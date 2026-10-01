package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.BalancePreset;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.MemberUpgradeService.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.core.MemberUpgradeService.Status.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实蜂箱原生／PB 时间升级、旧周期费用与当前实物交还；客户端升级另行验收。 */
final class ApiaryUpgradeProbe {
	private static final BlockPos POS = new BlockPos(440, 160, 8);
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekApiary hive, other, reference;
	private static NetworkSavedData data;
	private static NetworkBeeService service;
	private static ServerPlayer player;
	private static PlayerInventorySyncProbe sync;
	private static NetworkCoreMenu menu;
	private static UUID member, otherMember;
	private static NetworkCheckpoint saved, shutdown;
	private static StaticBeePlan oldPlan;
	private static long expectedEnergy, returnEnergy, maxNanos, boundaryNanos, continuationNanos;
	private static int phase, started, checks, pbChecks, pbTypes;
	static void start(MinecraftServer server) {
		started = server.getTickCount(); var level = server.overworld(); ModConfig.SERVER.beeNetwork.enabled.set(true);
		level.setChunkForced(POS.getX() >> 4, POS.getZ() >> 4, true);
		player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "ApiaryUpgradeOwner"));
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5); sync = new PlayerInventorySyncProbe(player);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
		core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
		hive = machine(POS.east(), true); other = machine(POS.west(), true); reference = machine(POS.south(5), false);
		require(hive.installPbUpgradeBulk(PbUpgradeType.TIME, 1) == 1, "Seeded PB apiary install failed");
		hive.getComponent().addUpgrades(Upgrade.ENERGY, 2); hive.energyContainer().setEnergy(hive.energyContainer().getMaxEnergy());
	}
	private static TileEntityMekApiary machine(BlockPos pos, boolean withBees) {
		var level = player.serverLevel(); level.setBlockAndUpdate(pos, ModBlocks.MEK_APIARY.get().defaultBlockState());
		var tile = (TileEntityMekApiary) level.getBlockEntity(pos); tile.setOwnerUUID(player.getUUID()); tile.setControlType(RedstoneControl.HIGH);
		tile.setDirectEjectEnabled(false); tile.setDirectAeOutputEnabled(false); tile.setDirectContainerOutputEnabled(false);
		tile.setCentrifugePriorityEnabled(false); tile.setFeederConversionEnabled(false);
		if (withBees) for (int i = 0; i < 2; i++) {
			var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee"); bee.putString("type", "productivebees:iron"); bee.putUUID("UUID", UUID.randomUUID());
			var genes = new CompoundTag(); genes.putString("bee_behavior", "behavior.metaturnal"); genes.putString("bee_weather_tolerance", "weather_tolerance.any");
			genes.putString("bee_productivity", i == 0 ? "productivity.normal" : "productivity.very_high");
			var attachments = new CompoundTag(); attachments.put("productivebees:attributes_handler", genes); bee.put("neoforge:attachments", attachments);
			tile.getBeeSlot(i).setBeeData(bee); tile.getBeeSlot(i).setBaseMinOccupationTicks(20);
			tile.getFeederSlots().get(i).setStack(new ItemStack(Items.IRON_BLOCK));
		}
		tile.energyContainer().setEnergy(tile.energyContainer().getMaxEnergy()); return tile;
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (phase == 5) return true;
		require(server.getTickCount() - started < 1000, "Apiary upgrade timeout at " + phase);
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var level = server.overworld(); var directory = NetworkPersistence.directory(server);
		if (phase == 0) {
			if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return false;
			require(!core.topology().hasMember(reference.getBlockPos()), "Reference hive joined target network");
			require(core.ownership().command(true), "Apiary upgrade takeover failed"); phase = 1; return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1) {
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			data = directory.loadExisting(core.network()).ready(); service = new NetworkBeeService(data, directory);
			for (var record : data.checkpoint().ownedMachines().activeValues()) {
				if (record.claim().origin().x() == hive.getBlockPos().getX()) member = record.claim().member(); else otherMember = record.claim().member();
			}
			for (var id : List.of(member, otherMember)) require(service.activate(level, id, data.checkpoint().revision(), 0), "Upgraded apiary activation failed");
			var feeding = state().feeding();
			require(feeding.slots().get(0).count() == 2 && feeding.slots().get(1).count() == 0, "Expected merged real flower samples");
			hive.setControlType(RedstoneControl.DISABLED);
			var beforeSharing = data.checkpoint();
			require(work(1, 1, 1, false) == BeeWorkExecutor.Status.FLOWER && beforeSharing == data.checkpoint(), "Missing flower must reject without publishing timing");
			var feedingService = new com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkFeedingService(data, directory);
			require(feedingService.apply(level, member, feeding.revision(), feeding.groups(List.of(0, 0, 2)), false), "Explicit shared flower group rejected");
			hive.setControlType(RedstoneControl.HIGH);
			open(81);
			setReference(Upgrade.ENERGY, 2); setReference(PbUpgradeType.TIME, 1);
			comparePhysical(new BeeWorkExecutor.Timing(state().bee(0).plan().cycleTicks(), state().bee(0).plan().energyPerTick()));
			slot(10, ItemStack.EMPTY); check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME, 0);
			localCapacityChecks();
			for (var id : List.of(member, otherMember)) require(NetworkEnergyService.migrate(level, data, directory, id, data.checkpoint().revision(), false), "Apiary FE migration failed");
			exchanges(); pbExchanges(); permissions(server);
			core.toggleFace(Direction.EAST); check(Upgrade.SPEED, INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, UNAVAILABLE, 0, revision());
			core.toggleFace(Direction.EAST); phase = 2; return false;
		}
		if (phase == 2) {
			if (core.topology() == null || !core.topology().valid()) return false;
			hive.setControlType(RedstoneControl.DISABLED); pbTimingBoundary(); beginOldCycle();
			saved = data.checkpoint(); directory.requestSave(data); phase = 3; return false;
		}
		if (phase == 3) {
			if (data.persistedRevision() != saved.revision()) return false;
			require(NetworkCheckpointCodec.forRegistries(level.registryAccess()).decode(NbtIo.readCompressed(path(server), NbtAccounter.unlimitedHeap()).getCompound("data")).equals(saved), "Apiary save changed old cycle");
			var pending = state().bee(0);
			require(pending.plan() == oldPlan && pending.pendingCycles() == 1 && pending.frozen().isZero(), "Old paid cycle was replaced");
			require(work(0, 0, 1, false) == BeeWorkExecutor.Status.READY, "Old paid cycle could not freeze");
			require(state().bee(0).frozen().equals(ProductAmount.of(oldPlan.countPerCycle())), "Old paid output changed");
			require(service.settle(level, member, 0, state().bee(0).revision()), "Old output could not settle");
			setReference(Upgrade.SPEED, 3); setReference(Upgrade.ENERGY, 2); setReference(PbUpgradeType.TIME, 0); setReference(PbUpgradeType.TIME_2, 2);
			var desired = StaticApiaryAdapter.timing(hive, record(), state().bee(0)); comparePhysical(desired);
			var before = data.checkpoint(); require(work(0, 1, 1, true) == BeeWorkExecutor.Status.READY && data.checkpoint() == before, "Simulated next cycle published timing");
			require(work(0, 1, 1, false) == BeeWorkExecutor.Status.READY, "Next upgraded cycle did not run");
			require(desired.matches(state().bee(0).plan()) && state().bee(0).plan().capabilityRevision() > oldPlan.capabilityRevision(), "Next cycle retained old timing");
			expectedEnergy -= desired.energyPerTick(); require(data.checkpoint().energy().stored() == expectedEnergy, "Next cycle used old price");
			var otherBeePlan = state().bee(1).plan();
			require(work(1, desired.cycleTicks(), 1, false) == BeeWorkExecutor.Status.READY, "Second bee did not use new cycle");
			require(state().bee(1).plan().productivity() == 3 && state().bee(1).frozen().equals(ProductAmount.of(4 * otherBeePlan.count())), "Upgrade changed bee genes");
			expectedEnergy -= (long) desired.cycleTicks() * desired.energyPerTick();
			require(service.settle(level, member, 1, state().bee(1).revision()), "Second bee output could not settle");
			require(data.checkpoint().energy().stored() == expectedEnergy, "Apiary cycles lost shared FE");
			require(new BlockEntityOwnershipEndpoint(hive).readyToReturn(record()), "Current partial cycle should remain returnable");
			returnEnergy = data.checkpoint().energy().stored(); hive.setControlType(RedstoneControl.HIGH);
			require(core.ownership().command(false), "Apiary assets could not return"); phase = 4; return false;
		}
		if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
		require(hive.getComponent().getUpgrades(Upgrade.SPEED) == 3 && hive.getComponent().getUpgrades(Upgrade.ENERGY) == 2
				&& other.getComponent().getUpgrades(Upgrade.SPEED) == 0 && other.getComponent().getUpgrades(Upgrade.ENERGY) == 0, "Return lost or shared upgrades");
		require(hive.getPbUpgradeCount(PbUpgradeType.TIME) == 0 && hive.getPbUpgradeCount(PbUpgradeType.TIME_2) == 2
				&& other.getPbUpgradeCount(PbUpgradeType.TIME) == 0 && other.getPbUpgradeCount(PbUpgradeType.TIME_2) == 0, "Return lost or shared PB upgrades");
		require(hive.getBeeSlot(0).getBeeData() != null && hive.getBeeSlot(1).getBeeData() != null && hive.getBeeSlot(0).getTicksInHive() == 1, "Return lost bees or progress");
		require(hive.energyContainer().getEnergy() == 0 && data.checkpoint().energy().stored() == returnEnergy && record().assets().isEmpty(), "Return duplicated assets");
		shutdown = data.checkpoint(); player.containerMenu = player.inventoryMenu;
		report.addProperty("apiaryBoundaryMaxNanos", boundaryNanos); report.addProperty("apiaryContinuationMaxNanos", continuationNanos);
		report.addProperty("apiaryUpgradeChecks", checks); report.addProperty("apiaryUpgradeMaxExchangeNanos", maxNanos);
		report.addProperty("apiaryUpgradeFinitePermissionsAndConservation", true); report.addProperty("apiaryUpgradeAllCountsAndCapacityMatchPhysical", true);
		report.addProperty("apiaryUpgradeOldCycleAndAtomicTiming", true); report.addProperty("apiaryUpgradeSavedWorkAndCurrentReturn", true);
		report.addProperty("apiaryPbUpgradeTypes", pbTypes); report.addProperty("apiaryPbUpgradeChecks", pbChecks);
		report.addProperty("apiaryPbUpgradeLimitsConflictsAndConservation", true);
		report.addProperty("apiaryPbUpgradePhysicalTimingAndOldCycle", true);
		report.addProperty("apiaryPbUpgradeCheckpointAndReturn", true);
		level.removeBlock(reference.getBlockPos(), false); phase = 5; return true;
	}
	private static void localCapacityChecks() {
		slot(0, UpgradeUtils.getStack(Upgrade.SPEED, 32)); slot(1, UpgradeUtils.getStack(Upgrade.ENERGY, 32)); slot(2, ItemStack.EMPTY);
		long stored = state().energy();
		check(Upgrade.ENERGY, REMOVE, 2, 1, true, ENERGY_CAPACITY, 0, revision());
		check(Upgrade.ENERGY, REMOVE, 2, 1, false, ENERGY_CAPACITY, 0, revision());
		check(Upgrade.ENERGY, INSTALL, 1, 1, false, MOVED, 1, revision()); compareCapacity(3);
		require(state().energy() == stored, "Expanding apiary capacity created FE");
		check(Upgrade.ENERGY, REMOVE, 2, 1, false, MOVED, 1, revision()); compareCapacity(2);
		require(state().energy() == state().energyCapacity(), "Exact capacity boundary changed FE");
		new MachineAssetStore(hive).validate(record().returnImage(), player.serverLevel());
	}
	private static void exchanges() {
		check(Upgrade.ENERGY, REMOVE, 1, 2, false, MOVED, 2, revision()); compareCapacity(0);
		for (var upgrade : List.of(Upgrade.SPEED, Upgrade.ENERGY)) {
			int slot = upgrade == Upgrade.SPEED ? 0 : 1;
			for (int count = 1; count <= upgrade.getMax(); count++) {
				check(upgrade, INSTALL, slot, 1, false, MOVED, 1, revision()); setReference(upgrade, count);
				comparePhysical(StaticApiaryAdapter.timing(hive, record(), state().bee(0)));
				if (upgrade == Upgrade.ENERGY) compareCapacity(count);
			}
			check(upgrade, INSTALL, slot, 1, false, LIMIT, 0, revision());
			check(upgrade, REMOVE, slot, upgrade.getMax(), false, MOVED, upgrade.getMax(), revision()); setReference(upgrade, 0);
		}
		check(Upgrade.SPEED, INSTALL, 0, 2, false, MOVED, 2, revision());
		check(Upgrade.ENERGY, INSTALL, 1, 1, false, MOVED, 1, revision());
		slot(2, UpgradeUtils.getStack(Upgrade.SPEED, 63)); check(Upgrade.SPEED, REMOVE, 2, 2, false, MOVED, 1, revision());
		check(Upgrade.SPEED, REMOVE, 2, 1, false, NO_SPACE, 0, revision());
		var named = UpgradeUtils.getStack(Upgrade.SPEED, 1); named.set(DataComponents.CUSTOM_NAME, Component.literal("apiary-component")); slot(3, named);
		check(Upgrade.SPEED, INSTALL, 3, 1, false, UNSUPPORTED, 0, revision()); check(Upgrade.SPEED, REMOVE, 3, 1, false, NO_SPACE, 0, revision());
		var inventory = player.getInventory().save(new ListTag());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(Upgrade.SPEED, REMOVE, 0, 1, false, NO_SPACE, 0, revision()); player.getInventory().load(inventory);
		ModConfig.SERVER.beeNetwork.enabled.set(false); check(Upgrade.SPEED, INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
		check(Upgrade.SPEED, REMOVE, 0, 1, false, MOVED, 1, revision()); ModConfig.SERVER.beeNetwork.enabled.set(true);
		check(Upgrade.SPEED, INSTALL, 0, 2, true, MOVED, 2, revision()); long old = revision();
		check(Upgrade.SPEED, INSTALL, 0, 2, false, MOVED, 2, old); check(Upgrade.SPEED, INSTALL, 0, 1, false, STALE, 0, old);
		sync.failNext = true; old = revision(); check(Upgrade.SPEED, INSTALL, 0, 1, false, MOVED, 1, old);
		check(Upgrade.SPEED, INSTALL, 0, 1, false, STALE, 0, old);
		var nested = new java.util.concurrent.atomic.AtomicReference<MemberUpgradeService.Result>();
		sync.onSend = () -> nested.set(menu.exchangeUpgrade(player, member, revision(), Upgrade.SPEED, 0, 1, REMOVE, false));
		check(Upgrade.SPEED, REMOVE, 0, 1, false, MOVED, 1, revision()); require(nested.get().status() == UNAVAILABLE, "Apiary sync reentered exchange");
		var before = data.checkpoint();
		require(menu.exchangePbUpgrade(player, member, revision(), PbUpgradeType.PRODUCTIVITY, 0, 1, INSTALL, false).status() == UNSUPPORTED, "Unreviewed PB apiary effect accepted");
		require(before == data.checkpoint(), "Rejected PB changed assets");
	}
	private static void pbExchanges() {
		setReference(Upgrade.SPEED, 2); setReference(Upgrade.ENERGY, 1);
		for (var type : PbUpgradeType.values()) {
			if (type != PbUpgradeType.TIME && type != PbUpgradeType.TIME_2) {
				check(type, INSTALL, 10, 1, false, UNSUPPORTED, 0, revision()); continue;
			}
			pbTypes++; int limit = hive.getPbUpgradeLimit(type); require(limit > 0 && limit < 64, "Unexpected apiary PB cap");
			slot(10, PbUpgradeInventorySlot.getRepresentativeStack(type).copyWithCount(64));
			long old = revision(); check(type, INSTALL, 10, 1, true, MOVED, 1, old);
			for (int count = 1; count <= limit; count++) {
				check(type, INSTALL, 10, 1, false, MOVED, 1, revision()); setReference(type, count);
				comparePhysical(StaticApiaryAdapter.timing(hive, record(), state().bee(0)));
			}
			check(type, INSTALL, 10, 1, false, STALE, 0, old); check(type, INSTALL, 10, 1, false, LIMIT, 0, revision());
			check(type, REMOVE, 10, limit, true, MOVED, limit, revision());
			check(type, REMOVE, 10, limit, false, MOVED, limit, revision()); setReference(type, 0);
		}
		var unit = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME);
		slot(10, unit.copyWithCount(64)); slot(11, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME_2).copyWithCount(64));
		check(PbUpgradeType.TIME, INSTALL, 10, 2, false, MOVED, 2, revision());
		slot(12, unit.copyWithCount(63)); check(PbUpgradeType.TIME, REMOVE, 12, 2, false, MOVED, 1, revision());
		check(PbUpgradeType.TIME, REMOVE, 12, 1, false, NO_SPACE, 0, revision());
		var named = unit.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("apiary-PB-components")); slot(13, named);
		check(PbUpgradeType.TIME, INSTALL, 13, 1, false, UNSUPPORTED, 0, revision());
		check(PbUpgradeType.TIME, REMOVE, 13, 1, false, NO_SPACE, 0, revision());
		var inventory = player.getInventory().save(new ListTag());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(PbUpgradeType.TIME, REMOVE, 0, 1, false, NO_SPACE, 0, revision()); player.getInventory().load(inventory);
		check(PbUpgradeType.TIME_2, INSTALL, 11, 1, false, CONFLICT, 0, revision());
		ModConfig.SERVER.beeNetwork.enabled.set(false);
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, UNAVAILABLE, 0, revision());
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision()); ModConfig.SERVER.beeNetwork.enabled.set(true);
		var preset = ModConfig.SERVER.balancePreset.get(); int limit = ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount.get();
		boolean exclusive = ModConfig.SERVER.speedUpgradeTiersExclusive.get();
		try {
			ModConfig.SERVER.balancePreset.set(BalancePreset.CUSTOM); ModConfig.SERVER.speedUpgradeTiersExclusive.set(false);
			ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount.set(8); BalanceConfig.refresh(false);
			check(PbUpgradeType.TIME, INSTALL, 10, 6, false, MOVED, 6, revision()); setReference(PbUpgradeType.TIME, 6);
			check(PbUpgradeType.TIME_2, INSTALL, 11, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME_2, 1);
			ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount.set(4); ModConfig.SERVER.speedUpgradeTiersExclusive.set(true); BalanceConfig.refresh(false);
			comparePhysical(StaticApiaryAdapter.timing(hive, record(), state().bee(0)));
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, CONFLICT, 0, revision());
			check(PbUpgradeType.TIME_2, REMOVE, 11, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME_2, 0);
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, LIMIT, 0, revision());
			check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME, 5);
			require(PbApiaryUpgradeCounts.read(record().assets().copy().getCompound("extra")).get(PbUpgradeType.TIME) == 5, "Legacy apiary count truncated");
			new MachineAssetStore(hive).validate(record().returnImage(), player.serverLevel());
			check(PbUpgradeType.TIME, REMOVE, 10, 5, false, MOVED, 5, revision()); setReference(PbUpgradeType.TIME, 0);
		} finally {
			ModConfig.SERVER.balancePreset.set(preset); ModConfig.SERVER.speedUpgradeTiersExclusive.set(exclusive);
			ModConfig.SERVER.apiaryPbUpgradeTimeMaxCount.set(limit); BalanceConfig.refresh(false);
		}
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME, 1);
	}
	private static void pbTimingBoundary() {
		long before = data.checkpoint().energy().stored();
		require(work(0, 1, 1, false) == BeeWorkExecutor.Status.READY, "PB initial cycle failed");
		var old = state().bee(0).plan();
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME, 2);
		var timing = StaticApiaryAdapter.timing(hive, record(), state().bee(0)); comparePhysical(timing);
		require(timing.cycleTicks() < old.cycleTicks() && timing.energyPerTick() == old.energyPerTick(), "PB time changed FE/t or did not change duration");
		require(!new BlockEntityOwnershipEndpoint(hive).readyToReturn(record()), "PB-only partial cycle returned too early");
		require(work(0, 999, 1, false) == BeeWorkExecutor.Status.READY && state().bee(0).progress() == 0
				&& state().bee(0).plan() == old && state().bee(0).frozen().equals(ProductAmount.of(old.countPerCycle())), "PB edit changed old cycle/output");
		require(service.settle(player.serverLevel(), member, 0, state().bee(0).revision()), "PB old output did not settle");
		var unchanged = data.checkpoint();
		require(work(0, 1, 1, true) == BeeWorkExecutor.Status.READY && data.checkpoint() == unchanged, "Simulated PB timing published");
		require(work(0, timing.cycleTicks(), 1, false) == BeeWorkExecutor.Status.READY
				&& timing.matches(state().bee(0).plan()) && state().bee(0).frozen().equals(ProductAmount.of(old.countPerCycle())), "PB next cycle changed output");
		require(data.checkpoint().energy().stored() == before - (long) (old.cycleTicks() + timing.cycleTicks()) * old.energyPerTick(), "PB cycles charged incorrectly");
		require(service.settle(player.serverLevel(), member, 0, state().bee(0).revision()), "PB next output did not settle");
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision()); setReference(PbUpgradeType.TIME, 1);
	}
	private static void beginOldCycle() {
		setReference(Upgrade.SPEED, 2); setReference(Upgrade.ENERGY, 1);
		long energyBeforeWork = data.checkpoint().energy().stored(); var before = data.checkpoint();
		require(work(0, 1, 1, true) == BeeWorkExecutor.Status.READY && data.checkpoint() == before, "Simulated timing changed authority");
		require(work(0, 1, 1, false) == BeeWorkExecutor.Status.READY, "First upgraded apiary cycle failed");
		oldPlan = state().bee(0).plan(); comparePhysical(new BeeWorkExecutor.Timing(oldPlan.cycleTicks(), oldPlan.energyPerTick()));
		var bee = state().bee(0); var second = state().bee(1);
		check(Upgrade.SPEED, INSTALL, 0, 1, false, MOVED, 1, revision()); check(Upgrade.ENERGY, INSTALL, 1, 1, false, MOVED, 1, revision());
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision());
		check(PbUpgradeType.TIME_2, INSTALL, 11, 2, false, MOVED, 2, revision());
		require(state().bee(0) == bee && state().bee(1) == second, "Upgrade changed old bee plans");
		require(!new BlockEntityOwnershipEndpoint(hive).readyToReturn(record()) && !core.ownership().command(false)
				&& core.ownership().status() == CoreOwnershipController.Status.REJECTED, "Old partial cycle escaped to physical production");
		require(work(0, 999, 0, false) == BeeWorkExecutor.Status.READY, "Old cycle could not finish");
		expectedEnergy = energyBeforeWork - (long) oldPlan.cycleTicks() * oldPlan.energyPerTick();
		require(state().bee(0).progress() == 0 && state().bee(0).pendingCycles() == 1 && data.checkpoint().energy().stored() == expectedEnergy, "Old cycle crossed boundary or changed price");
	}
	private static void permissions(MinecraftServer server) throws Exception {
		var old = menu; open(82); require(old.exchangeUpgrade(player, member, revision(), Upgrade.SPEED, 0, 1, INSTALL, false).status() == UNAVAILABLE, "Old apiary menu accepted");
		player.setPos(POS.getX() + 20, POS.getY(), POS.getZ()); check(Upgrade.SPEED, INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
		player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
		long rev = revision(); var offThread = java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.exchangeUpgrade(player, member, rev, Upgrade.SPEED, 0, 1, INSTALL, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		require(offThread.status() == UNAVAILABLE, "Off-thread apiary upgrade accepted");
		require(java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.exchangePbUpgrade(player, member, rev, PbUpgradeType.TIME, 10, 1, INSTALL, false))
				.get(5, java.util.concurrent.TimeUnit.SECONDS).status() == UNAVAILABLE, "Off-thread PB apiary upgrade accepted");
		var guest = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "ApiaryUpgradeGuest")); guest.setPos(player.position());
		require(core.changeGuest(player, guest.getUUID(), true) == CoreAccessState.Change.CHANGED, "Apiary guest grant failed");
		var guestMenu = (NetworkCoreMenu) core.createMenu(83, guest.getInventory(), guest); guest.containerMenu = guestMenu;
		require(guestMenu.exchangeUpgrade(guest, member, revision(), Upgrade.SPEED, 0, 1, REMOVE, false).status() == UNAVAILABLE, "Guest changed apiary upgrades");
		require(guestMenu.exchangePbUpgrade(guest, member, revision(), PbUpgradeType.TIME, 10, 1, REMOVE, false).status() == UNAVAILABLE, "Guest changed apiary PB upgrades");
		guest.containerMenu = guest.inventoryMenu; open(84);
	}
	private static void setReference(Upgrade upgrade, int count) {
		var component = reference.getComponent();
		if (component.getUpgrades(upgrade) < count) component.addUpgrades(upgrade, count - component.getUpgrades(upgrade));
		while (component.getUpgrades(upgrade) > count) {
			component.getUpgradeOutputSlot().setStack(ItemStack.EMPTY); component.removeUpgrade(upgrade, false);
		}
		component.getUpgradeOutputSlot().setStack(ItemStack.EMPTY);
	}
	private static void setReference(PbUpgradeType type, int count) {
		int old = reference.getPbUpgradeCount(type);
		if (old < count) require(reference.installPbUpgradeBulk(type, count - old) == count - old, "Physical apiary PB install failed");
		while (reference.getPbUpgradeCount(type) > count) {
			reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
			require(reference.extractPbUpgradeByType(type), "Physical apiary PB removal failed");
		}
		reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
	}
	private static void compareCapacity(int count) {
		setReference(Upgrade.ENERGY, count); require(state().energyCapacity() == reference.energyContainer().getMaxEnergy(), "Apiary capacity differs from physical upgrades");
	}
	private static void comparePhysical(BeeWorkExecutor.Timing timing) {
		int ticks = BeeProgressPlan.cycleTicks(20, ModConfig.SERVER.apiaryProcessingTime.get(), reference.getApiaryUpgradeHandler().getTimeMultiplier(), false);
		require(timing.cycleTicks() == ticks && timing.energyPerTick() == reference.energyContainer().getEnergyPerTick(), "Apiary timing differs from physical upgrades");
	}
	private static void check(Upgrade upgrade, MemberUpgradeService.Action action, int slot, int requested, boolean simulate,
			MemberUpgradeService.Status expected, int moved, long revision) {
		check(upgrade, null, action, slot, requested, simulate, expected, moved, revision);
	}
	private static void check(PbUpgradeType upgrade, MemberUpgradeService.Action action, int slot, int requested, boolean simulate,
			MemberUpgradeService.Status expected, int moved, long revision) {
		pbChecks++; check(null, upgrade, action, slot, requested, simulate, expected, moved, revision);
	}
	private static void check(Upgrade upgrade, PbUpgradeType pb, MemberUpgradeService.Action action, int slot, int requested, boolean simulate,
			MemberUpgradeService.Status expected, int moved, long revision) {
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag()); var total = totals();
		int packets = sync.packets; long start = System.nanoTime();
		var result = pb == null ? menu.exchangeUpgrade(player, member, revision, upgrade, slot, requested, action, simulate)
				: menu.exchangePbUpgrade(player, member, revision, pb, slot, requested, action, simulate); maxNanos = Math.max(maxNanos, System.nanoTime() - start); checks++;
		require(result.status() == expected && result.moved() == moved, "Apiary exchange differs: " + (pb == null ? upgrade : pb) + "/" + action + " " + result + " expected " + expected);
		require(total.equals(totals()), "Apiary upgrade/player quantities not conserved");
		var after = data.checkpoint(); var old = before.ownedMachines().get(member).bees();
		require(before.ledger() == after.ledger() && before.energy() == after.energy() && before.ownedMachines().get(otherMember) == after.ownedMachines().get(otherMember)
				&& old.bees().equals(state().bees()) && old.feeding() == state().feeding() && old.rosterVersion() == state().rosterVersion() && old.energy() == state().energy(), "Apiary exchange changed unrelated assets");
		if (simulate || moved == 0) require(before == after && inventory.equals(player.getInventory().save(new ListTag())), "Rejected apiary request changed state");
		require(sync.packets - packets == (simulate || moved == 0 ? 0 : 1), "Apiary sync count wrong");
		require(new MachineAssetStore(hive).empty() && new MachineAssetStore(other).empty(), "Apiary physical assets reappeared");
		require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(4)).isEmpty(), "Apiary exchange created drops");
	}
	private static Map<ProductKey, Integer> totals() {
		var total = new HashMap<ProductKey, Integer>();
		for (var stack : player.getInventory().items) if (!stack.isEmpty()) total.merge(ProductKeyCodec.item(stack, player.registryAccess()), stack.getCount(), Math::addExact);
		NativeUpgradeCounts.read(record().assets().copy().getCompound("upgrades")).forEach((upgrade, count) ->
				total.merge(ProductKeyCodec.item(UpgradeUtils.getStack(upgrade, 1), player.registryAccess()), count, Math::addExact));
		PbApiaryUpgradeCounts.read(record().assets().copy().getCompound("extra")).forEach((upgrade, count) ->
				total.merge(ProductKeyCodec.item(PbUpgradeInventorySlot.getRepresentativeStack(upgrade), player.registryAccess()), count, Math::addExact)); return total;
	}
	private static BeeWorkExecutor.Status work(int slot, int ticks, int budget, boolean simulate) {
		var bee = state().bee(slot); long start = System.nanoTime();
		var status = service.advance(player.serverLevel(), member, slot, bee.revision(), bee.plan().recipeRevision(), bee.plan().capabilityRevision(), ticks, budget, simulate);
		long elapsed = System.nanoTime() - start;
		if (ticks > 0 && !simulate) {
			if (bee.progress() == 0) boundaryNanos = Math.max(boundaryNanos, elapsed);
			else continuationNanos = Math.max(continuationNanos, elapsed);
		}
		return status;
	}
	private static OwnedMachineRecord record() { return data.checkpoint().ownedMachines().get(member); }
	private static BeeMemberState state() { return record().bees(); }
	private static long revision() { return state().revision(); }
	private static void slot(int slot, ItemStack stack) { player.getInventory().items.set(slot, stack); }
	private static void open(int id) { menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player); player.containerMenu = menu; }
	private static java.nio.file.Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + data.identity().networkId() + ".dat"); }
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		if (shutdown == null) return;
		require(NetworkCheckpointCodec.forRegistries(server.registryAccess()).decode(NbtIo.readCompressed(path(server), NbtAccounter.unlimitedHeap()).getCompound("data")).equals(shutdown), "Shutdown lost apiary upgrade return");
		report.addProperty("apiaryUpgradeShutdownSaved", true);
		core = null; hive = other = reference = null; data = null; service = null; player = null; sync = null; menu = null; saved = shutdown = null;
	}
	private ApiaryUpgradeProbe() { }
}
