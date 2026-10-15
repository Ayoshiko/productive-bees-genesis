package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeJob;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.*;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 正式队列、菜单和落盘恢复夹具；不直接调用生产 step。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class CombinedNetworkProbe {
	private static final BlockPos POS = new BlockPos(1012, 128, 1012);
	private static final JsonObject report = new JsonObject();
	private static MachineProbeFixture structure;
	private static MachineProductionFixture standalone;
	private static NetworkCoreBlockEntity core;
	private static NetworkSavedData data;
	private static CombinedMachineWork original, previous;
	private static NetworkCheckpoint checkpoint;
	private static CompoundTag marker;
	private static IItemHandler oldOutput;
	private static MachineWorkService.Access oldAccess;
	private static final Map<ProductKey, ProductAmount> expected = new HashMap<>();
	private static long expectedEnergy, workFe, maintenanceTicks, completedBees, completedJobs, assignedJobs;
	private static UUID basicMember;
	private static CentrifugeJob basicPrevious;
	private static long basicCompleted, probabilityProducts;
	private static CombinedManagementProbe managementProbe;
	public static volatile boolean clientReady, clientVerified, normalShutdown;
	public static volatile String clientFailure;
	public static volatile BlockPos clientCore;
	public static volatile int clientStage;
	private static final Set<Integer> advancedBees = new HashSet<>(), advancedLanes = new HashSet<>();
	private static int phase, started, oldCycle, stable;
	private static boolean enabled() { return Boolean.getBoolean("pbg.multiblock.network"); }
	private static boolean reading() { return "read".equals(System.getProperty("pbg.multiblock.mode")); }
	private static Path markerFile(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("multiblock-probe.dat"); }
	@SubscribeEvent public static void started(ServerStartedEvent event) {
		if (!enabled()) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			oldCycle = ModConfig.SERVER.apiaryProcessingTime.get(); ModConfig.SERVER.apiaryProcessingTime.set(40);
			ModConfig.SERVER.beeNetwork.enabled.set(true); ModConfig.SERVER.beeNetwork.maintenanceFe.set(7L);
			var template = CombinedApiaryDefinition.DEFINITION.candidates().getFirst();
			marker = reading() ? NbtIo.readCompressed(markerFile(server), NbtAccounter.unlimitedHeap()) : new CompoundTag();
			if (reading()) {
				check(marker.getLong("writerPid") != ProcessHandle.current().pid(), "Restart reused JVM");
				var shell = new MachineProbeFixture(template, POS, Direction.NORTH, null); MachineLifecycleProbe.force(level, shell, true);
				structure = new MachineProbeFixture(template, POS, Direction.NORTH, (MachineControllerEntity) level.getBlockEntity(POS));
				core = (NetworkCoreBlockEntity) level.getBlockEntity(BlockPos.of(marker.getLong("core")));
					basicMember = marker.getUUID("basicMember");
				check(core != null && structure.core() != null, "Missing saved controllers"); original = decode(marker.getCompound("original"));
				report.addProperty("newJvm", true);
			} else {
				marker.putLong("writerPid", ProcessHandle.current().pid());
					structure = MachineProbeFixture.place(level, template, POS, Direction.NORTH, server.isDedicatedServer() ? UUID.randomUUID() : server.getSingleplayerProfile().getId());
			}
			started = server.getTickCount(); phase = 1;
		} catch (Exception failure) { fail(server, failure); }
	}
	@SubscribeEvent(priority = EventPriority.LOWEST) public static void tick(ServerTickEvent.Post event) {
		if (!enabled() || phase == 0 || phase == 99) return;
		var server = event.getServer(); var level = server.overworld();
		try {
			if ((server.getTickCount() - started) % 200 == 0) com.mojang.logging.LogUtils.getLogger().info("COMBINED_NETWORK_PROGRESS phase={} structure={} topology={} runtime={}", phase, structure.core().status(), core == null ? null : core.topology(), core == null ? null : core.runtime().status());
			check(server.getTickCount() - started < 3500, "Combined probe timeout phase=" + phase + " core=" + (core == null ? "none" : core.ownership().status() + "/" + core.runtime().status()));
			check(level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(24)).isEmpty(), "Asset emitted as world drop");
			if (phase >= 5 && phase <= 7 || phase == 11 || phase == 12) observe();
			switch (phase) {
				case 1 -> {
					if (!structure.core().formed() || !reading() && RuntimeProductPolicies.peek(level) == null) return;
					if (reading()) {
						data = core.ownership().readyAuthority(); if (data == null || core.topology() == null || !core.topology().valid()) return;
						var work = data.checkpoint().ownedMachines().get(structure.core().machineId()).combined();
						check(work != null && CombinedWorkCodec.encode(work).equals(CombinedWorkCodec.encode(decode(marker.getCompound("managed")))), "Managed work changed during restart");
						check(NetworkCheckpointCodec.encode(data.checkpoint()).getCompound("energy").equals(marker.getCompound("energy")), "Restart changed shared FE");
						check(data.checkpoint().ledger().balances().equals(new ProductRecordCodec(key -> {}).readAmounts(marker.getList("balances", 10))), "Restart changed products");
						check(structure.core().assets.empty() && !structure.core().assets.available(), "Restart exposed second owner");
						baseline(work, data.checkpoint().energy().stored()); expected.putAll(data.checkpoint().ledger().balances());
						var basic = data.checkpoint().ownedMachines().get(basicMember).centrifugeState();
						basicPrevious = basic == null ? null : basic.jobs().get(0);
						report.addProperty("managedCheckpointRestored", true); check(core.setProductionRunning(true), "Cannot resume network"); phase = 5;
					} else {
						standalone = new MachineProductionFixture(level, structure, new MachineProductionLedger(structure.core().assets.work()));
						standalone.seed(false); phase = 2;
					}
				}
				case 2 -> {
					var work = structure.core().assets.work();
					if (work.centrifuges().size() != 3 || work.centrifuges().values().stream().anyMatch(d -> d.job().progress() < 2 || d.job().paid()) || work.bees().stream().noneMatch(b -> b.progress() > 0)) return;
					var outputPos = structure.world(structure.template().features().entrySet().stream().filter(e -> e.getValue().roles().contains(StructureRole.OUTPUT_PORT)).findFirst().orElseThrow().getKey());
					oldOutput = level.getCapability(Capabilities.ItemHandler.BLOCK, outputPos, level.getBlockState(outputPos).getValue(MachinePartBlock.FACING));
					oldAccess = MachineWorkService.access(structure.core()).orElseThrow();
					var port = structure.world(new BlockPos(1, 1, 0));
					level.setBlockAndUpdate(port, MachineContent.block(StructureRole.NETWORK_PORT).defaultBlockState().setValue(MachinePartBlock.FACING, Direction.NORTH));
					var corePos = port.north();
					for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced((corePos.getX() >> 4) + dx, (corePos.getZ() >> 4) + dz, true);
					level.setBlockAndUpdate(corePos, NetworkContent.CORE.get().defaultBlockState());
					core = (NetworkCoreBlockEntity) level.getBlockEntity(corePos); core.initializeOwner(structure.core().ownerId());
					level.setBlockAndUpdate(corePos.north(), com.ayoshiko.productivebeesgenesis.init.ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
					var basic = (com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge) level.getBlockEntity(corePos.north());
					basic.setOwnerUUID(structure.core().ownerId()); basic.getComponent().addUpgrades(mekanism.api.Upgrade.SPEED, 2);
					level.setBlockAndUpdate(corePos.west(), NetworkContent.SUPPLY_PORT.get().defaultBlockState());
					((com.ayoshiko.productivebeesgenesis.apiculture.port.NetworkPortBlockEntity) level.getBlockEntity(corePos.west())).initializeOwner(structure.core().ownerId());
					level.setBlockAndUpdate(corePos.east(), NetworkContent.BEE_TERMINAL.get().defaultBlockState());
					marker.putLong("core", corePos.asLong()); phase = 3;
				}
				case 3 -> {
					if (!structure.core().formed() || core.topology() == null || !core.topology().valid() || !core.topology().hasMember(POS)) return;
					check(core.topology().members().size() == 2, "Shell or port duplicated membership");
					check(core.ownership().command(true), "Join command refused"); phase = 4;
				}
				case 4 -> {
					check(core.ownership().status() != com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
					data = core.ownership().readyAuthority(); if (data == null || data.checkpoint().ownedMachines().activeCount() != 2) return;
					for (var member : data.checkpoint().ownedMachines().activeValues()) if (member.claim().machine().endsWith(":mek_centrifuge")) basicMember = member.claim().member();
					check(basicMember != null, "Missing basic competitor"); marker.putUUID("basicMember", basicMember);
					var record = data.checkpoint().ownedMachines().get(structure.core().machineId());
					check(record != null, "Join replaced machine UUID");
					original = new MachineOwnershipEndpoint(structure.core()).decode(record.assets());
					check(original.centrifuges().size() == 3 && original.bees().size() == 6, "Lost independent partial work");
					check(!oldAccess.current() && oldOutput.getSlots() == 0, "Cached standalone access survived join");
					baseline(original, original.energy()); check(core.setProductionRunning(true), "Cannot start managed production");
					report.addProperty("independentPartialWorkTransferred", true); phase = 5;
				}
				case 5 -> {
					if (data.checkpoint().ownedMachines().get(structure.core().machineId()).combined() == null) return;
					check(MachineWorldService.tracked(server) == 1, "Duplicate independent controller");
					if (completedBees < 12 || completedJobs < 3 || assignedJobs < 3 || basicCompleted < 1 || Boolean.getBoolean("pbg.multiblock.multi") && probabilityProducts == 0) return;
					check(advancedBees.size() == 6 && advancedLanes.size() == 3, "Typed scheduler missed a bee or lane");
					report.addProperty("formalTypedProduction", true); report.addProperty("singleIdentitySixBeesThreeLanes", true);
					report.addProperty("newNetworkInputsAssigned", true);
					check(core.setProductionRunning(false), "Management pause failed");
					managementProbe = new CombinedManagementProbe(core, structure.core().machineId(), basicMember, level);
					phase = 12;
				}
				case 12 -> {
					if (managementProbe != null) { if (!managementProbe.step()) return; managementProbe = null; }
					if (Boolean.getBoolean("pbg.combined.client") && !clientVerified) {
						check(clientFailure == null, clientFailure);
						if (!clientReady) {
							var actual = server.getPlayerList().getPlayers().getFirst();
							actual.teleportTo(core.getBlockPos().getX() + 0.5, core.getBlockPos().getY() + 2, core.getBlockPos().getZ() + 0.5);
							actual.getInventory().items.set(0, ItemStack.EMPTY); actual.inventoryMenu.broadcastChanges();
							clientCore = core.getBlockPos(); clientReady = true;
						}
						var work = data.checkpoint().ownedMachines().get(structure.core().machineId()).combined();
						if (clientStage == 1 && work.feeding().get(5).count() == 0) clientStage = 11;
						if (clientStage == 2 && work.feeding().get(5).count() == 1) clientStage = 12;
						return;
					}
					report.addProperty("unifiedSixthSlotSupplyAndPermissions", true);
					report.addProperty("heterogeneousCapacityAndCompetition", true);
					management(level, reading());
					report.addProperty("managedMenuExchange", true);
					if (reading()) { check(core.setProductionRunning(true), "Drain resume failed"); phase = 6; }
					else {
						oldAccess = MachineWorkService.access(structure.core()).orElseThrow(); checkpoint = data.checkpoint();
						core.toggleFace(Direction.SOUTH); stable = 0; phase = 10;
					}
				}
				case 6 -> {
					var work = data.checkpoint().ownedMachines().get(structure.core().machineId()).combined();
					var basic = data.checkpoint().ownedMachines().get(basicMember).centrifugeState();
					if (!work.drained() || basic != null && !basic.drained()) return;
					check(core.setProductionRunning(false), "Return pause rejected");
					check(core.ownership().command(false), "Safe return refused"); checkpoint = data.checkpoint(); phase = 8;
				}
				case 7 -> {
					if (++stable < 30) return;
					checkpoint = data.checkpoint(); marker.put("managed", CombinedWorkCodec.encode(previous));
					marker.put("original", CombinedWorkCodec.encode(original)); marker.put("energy", NetworkCheckpointCodec.encode(checkpoint).getCompound("energy"));
					marker.put("balances", ProductRecordCodec.amounts(checkpoint.ledger().balances())); NbtIo.writeCompressed(marker, markerFile(server)); finish(server);
				}
				case 8 -> {
					if (core.ownership().busy()) return;
					check(structure.core().assets.available(), "Returned source still unavailable");
					check(data.checkpoint().ownedMachines().activeCount() == 0, "Network still owns returned machine");
					check(data.checkpoint().ledger().equals(checkpoint.ledger()) && data.checkpoint().energy().equals(checkpoint.energy()), "Return transferred shared products or FE");
					var work = structure.core().assets.work();
					check(work.bees().size() == 6 && work.energy() == 0 && sameBuffer(work, original) && work.upgrades().count(2) == 8 && work.upgrades().count(3) == 8, "Finite return assets changed");
					var player = player(level); var menu = new MachineMenu(91, player.getInventory(), structure.core(), UUID.randomUUID()); player.containerMenu = menu;
					player.getInventory().items.set(0, new ItemStack(Items.IRON_BLOCK));
					check(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_IN, 0, 0, 1, false).moved() == 1, "Returned feeding unavailable");
					var powerPos = structure.world(structure.template().features().entrySet().stream().filter(e -> e.getValue().roles().contains(StructureRole.ENERGY_PORT)).findFirst().orElseThrow().getKey());
					var energy = level.getCapability(Capabilities.EnergyStorage.BLOCK, powerPos, level.getBlockState(powerPos).getValue(MachinePartBlock.FACING));
					int fuel = Boolean.getBoolean("pbg.multiblock.multi") ? 1_000_000 : 10000;
					check(energy != null && energy.receiveEnergy(fuel, false) == fuel, "Returned FE input unavailable");
					if (Boolean.getBoolean("pbg.multiblock.multi")) {
						player.getInventory().items.set(0, new ItemStack(Items.BONE_BLOCK));
						check(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_IN, 4, 0, 1, false).moved() == 1, "Returned skeletal feeding unavailable");
					}
					previous = structure.core().assets.work(); phase = 9;
				}
				case 9 -> {
					var work = structure.core().assets.work(); if (work.bee(0).revision() == previous.bee(0).revision()) return;
					check(work.energy() < previous.energy(), "Independent production did not resume");
					if (Boolean.getBoolean("pbg.multiblock.multi")) {
						var bone = com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.item(new ItemStack(Items.BONE), level.registryAccess());
						if (work.buffer().count(bone) <= previous.buffer().count(bone)) return;
						check(work.bee(4).random().cursor() > previous.bee(4).random().cursor(), "Independent probability result had no paid cycle");
						report.addProperty("independentProbabilityOutput", true);
					}
					report.addProperty("returnAndIndependentResume", true); finish(server);
				}
				case 10 -> {
					check(!oldAccess.current() && MachineWorkService.access(structure.core()).isEmpty(), "Disconnected managed access survived");
					check(!structure.core().assets.available() && structure.core().assets.empty(), "Disconnection returned ownership without receipts");
					check(data.checkpoint() == checkpoint, "Disconnected machine changed work, FE or products");
					if (++stable < 30) return;
					core.toggleFace(Direction.SOUTH); phase = 11;
				}
				case 11 -> {
					if (MachineWorkService.access(structure.core()).isEmpty()) return;
					check(!oldAccess.current(), "Reconnection revived an old access token");
					report.addProperty("disconnectionRetainsCustodyAndRevokesAccess", true);
					check(core.setProductionRunning(false), "Pause rejected"); phase = 7; stable = 0;
				}
				default -> throw new IllegalStateException("Unknown phase");
			}
		} catch (Exception failure) { fail(server, failure); }
	}
	private static void baseline(CombinedMachineWork work, long energy) { previous = work; expectedEnergy = energy; expected.clear(); }
	private static void observe() {
		var record = data.checkpoint().ownedMachines().get(structure.core().machineId()); if (record.combined() == null) return;
		var work = record.combined(); var current = data.checkpoint(); boolean progressed = false;
		check(structure.core().assets.empty() && !MachineWorkService.active(structure.core()), "Independent producer resumed while managed");
		check(sameBuffer(work, original), "Network consumed finite return inventory");
		for (var before : previous.bees()) {
			var after = work.bee(before.slot()); int delta = Math.floorMod(after.progress() - before.progress(), after.plan().cycleTicks());
			check(delta <= 1 && before.id().equals(after.id()), "Bee caught up or changed identity");
			if (delta > 0) { progressed = true; expectedEnergy -= after.plan().energyPerTick(); workFe += after.plan().energyPerTick(); advancedBees.add(after.slot()); }
			long cycles = after.random().cursor() - before.random().cursor();
			completedBees += cycles;
			com.ayoshiko.productivebeesgenesis.apiculture.production.BeeOutputProbeOracle.produced(before, after).forEach((key, amount) -> {
				add(key, amount); if (key.id().toString().equals("minecraft:bone")) probabilityProducts += amount.longSaturated();
			});
			before.frozenOutputs().forEach(CombinedNetworkProbe::add);
			after.frozenOutputs().forEach((key, count) -> subtract(key, count));
		}
		for (int lane = 0; lane < 3; lane++) {
			var oldDelivery = previous.centrifuges().get(lane); var nextDelivery = work.centrifuges().get(lane);
			var before = oldDelivery == null ? null : oldDelivery.job(); var after = nextDelivery == null ? null : nextDelivery.job();
			check(before == null || after == null || before.id().equals(after.id()), "Lane replaced more than one job per tick");
			var active = after == null ? before : after;
			if (active == null) continue;
			int delta = (after == null ? active.plan().cycleTicks() : after.progress()) - (before == null ? 0 : before.progress());
			check(delta >= 0 && delta <= 1, "Centrifuge caught up");
			if (delta > 0) { progressed = true; long cost = active.plan().energyPerTick(active.operations()); expectedEnergy -= cost; workFe += cost; advancedLanes.add(lane); }
			if (before == null) { assignedJobs++; subtract(after.plan().input(), after.heldInputs()); }
			if (after == null) {
				completedJobs++;
				var frozen = active.sampled() ? active : new CentrifugeJob(active.id(), active.plan(), active.operations(), active.plan().cycleTicks(), active.seed(), null).freeze();
				frozen.frozen().forEach(CombinedNetworkProbe::add);
				oldDelivery.delivered().forEach(CombinedNetworkProbe::subtract);
			}
		}
		var basicState = current.ownedMachines().get(basicMember).centrifugeState();
		var basicAfter = basicState == null ? null : basicState.jobs().get(0);
		var active = basicAfter == null ? basicPrevious : basicAfter;
		if (active != null) {
			check(basicPrevious == null || basicAfter == null || basicPrevious.id().equals(basicAfter.id()), "Basic lane replaced active work");
			int delta = (basicAfter == null ? active.plan().cycleTicks() : basicAfter.progress()) - (basicPrevious == null ? 0 : basicPrevious.progress());
			check(delta >= 0 && delta <= 1, "Basic lane caught up");
			if (delta > 0) { progressed = true; long cost = active.plan().energyPerTick(active.operations()); expectedEnergy -= cost; workFe += cost; }
			if (basicPrevious == null) subtract(basicAfter.plan().input(), basicAfter.heldInputs());
			if (basicAfter == null) {
				basicCompleted++;
				var frozen = active.sampled() ? active : new CentrifugeJob(active.id(), active.plan(), active.operations(), active.plan().cycleTicks(), active.seed(), null).freeze();
				frozen.frozen().forEach(CombinedNetworkProbe::add);
			}
		}
		basicPrevious = basicAfter;
		if (progressed) { expectedEnergy -= 7; maintenanceTicks++; }
		check(current.energy().stored() == expectedEnergy, "FE mismatch expected=" + expectedEnergy + " actual=" + current.energy().stored());
		check(current.ledger().balances().equals(expected), "Product mismatch expected=" + expected + " actual=" + current.ledger().balances());
		previous = work; report.addProperty("sourcePortsRevoked", true);
	}
	private static boolean sameBuffer(CombinedMachineWork a, CombinedMachineWork b) { return a.buffer().items().equals(b.buffer().items()) && a.buffer().fluids().equals(b.buffer().fluids()); }
	private static void add(ProductKey key, ProductAmount amount) { if (!amount.isZero()) expected.merge(key, amount, ProductAmount::add); }
	private static void subtract(ProductKey key, ProductAmount amount) { if (amount.isZero()) return; var remaining = expected.getOrDefault(key, ProductAmount.ZERO).subtract(amount); if (remaining.isZero()) expected.remove(key); else expected.put(key, remaining); }
	private static ServerPlayer player(ServerLevel level) {
		var player = FakePlayerFactory.get(level, new GameProfile(structure.core().ownerId(), "ManagedOwner"));
		new PlayerInventorySyncProbe(player); player.setPos(POS.getCenter()); return player;
	}
	private static void management(ServerLevel level, boolean drain) {
		var player = player(level); var menu = new MachineMenu(90, player.getInventory(), structure.core(), UUID.randomUUID()); player.containerMenu = menu;
		check(menu.stillValid(player), "Managed menu unavailable");
		if (drain) {
			var old = MachineWorkService.access(structure.core()).orElseThrow().work();
			for (int slot : new int[]{3, 2}) {
				player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot).copyWithCount(8));
				check(MachineExchange.exchange(menu, player, MachineExchange.Action.UPGRADE_IN, slot, 0, 8, false).moved() == 8, "Managed native upgrade failed");
			}
			var upgraded = MachineWorkService.access(structure.core()).orElseThrow().work();
			check(upgraded.bees().equals(old.bees()) && upgraded.centrifuges().equals(old.centrifuges()), "Managed upgrade rewrote paid work");
			report.addProperty("managedUpgradePreservesPaidWork", true);
		}
		var before = MachineWorkService.access(structure.core()).orElseThrow();
		for (int slot = 0; slot < (drain ? 6 : 1); slot++) {
			player.getInventory().items.set(0, ItemStack.EMPTY);
			check(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_OUT, slot, 0, 1, true).moved() == 1, "Managed simulation refused");
			check(before.current() == (slot == 0), "Simulation changed assets");
			check(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_OUT, slot, 0, 1, false).moved() == 1, "Managed food return failed");
			if (!drain) check(MachineExchange.exchange(menu, player, MachineExchange.Action.FEED_IN, slot, 0, 1, false).moved() == 1, "Managed feeding failed");
		}
		check(!before.current(), "Old managed root survived exchange"); previous = data.checkpoint().ownedMachines().get(structure.core().machineId()).combined();
	}
	private static CombinedMachineWork decode(CompoundTag tag) { return CombinedWorkCodec.decode(tag, key -> {}, key -> 64, food -> {}); }
	private static void finish(MinecraftServer server) {
		report.addProperty("probabilityProducts", probabilityProducts);
		report.addProperty("multiOutput", Boolean.getBoolean("pbg.multiblock.multi"));
		report.addProperty("assignedJobs", assignedJobs);
		report.addProperty("basicCompletedJobs", basicCompleted);
		report.addProperty("passed", true); report.addProperty("sharedEnergyAndOutputsConserved", true); report.addProperty("noWorldDrops", true);
		report.addProperty("completedBeeCycles", completedBees); report.addProperty("completedJobs", completedJobs); report.addProperty("workFe", workFe); report.addProperty("maintenanceTicks", maintenanceTicks);
		stop(server);
	}
	private static void fail(MinecraftServer server, Exception failure) { clientFailure = failure.toString(); report.addProperty("passed", false); report.addProperty("failure", failure.toString()); com.mojang.logging.LogUtils.getLogger().error("COMBINED_NETWORK_PROBE_FAILED phase={}", phase, failure); stop(server); }
	private static void stop(MinecraftServer server) { phase = 99; report.addProperty("combinedNetwork", true); report.addProperty("mode", reading() ? "read" : "write"); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2")); report.addProperty("elapsedTicks", server.getTickCount() - started); report.addProperty("completedBeeCycles", completedBees); report.addProperty("completedJobs", completedJobs); report.addProperty("expectedEnergy", expectedEnergy); write(); server.halt(false); }
	@SubscribeEvent public static void stopped(ServerStoppedEvent event) {
		if (!enabled()) return;
		try {
			var server = event.getServer(); var file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(MachineWorkService.dataName(structure.core().machineId()) + ".dat");
			var source = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
			check(source.getString("mode").equals(reading() ? "STANDALONE" : "MANAGED"), "Wrong persisted source owner");
			check(reading() || source.getCompound("work").isEmpty(), "Saved a duplicate managed source");
			var networkFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("productivebeesgenesis_network_" + core.network().networkId() + ".dat");
			var saved = NbtIo.readCompressed(networkFile, NbtAccounter.unlimitedHeap()).getCompound("data");
			var restored = new NetworkCheckpointCodec(key -> {}).decode(saved);
			check(reading() ? restored.ownedMachines().activeCount() == 0 : CombinedWorkCodec.encode(restored.ownedMachines().get(structure.core().machineId()).combined()).equals(marker.getCompound("managed")), "Network shutdown did not save ownership");
			report.addProperty("normalAssetCheckpointSaved", true); report.addProperty("normalShutdown", true);
			normalShutdown = true;
		} catch (Exception failure) { report.addProperty("passed", false); report.addProperty("shutdownFailure", failure.toString()); }
		finally { ModConfig.SERVER.apiaryProcessingTime.set(oldCycle); write(); }
	}
	private static void write() { try { Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results/multiblock.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); } catch (Exception failure) { throw new IllegalStateException(failure); } }
}
