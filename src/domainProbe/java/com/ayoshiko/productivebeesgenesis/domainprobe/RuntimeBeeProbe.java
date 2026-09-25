package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import java.util.UUID;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 两个真实核心共用一步预算；生产由正式 ticker 推进，夹具只布置和观察。 */
final class RuntimeBeeProbe {
	private static final class Fixture {
		final BlockPos position;
		NetworkCoreBlockEntity core;
		TileEntityMekApiary hive;
		NetworkSavedData data;
		UUID member;
		int physicalTicker;
		long cost;
		boolean joined;
		Fixture(BlockPos position) { this.position = position; }
		BeeRecord bee() { return data.checkpoint().ownedMachines().get(member).bees().bee(0); }
		long output() { return data.checkpoint().ledger().balances().getOrDefault(bee().plan().output(), com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.ZERO).longSaturated(); }
		long cycles() { return output() / bee().plan().countPerCycle(); }
		void charge(ServerLevel level) {
			charge(level, cost);
		}
		void charge(ServerLevel level, long amount) {
			var port = level.getCapability(Capabilities.EnergyStorage.BLOCK, position, Direction.UP);
			require(port != null && port.receiveEnergy(Math.toIntExact(amount), false) == amount, "Runtime FE input failed");
		}
	}
	private static final Fixture[] FIXTURES = {new Fixture(new BlockPos(40, 150, 4)), new Fixture(new BlockPos(72, 150, 4))};
	private static int phase, started, until, previousBudget, previousTotalBudget;
	private static final int[] serviceChecks = new int[com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length];
	private static final long[] longestStep = new long[com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length];
	private static long previousWork, savesBeforePaidWork;
	private static final long MAINTENANCE = 7;
	private static long previousMaintenance;
	static void start(MinecraftServer server) {
		previousMaintenance = ModConfig.SERVER.beeNetwork.maintenanceFe.get(); ModConfig.SERVER.beeNetwork.maintenanceFe.set(MAINTENANCE);
		previousTotalBudget = ModConfig.SERVER.beeNetwork.totalSteps.get();
		started = server.getTickCount(); previousBudget = ModConfig.SERVER.beeNetwork.runtimeSteps.get(); ModConfig.SERVER.beeNetwork.runtimeSteps.set(1);
		var level = server.overworld();
		for (int i = 0; i < FIXTURES.length; i++) {
			var f = FIXTURES[i]; var owner = UUID.randomUUID(); level.setChunkForced(f.position.getX() >> 4, 0, true);
			level.setBlockAndUpdate(f.position, NetworkContent.CORE.get().defaultBlockState()); f.core = (NetworkCoreBlockEntity) level.getBlockEntity(f.position); f.core.initializeOwner(owner);
			level.setBlockAndUpdate(f.position.east(), ModBlocks.MEK_APIARY.get().defaultBlockState()); f.hive = (TileEntityMekApiary) level.getBlockEntity(f.position.east());
			f.hive.setOwnerUUID(owner); f.hive.setControlType(RedstoneControl.HIGH); f.hive.setFeederConversionEnabled(false);
			var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any");
			genes.putString("bee_productivity", i == 0 ? "productivity.normal" : "productivity.very_high");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
			f.hive.getBeeSlot(0).setBeeData(bee); f.hive.getBeeSlot(0).setBaseMinOccupationTicks(5);
			f.hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
		}
	}
	static boolean advance(MinecraftServer server, JsonObject report) {
		if (phase == 11) return true;
		var budget = com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.budget(server);
		if (phase > 0 && server.getTickCount() > started && budget != null) {
			require(budget.attempts() <= 1, "Subsystems overspent the total one-step budget");
			for (int i = 0; i < com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length; i++) {
				serviceChecks[i] += budget.used(i); longestStep[i] = Math.max(longestStep[i], budget.longestStepNanos(i));
			}
		}
		if (server.getTickCount() - started >= 2400) throw new IllegalStateException("Runtime probe timeout at phase " + phase
				+ ": " + java.util.Arrays.stream(FIXTURES).map(f -> f.core.ownership().status() + "/" + f.core.runtime().status()
				+ "/running=" + f.core.productionRunning() + "/topology=" + (f.core.topology() != null)).toList());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		for (var f : FIXTURES) require(f.core.ownership().status() != CoreOwnershipController.Status.RECOVERY, f.core.ownership().failure());
		if (phase == 0) {
			for (var f : FIXTURES) {
				if (!f.joined && f.core.topology() != null) { require(f.core.ownership().command(true), "Runtime takeover failed"); f.joined = true; }
				if (f.core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			}
			for (var f : FIXTURES) {
				f.data = directory.loadExisting(f.core.network()).ready(); var record = f.data.checkpoint().ownedMachines().values().iterator().next(); f.member = record.claim().member();
				require(record.bees() == null && !f.core.hasProductionSession(), "Custody unexpectedly started production");
				f.physicalTicker = f.hive.ticker; f.hive.setControlType(RedstoneControl.DISABLED); require(f.core.setProductionRunning(true), "Runtime start failed");
			}
			ModConfig.SERVER.beeNetwork.totalSteps.set(1); started = server.getTickCount(); phase = 1; return false;
		}
		if (phase < 10) for (var f : FIXTURES) require(f.hive.ticker == f.physicalTicker && new MachineAssetStore(f.hive).empty(), "Runtime executed a physical member ticker or inventory");
		if (phase == 1) {
			for (var f : FIXTURES) {
				var state = f.data.checkpoint().ownedMachines().get(f.member).bees();
				if (state == null || !state.networkPowered()) return false;
				require(f.bee().progress() == 0 && f.cycles() == 0, "Unfunded runtime progressed");
			}
			for (var f : FIXTURES) { f.cost = Math.multiplyExact(f.bee().plan().cycleTicks(), f.bee().plan().energyPerTick() + MAINTENANCE); require(f.cost > 0, "Fixture must charge FE"); f.charge(level); }
			phase = 2; return false;
		}
		if (phase == 2) {
			long work = 0; for (var f : FIXTURES) work += f.cycles() * f.bee().plan().cycleTicks() + f.bee().progress();
			require(work >= previousWork && work - previousWork <= 1, "Networks exceeded their one-step shared budget"); previousWork = work;
			for (var f : FIXTURES) if (f.cycles() != 1 || !f.bee().drained()) return false;
			for (var f : FIXTURES) if (!ready(f)) return false;
			for (var f : FIXTURES) { require(f.data.checkpoint().energy().stored() == 0, "Runtime charged a wrong cycle price"); f.charge(level); }
			require(FIXTURES[0].core.setProductionRunning(false), "Pause failed"); FIXTURES[1].hive.setControlType(RedstoneControl.HIGH); flower(level, directory, FIXTURES[1], true);
			until = server.getTickCount() + 30; phase = 3; return false;
		}
		if (phase == 3) {
			for (var f : FIXTURES) require(f.cycles() == 1 && f.bee().progress() == 0 && f.data.checkpoint().energy().stored() == f.cost, "Paused member advanced");
			if (server.getTickCount() < until) return false;
			if (!ready(FIXTURES[0])) return false;
			require(FIXTURES[0].core.setProductionRunning(true), "Resume failed"); phase = 4; return false;
		}
		if (phase == 4) {
			var f = FIXTURES[0]; if (f.bee().progress() == 0) return false;
			require(f.bee().progress() < f.bee().plan().cycleTicks(), "No partial work before reload");
			var saved = f.core.saveWithFullMetadata(server.registryAccess()); var old = f.core;
			var replacement = new NetworkCoreBlockEntity(f.position, old.getBlockState()); replacement.loadWithComponents(saved, server.registryAccess());
			level.removeBlockEntity(f.position); level.setBlockEntity(replacement); f.core = replacement;
			require(f.core.productionRunning() && !old.setProductionRunning(true), "Reloaded start state or stale host check failed");
			FIXTURES[1].hive.setControlType(RedstoneControl.DISABLED); phase = 5; return false;
		}
		if (phase == 5) {
			var blocked = FIXTURES[1]; require(blocked.cycles() == 1 && blocked.bee().progress() == 0 && blocked.data.checkpoint().energy().stored() == blocked.cost, "Missing flower produced output");
			if (FIXTURES[0].cycles() != 2 || !ready(blocked)) return false;
			require(FIXTURES[0].data.checkpoint().energy().stored() == 0, "Reload duplicated energy or progress");
			flower(level, directory, blocked, false); phase = 6; return false;
		}
		if (phase == 6) {
			if (FIXTURES[1].cycles() != 2 || !ready(FIXTURES[0])) return false;
			for (var f : FIXTURES) require(f.core.setProductionRunning(false), "Final pause failed");
			var f = FIXTURES[0]; f.charge(level, Math.multiplyExact(f.bee().plan().cycleTicks(), f.bee().plan().energyPerTick()));
			savesBeforePaidWork = directory.saveStatus().submitted();
			var bee = f.bee(); require(new NetworkBeeService(f.data, directory).advance(level, f.member, 0, bee.revision(), 0, 0,
					bee.plan().cycleTicks(), 0, false) == BeeWorkExecutor.Status.READY, "Paid pending fixture failed");
			require(f.bee().pendingCycles() == 1 && f.data.checkpoint().energy().stored() == 0, "Pending fixture was not exactly paid"); phase = 7; return false;
		}
		if (phase == 7) {
			var f = FIXTURES[0]; if (!f.bee().drained()) return false;
			require(directory.saveStatus().submitted() == savesBeforePaidWork, "Ordinary runtime work started a full checkpoint write");
			require(f.cycles() == 3 && f.data.checkpoint().energy().stored() == 0, "Paused paid work was lost or charged twice");
			f.charge(level); f.core.toggleFace(Direction.EAST); until = server.getTickCount() + 30; phase = 8; return false;
		}
		if (phase == 8) {
			var f = FIXTURES[0]; if (!f.core.productionRunning() && f.core.topology() != null) require(f.core.setProductionRunning(true), "Disconnected core start failed");
			require(f.cycles() == 3 && f.bee().progress() == 0 && f.data.checkpoint().energy().stored() == f.cost, "Disconnected member progressed");
			if (server.getTickCount() < until || !f.core.productionRunning()) return false;
			f.core.toggleFace(Direction.EAST); phase = 9; return false;
		}
		if (phase == 9) {
			if (FIXTURES[0].cycles() != 4) return false;
			for (var f : FIXTURES) {
				require(f.data.checkpoint().energy().stored() == 0 && f.bee().drained(), "Final runtime conservation failed");
				f.core.setProductionRunning(false); f.hive.setControlType(RedstoneControl.HIGH); require(f.core.ownership().command(false), "Runtime return failed");
			}
			phase = 10; return false;
		}
		if (phase == 10) {
			for (var f : FIXTURES) if (f.core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
			for (var f : FIXTURES) {
				require(f.hive.energyContainer().getEnergy() == 0 && !f.hive.getBeeSlot(0).isEmpty(), "Runtime return lost bee or duplicated FE");
				level.removeBlock(f.position.east(), false); level.removeBlock(f.position, false); level.setChunkForced(f.position.getX() >> 4, 0, false);
			}
			ModConfig.SERVER.beeNetwork.runtimeSteps.set(previousBudget); ModConfig.SERVER.beeNetwork.totalSteps.set(previousTotalBudget); phase = 11;
			ModConfig.SERVER.beeNetwork.maintenanceFe.set(previousMaintenance);
			report.addProperty("runtimeBeeMaintenanceExactAndPaidSettlementFree", true);
			for (int i = 0; i < com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkTickService.Service.values().length; i++) {
				require(serviceChecks[i] > 0, "A service was starved"); report.addProperty("sharedBudgetServiceChecks" + i, serviceChecks[i]);
				report.addProperty("sharedBudgetLongestStepNanos" + i, longestStep[i]);
			}
			report.addProperty("allNetworkServicesShareOneRealTickBudget", true);
			report.addProperty("runtimeTwoNetworksShareOneStepAndRetainIndividualGenes", true);
			report.addProperty("runtimeBeePauseFlowerEnergyCoreReloadAndTopology", true);
			report.addProperty("runtimePausedPaidOutputSettlesWithoutPhysicalTicks", true); return true;
		}
		return false;
	}
	private static void flower(ServerLevel level, NetworkDirectory directory, Fixture fixture, boolean disabled) {
		var feeding = fixture.data.checkpoint().ownedMachines().get(fixture.member).bees().feeding();
		require(new NetworkFeedingService(fixture.data, directory).apply(level, fixture.member, feeding.revision(), feeding.disabled(0, disabled), false), "Flower fixture failed");
	}
	private static boolean ready(Fixture fixture) {
		var topology = fixture.core.topology();
		return topology != null && topology.valid() && fixture.core.ownership().readyAuthority() != null;
	}
	private RuntimeBeeProbe() { }
}
