package com.ayoshiko.productivebeesgenesis.capacityprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.MachineCapacityReader;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierConfigService;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierKey;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 真实 BE 能力采集验证，无蜜蜂／输入，不执行网络生产。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class D03Server {
	private static D03Server active;
	private final MinecraftServer server;
	private final List<BlockEntity> machines = new ArrayList<>();
	private int ticks;

	private D03Server(MinecraftServer server) {
		this.server = server;
		server.overworld().setChunkForced(0, 0, true);
		for (String name : List.of("mek_apiary", "ultimate_mek_apiary_factory", "mek_centrifuge", "ultimate_mek_centrifuge_factory")) {
			var pos = new BlockPos(machines.size() * 3, 80, 0);
			var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:" + name));
			server.overworld().setBlockAndUpdate(pos, block.defaultBlockState());
			machines.add(server.overworld().getBlockEntity(pos));
		}
	}

	@SubscribeEvent
	public static void started(ServerStartedEvent event) {
		if (Boolean.getBoolean("pbg.d03.enabled")) active = new D03Server(event.getServer());
	}
	@SubscribeEvent
	public static void stopped(ServerStoppedEvent event) { active = null; }
	@SubscribeEvent
	public static void tick(ServerTickEvent.Post event) {
		if (active == null || ++active.ticks != 40) return;
		D03Server run = active;
		JsonObject report = new JsonObject();
		try {
			run.verify(report);
			report.addProperty("passed", true);
			LogUtils.getLogger().info("D03_COMPLETE");
		} catch (Exception failure) {
			report.addProperty("passed", false);
			report.addProperty("failure", failure.toString());
			LogUtils.getLogger().error("D03_FAILED", failure);
		}
		try {
			Files.createDirectories(Path.of("results"));
			Files.writeString(Path.of("results/d03-capacity.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
		} catch (Exception failure) {
			LogUtils.getLogger().error("Cannot persist D03 report", failure);
		} finally {
			active = null;
			run.server.halt(false);
		}
	}

	private void verify(JsonObject report) {
		report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
		var work = new WorkKey(WorkKey.Kind.BEE_CYCLE, "productivebees:iron", 1, "period1200-potential");
		var queries = List.of(new MachineCapacityReader.BeeCycleQuery(work, 1200));
		var recipe = CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron"));
		require(recipe != null, "Missing iron recipe");
		var snapshots = new ArrayList<MemberCapabilitySnapshot>();
		var gson = new GsonBuilder().create();
		JsonArray captures = new JsonArray();
		for (BlockEntity machine : machines) {
			UUID id = UUID.randomUUID();
			var before = machine.saveWithFullMetadata(server.registryAccess()).copy();
			MemberCapabilitySnapshot first;
			MemberCapabilitySnapshot upgraded;
			if (machine instanceof TileEntityMekApiary hive) {
				first = MachineCapacityReader.apiary(hive, id, 1, queries);
				require(first.beeSlots() == hive.getBeeSlotCount() && first.feedingSlots() == first.beeSlots(), "Incorrect bee slots");
				require(machine.saveWithFullMetadata(server.registryAccess()).equals(before), "Snapshot mutated hive NBT");
				hive.getComponent().addUpgrades(Upgrade.SPEED, 8);
				hive.getComponent().addUpgrades(Upgrade.ENERGY, 8);
				hive.installPbUpgradeBulk(PbUpgradeType.TIME, 8);
				upgraded = MachineCapacityReader.apiary(hive, id, 2, queries);
				require(upgraded.requireCapacity(work).cycleTicks() == 75, "Expected actual BASIC speed and TIME4");
				require(first.requireCapacity(work).cycleTicks() == 1200, "Old snapshot changed");
				hive.setFeederConversionEnabled(true);
				var conversion = MachineCapacityReader.apiary(hive, id, 3, queries);
				require(!conversion.requireCapacity(work).equals(upgraded.requireCapacity(work)), "Feeder conversion conflated with normal production");
				hive.setFeederConversionEnabled(false);
			} else {
				PbRecipeContext centrifuge = (PbRecipeContext) machine;
				first = MachineCapacityReader.centrifuge(centrifuge, id, 1, 1, List.of(recipe));
				require(first.laneCount() == centrifuge.processes(), "Incorrect process count");
				require(machine.saveWithFullMetadata(server.registryAccess()).equals(before), "Snapshot mutated centrifuge NBT");
				var tile = (TileEntityMekanism) machine;
				tile.getComponent().addUpgrades(Upgrade.SPEED, 8);
				tile.getComponent().addUpgrades(Upgrade.ENERGY, 8);
				for (Upgrade upgrade : Upgrade.values()) {
					if (MekUpgradeSupport.isStackUpgrade(upgrade)) tile.getComponent().addUpgrades(upgrade, 3);
				}
				((com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider) machine).installPbUpgradeBulk(PbUpgradeType.PRODUCTIVITY, 4);
				upgraded = MachineCapacityReader.centrifuge(centrifuge, id, 2, 1, List.of(recipe));
				var fast = upgraded.alternatives().getFirst();
				require(centrifuge.operationsPerTick() > 1, "STACK did not provide parallelism");
				require(fast.cycleTicks() < first.alternatives().getFirst().cycleTicks(), "Speed not reflected");
				require(fast.operationsPerCycle() == Math.max(1, centrifuge.operationsPerTick()) * centrifuge.productivityParallelModifier(), "PB parallel missing");
				int oldLimit = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
				try {
					ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.set(1);
					var limited = MachineCapacityReader.centrifuge(centrifuge, id, 3, 1, List.of(recipe));
					require(limited.alternatives().getFirst().operationsPerCycle() == centrifuge.productivityParallelModifier(), "Cap incorrectly removed PB parallel");
				} finally {
					ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.set(oldLimit);
				}
			}
			JsonObject pair = new JsonObject();
			pair.add("before", gson.toJsonTree(first));
			pair.add("after", gson.toJsonTree(upgraded));
			captures.add(pair);
			snapshots.add(upgraded);
		}
		var totals = new CapacityPoolIndex(snapshots);
		require(totals.onlineBeeSlots().intValueExact() == 23, "Expected base3 plus ultimate20 bee slots");
		var limits = MachineCapacityReader.factoryBufferLimits(FactoryTierKey.ULTIMATE);
		require(limits.outputStackMultiplier() == FactoryTierConfigService.current().centrifugeOutputStack(FactoryTierKey.ULTIMATE), "Wrong capacity configuration");
		report.add("captures", captures);
		report.add("bufferLimits", gson.toJsonTree(limits));
		report.add("beeSummary", gson.toJsonTree(totals.forWork(work)));
		report.addProperty("capturePreservedMachineNbt", true);
		var stale = snapshots.getFirst();
		server.overworld().removeBlock(machines.getFirst().getBlockPos(), false);
		try {
			MachineCapacityReader.apiary((TileEntityMekApiary) machines.getFirst(), stale.memberId(), 3, queries);
			throw new IllegalStateException("Removed BE accepted");
		} catch (IllegalStateException expected) {
			require(expected.getMessage().contains("live member"), "Unexpected capture error");
		}
		report.addProperty("removedMemberRejected", true);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new IllegalStateException(message);
	}
}
