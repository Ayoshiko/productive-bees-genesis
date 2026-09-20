package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;

/** 真实 FE 能力给蜂箱与离心机共同供电；模拟、欠费续作和旧能力失效均在世界中验证。 */
public final class NetworkEnergyProbe {
	private static final BlockPos POS = new BlockPos(4, 138, 4);
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekApiary hive;
	private static TileEntityMekCentrifuge centrifuge;
	private static NetworkSavedData data;
	private static NetworkCentrifugeService service;
	private static UUID hiveId, centrifugeId;
	private static IEnergyStorage stalePort;
	private static int phase, started;
	public static void start(MinecraftServer server) {
		var level = server.overworld(); started = server.getTickCount();
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
		var owner = UUID.randomUUID(); core.initializeOwner(owner);
		stalePort = level.getCapability(Capabilities.EnergyStorage.BLOCK, POS, Direction.UP);
		require(stalePort != null && stalePort.receiveEnergy(100, false) == 0, "Unbound core accepted FE");
		level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState()); hive = (TileEntityMekApiary) level.getBlockEntity(POS.east());
		hive.setOwnerUUID(owner); hive.setControlType(RedstoneControl.HIGH); hive.setFeederConversionEnabled(false);
		var entity = new CompoundTag(); entity.putString("id", "productivebees:configurable_bee"); entity.putString("type", "productivebees:iron"); entity.putUUID("UUID", UUID.randomUUID());
		var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any"); genes.putString("bee_productivity", "productivity.normal");
		var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); entity.put("neoforge:attachments", attachments);
		hive.getBeeSlot(0).setBeeData(entity); hive.getBeeSlot(0).setBaseMinOccupationTicks(5);
		hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK)); hive.energyContainer().setEnergy(100);
		level.setBlockAndUpdate(POS.west(), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState()); centrifuge = (TileEntityMekCentrifuge) level.getBlockEntity(POS.west());
		centrifuge.setOwnerUUID(owner); centrifuge.setControlType(RedstoneControl.HIGH);
	}
	public static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 4) return true;
		require(server.getTickCount() - started < 800, "Energy fixture timeout " + core.ownership().status());
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		if (phase == 0) {
			ModConfig.SERVER.beeNetwork.enabled.set(true); if (core.topology() == null) return false;
			require(core.ownership().command(true), "Energy takeover failed"); phase++; return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			data = directory.loadExisting(core.network()).ready();
			for (var record : data.checkpoint().ownedMachines().values()) {
				if (record.claim().machine().endsWith("mek_apiary")) hiveId = record.claim().member(); else centrifugeId = record.claim().member();
			}
			var beeService = new NetworkBeeService(data, directory); require(beeService.activate(level, hiveId, data.checkpoint().revision(), 0), "Energy bee activation failed");
			var bee = data.checkpoint().ownedMachines().get(hiveId).bees().bee(0); var input = bee.plan().output();
			var policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, data.checkpoint().policyRevision()).snapshot());
			service = new NetworkCentrifugeService(data, directory, policy);
			require(service.activate(level, centrifugeId, data.checkpoint().revision(), input), "Energy centrifuge activation failed");
			var before = data.checkpoint();
			require(NetworkEnergyService.migrate(level, data, directory, hiveId, before.revision(), true) && data.checkpoint() == before, "Migration simulation changed authority");
			for (var member : List.of(hiveId, centrifugeId)) {
				require(NetworkEnergyService.migrate(level, data, directory, member, data.checkpoint().revision(), false), "Member energy did not migrate");
				require(!NetworkEnergyService.migrate(level, data, directory, member, data.checkpoint().revision(), false), "Energy migrated twice");
			}
			require(data.checkpoint().energy().stored() == 100 && stalePort.receiveEnergy(100, false) == 0, "Duplicate energy or stale unbound capability");
			hive.setControlType(RedstoneControl.DISABLED); centrifuge.setControlType(RedstoneControl.DISABLED);
			require(beeService.advance(level, hiveId, 0, 0, 0, 0, 5, 1, false) == BeeWorkExecutor.Status.READY, "Shared FE did not fund bee");
			require(beeService.settle(level, hiveId, 0, data.checkpoint().ownedMachines().get(hiveId).bees().bee(0).revision()), "Shared bee result not credited");
			require(data.checkpoint().energy().stored() == 50, "Bee charged wrong shared FE");
			var offered = service.candidate(level, centrifugeId, input);
			var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(offered), 0, 1, 1).selection();
			require(service.assign(level, selection, 16, false), "Shared FE assignment failed");
			var state = data.checkpoint().ownedMachines().get(centrifugeId).centrifuge();
			require(service.work(level, centrifugeId, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 9999, false), "Shared FE work did not progress");
			state = data.checkpoint().ownedMachines().get(centrifugeId).centrifuge(); var job = state.jobs().get(0);
			require(!job.paid() && data.checkpoint().energy().stored() == 0 && state.energy() == 0, "Expected a paused funded segment");
			require(!service.work(level, centrifugeId, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false), "Empty shared account still progressed");
			var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, POS, Direction.NORTH); require(port != null, "Core FE capability missing");
			before = data.checkpoint(); data.publish(before.configureEnergy(15)); before = data.checkpoint();
			require(port.receiveEnergy(20, true) == 15 && data.checkpoint() == before, "FE simulation changed balance or exceeded capacity");
			require(port.receiveEnergy(20, false) == 15 && port.receiveEnergy(1, false) == 0 && port.receiveEnergy(-1, false) == 0, "FE accepted an invalid amount");
			require(port.extractEnergy(15, false) == 0, "Input-only core exported FE");
			data.publish(data.checkpoint().configureEnergy(ModConfig.SERVER.beeNetwork.energyCapacity.get()));
			long remaining = Math.multiplyExact(job.plan().cycleTicks() - job.progress(), job.plan().energyPerTick(job.operations()));
			require(port.receiveEnergy(Math.toIntExact(remaining - 15), false) == remaining - 15, "FE top-up failed");
			ModConfig.SERVER.beeNetwork.enabled.set(false);
			try {
				require(port.receiveEnergy(1, false) == 0, "Disabled core accepted FE");
				var savedBee = data.checkpoint().ownedMachines().get(hiveId).bees().bee(0);
				require(beeService.advance(level, hiveId, 0, savedBee.revision(), 0, 0, 1, 1, false) == BeeWorkExecutor.Status.DISABLED,
						"Disabled bee service progressed");
				require(!service.work(level, centrifugeId, state.revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false), "Disabled centrifuge progressed");
			}
			finally { ModConfig.SERVER.beeNetwork.enabled.set(true); }
			require(job.equals(data.checkpoint().ownedMachines().get(centrifugeId).centrifuge().jobs().get(0)), "Top-up changed pinned job");
			stalePort = port; var saved = core.saveWithFullMetadata(server.registryAccess());
			var replacement = new NetworkCoreBlockEntity(POS, core.getBlockState()); replacement.loadWithComponents(saved, server.registryAccess());
			level.removeBlockEntity(POS); level.setBlockEntity(replacement); core = replacement;
			require(stalePort.receiveEnergy(10, false) == 0, "Removed core capability remained writable"); phase++; return false;
		}
		if (phase == 2 && core.ownership().status() == CoreOwnershipController.Status.MANAGED) {
			var state = data.checkpoint().ownedMachines().get(centrifugeId).centrifuge(); var job = state.jobs().get(0);
			var expected = job.plan().sample(job.operations(), job.seed());
			for (var action : List.of(NetworkCentrifugeService.Action.ADVANCE, NetworkCentrifugeService.Action.FREEZE, NetworkCentrifugeService.Action.SETTLE)) {
				long revision = data.checkpoint().ownedMachines().get(centrifugeId).centrifuge().revision();
				require(service.work(level, centrifugeId, revision, action, 9999, false), "Energy resume failed: " + action);
			}
			require(data.checkpoint().energy().stored() == 0 && data.checkpoint().ledger().balances().equals(expected), "Shared production conservation failed");
			require(new MachineAssetStore(hive).empty() && new MachineAssetStore(centrifuge).empty(), "Physical source retained shared assets");
			hive.setControlType(RedstoneControl.HIGH); centrifuge.setControlType(RedstoneControl.HIGH);
			require(core.ownership().command(false), "Shared members could not return"); phase++; return false;
		}
		if (phase == 3 && core.ownership().status() == CoreOwnershipController.Status.STANDALONE) {
			require(hive.energyContainer().getEnergy() == 0 && centrifuge.energyContainer().getEnergy() == 0, "Returned a second copy of migrated FE");
			level.removeBlock(POS.east(), false); level.removeBlock(POS.west(), false); level.removeBlock(POS, false); phase = 4;
			report.addProperty("sharedCoreEnergyMigrationBeeCentrifugeAndResume", true);
			report.addProperty("sharedCoreFeSimulationCapacityDisabledAndStaleCapability", true); return true;
		}
		return false;
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private NetworkEnergyProbe() { }
}
