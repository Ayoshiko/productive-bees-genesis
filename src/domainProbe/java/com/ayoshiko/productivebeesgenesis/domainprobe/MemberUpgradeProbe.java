package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.BalancePreset;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
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
import net.minecraft.resources.ResourceLocation;
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

/** 真实托管成员、服务器菜单和正常存档；客户端升级操作由 D17c 另行验收。 */
final class MemberUpgradeProbe {
	private static final BlockPos POS = new BlockPos(408, 160, 8);
	private static final ResourceLocation IRON = ResourceLocation.parse("productivebees:iron");
	private static NetworkCoreBlockEntity core;
	private static TileEntityMekCentrifuge target, other, reference;
	private static NetworkSavedData data;
	private static ServerPlayer player;
	private static PlayerInventorySyncProbe sync;
	private static NetworkCoreMenu menu;
	private static NetworkCentrifugeService service;
	private static ProductPolicyRegistry policy;
	private static ProductKey input;
	private static UUID member, otherMember;
	private static NetworkCheckpoint saved, shutdown;
	private static CentrifugeRecipePlan oldPlan;
	private static int phase, started, checks, energyChecks, pbChecks, pbTypes;
	private static final PbUpgradeType[] WORK_PB = {PbUpgradeType.PRODUCTIVITY_3, PbUpgradeType.TIME_2, PbUpgradeType.STABILITY, PbUpgradeType.USELESS_BYPRODUCT};
	private static long maxExchangeNanos, workEnergy, returnCapacity, returnSharedEnergy;
	static void start(MinecraftServer server) {
		started = server.getTickCount(); var level = server.overworld(); ModConfig.SERVER.beeNetwork.enabled.set(true);
		level.setChunkForced(POS.getX() >> 4, POS.getZ() >> 4, true);
		player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "UpgradeOwner")); player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
		sync = new PlayerInventorySyncProbe(player);
		level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState()); core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
		target = machine(POS.east()); other = machine(POS.west()); reference = machine(POS.south(5));
		target.getComponent().addUpgrades(Upgrade.ENERGY, 2);
		target.energyContainer().setEnergy(target.energyContainer().getMaxEnergy());
		var comb = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), IRON); input = ProductKeyCodec.item(comb, level.registryAccess());
	}
	private static TileEntityMekCentrifuge machine(BlockPos pos) {
		var level = player.serverLevel(); level.setBlockAndUpdate(pos, ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		var tile = (TileEntityMekCentrifuge) level.getBlockEntity(pos); tile.setOwnerUUID(player.getUUID()); tile.setControlType(RedstoneControl.HIGH);
		tile.energyContainer().setEnergy(tile.energyContainer().getMaxEnergy()); return tile;
	}
	static boolean advance(MinecraftServer server, JsonObject report) throws Exception {
		if (phase == 5) return true;
		require(server.getTickCount() - started < 900, "Member upgrade probe timeout at " + phase);
		require(core.ownership().status() != CoreOwnershipController.Status.RECOVERY, core.ownership().failure());
		var directory = NetworkPersistence.directory(server); var level = server.overworld();
		if (phase == 0) {
			if (core.topology() == null || !core.topology().valid() || core.ownership().busy()) return false;
			require(core.topology().hasMember(target.getBlockPos()) && core.topology().hasMember(other.getBlockPos()) && !core.topology().hasMember(reference.getBlockPos()), "Reference is not independent");
			require(core.ownership().command(true), "Upgrade fixture takeover failed"); phase = 1; return false;
		}
		if (core.ownership().busy()) return false;
		if (phase == 1) {
			if (core.ownership().status() != CoreOwnershipController.Status.MANAGED) return false;
			data = directory.loadExisting(core.network()).ready();
			for (var record : data.checkpoint().ownedMachines().values()) {
				if (record.claim().origin().x() == target.getBlockPos().getX()) member = record.claim().member(); else otherMember = record.claim().member();
			}
			require(member != null && otherMember != null, "Missing upgrade members");
			ClientTerminalStockFixture.seed(data, Map.of(input, ProductAmount.of(20)));
			policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(level, data.checkpoint().policyRevision()).snapshot());
			service = new NetworkCentrifugeService(data, directory, policy);
			for (UUID id : List.of(member, otherMember)) require(service.activate(level, id, data.checkpoint().revision(), input), "Upgrade fixture activation failed");
			require(data.checkpoint().energy().capacity() == ModConfig.SERVER.beeNetwork.energyCapacity.get(), "Core has not configured shared energy");
			target.setControlType(RedstoneControl.DISABLED); other.setControlType(RedstoneControl.DISABLED); open(71);
			localEnergyExchanges();
			for (UUID id : List.of(member, otherMember)) require(NetworkEnergyService.migrate(level, data, directory, id, data.checkpoint().revision(), false), "Upgrade fixture energy migration failed");
			basicExchanges(); sharedEnergyExchanges(); pbExchanges(); permissions(server);
			core.toggleFace(Direction.EAST); check(INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, UNAVAILABLE, 0, revision());
			core.toggleFace(Direction.EAST); phase = 2; return false;
		}
		if (phase == 2) {
			if (core.topology() == null || !core.topology().valid()) return false;
			if (!CoreUpgradeBatchProbe.advance(data, player, menu, report)) return false;
			if (!CoreMemberProxyProbe.advance(core, data, target, member, otherMember, report)) return false;
			open(75);
			beginWork(); saved = data.checkpoint(); directory.requestSave(data); phase = 3; return false;
		}
		if (phase == 3) {
			if (data.persistedRevision() != saved.revision()) return false;
			var disk = NbtIo.readCompressed(path(server), NbtAccounter.unlimitedHeap());
			require(NetworkCheckpointCodec.forRegistries(level.registryAccess()).decode(disk.getCompound("data")).equals(saved), "Save lost upgrades or frozen old work");
			var frozen = record().centrifuge().jobs().get(0); var balances = new HashMap<>(data.checkpoint().ledger().balances());
			frozen.frozen().forEach((key, amount) -> balances.merge(key, amount, ProductAmount::add));
			require(service.work(level, member, revision(), NetworkCentrifugeService.Action.SETTLE, 0, false), "Upgraded old work could not settle");
			require(data.checkpoint().ledger().balances().equals(balances), "Upgrade changed frozen output delivery");
			var fresh = service.candidate(level, member, input); reference.getComponent().addUpgrades(Upgrade.SPEED, 1);
			for (var type : WORK_PB) require(reference.installPbUpgradeBulk(type, 1) == 1, "Physical PB installation failed");
			comparePhysical(fresh.plan()); comparePbPhysical(fresh.plan());
			require(fresh.plan().maxParallel() > oldPlan.maxParallel() && fresh.plan().stability() > oldPlan.stability()
					&& fresh.plan().outputs().size() < oldPlan.outputs().size(), "Fresh work ignored PB effects");
			require(fresh.plan().cycleTicks() < oldPlan.cycleTicks() && fresh.plan().unitEnergyPerTick() > oldPlan.unitEnergyPerTick(), "New work ignored updated speed");
			var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(fresh), 0, 1, 1).selection();
			check(Upgrade.ENERGY, REMOVE, 3, 1, false, MOVED, 1, revision());
			require(!service.assign(level, selection, 7, false), "Pre-energy-change assignment survived capability change");
			check(REMOVE, 0, 1, false, MOVED, 1, revision());
			energyCapacityMatchesPhysical(1);
			returnCapacity = record().centrifuge().energyCapacity(); returnSharedEnergy = data.checkpoint().energy().stored();
			target.setControlType(RedstoneControl.HIGH); other.setControlType(RedstoneControl.HIGH);
			require(core.ownership().command(false), "Upgraded member could not return"); phase = 4; return false;
		}
		if (core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
		require(target.getComponent().getUpgrades(Upgrade.SPEED) == 2 && other.getComponent().getUpgrades(Upgrade.SPEED) == 0, "Return restored old or cross-member upgrades");
		require(target.getComponent().getUpgrades(Upgrade.ENERGY) == 1 && other.getComponent().getUpgrades(Upgrade.ENERGY) == 0
				&& target.energyContainer().getMaxEnergy() == returnCapacity && target.energyContainer().getEnergy() == 0
				&& data.checkpoint().energy().stored() == returnSharedEnergy, "Return lost capacity or duplicated shared energy");
		for (var type : PbUpgradeType.values()) {
			int expected = Arrays.asList(WORK_PB).contains(type) ? 1 : 0;
			require(target.getPbUpgradeInstalledCount(type) == expected && other.getPbUpgradeInstalledCount(type) == 0, "Return lost or duplicated PB upgrades: " + type);
		}
		require(record().assets().isEmpty() && !MemberBinding.isolated(target), "Returned upgrades still have two owners");
		shutdown = data.checkpoint(); player.containerMenu = player.inventoryMenu;
		report.addProperty("memberUpgradeFiniteExchangeAndPermissions", true); report.addProperty("memberUpgradeOldWorkAndFreshPhysicalCapacity", true);
		report.addProperty("memberUpgradeCheckpointPreservesWork", true); report.addProperty("memberUpgradeReturnCurrentAssets", true);
		report.addProperty("memberUpgradeChecks", checks); report.addProperty("memberUpgradeMaxExchangeNanos", maxExchangeNanos);
		report.addProperty("memberEnergyUpgradeChecks", energyChecks); report.addProperty("memberEnergyUpgradeLocalCapacityAndSharedConservation", true);
		report.addProperty("memberEnergyUpgradeAllCountsMatchPhysical", true); report.addProperty("memberEnergyUpgradeOldWorkAndReturn", true);
		report.addProperty("memberPbUpgradeChecks", pbChecks); report.addProperty("memberPbUpgradeTypes", pbTypes);
		report.addProperty("memberPbUpgradeLimitsConflictsAndConservation", true);
		report.addProperty("memberPbUpgradePhysicalEffectsAndOldWork", true);
		report.addProperty("memberPbUpgradeCheckpointAndReturn", true);
		level.removeBlock(reference.getBlockPos(), false); phase = 5; return true;
	}
	private static void basicExchanges() {
		player.getInventory().clearContent(); slot(0, UpgradeUtils.getStack(Upgrade.SPEED, 12)); slot(1, UpgradeUtils.getStack(Upgrade.SPEED, 63));
		var named = UpgradeUtils.getStack(Upgrade.SPEED, 2); named.set(DataComponents.CUSTOM_NAME, Component.literal("keep-components")); slot(2, named);
		long revision = revision(); check(INSTALL, 0, 2, true, MOVED, 2, revision); check(INSTALL, 0, 2, false, MOVED, 2, revision);
		check(INSTALL, 0, 2, false, STALE, 0, revision);
		check(REMOVE, 1, 2, false, MOVED, 1, revision()); check(REMOVE, 1, 1, false, NO_SPACE, 0, revision());
		check(REMOVE, 2, 1, false, NO_SPACE, 0, revision()); check(INSTALL, 2, 1, false, UNSUPPORTED, 0, revision());
		var savedInventory = player.getInventory().save(new ListTag());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(REMOVE, 0, 1, false, NO_SPACE, 0, revision()); player.getInventory().load(savedInventory);
		check(INSTALL, -1, 1, false, INVALID, 0, revision()); check(INSTALL, 0, 65, false, INVALID, 0, revision());
		check(INSTALL, 0, 64, false, MOVED, Upgrade.SPEED.getMax() - 1, revision()); check(INSTALL, 0, 1, false, LIMIT, 0, revision());
		check(REMOVE, 0, Upgrade.SPEED.getMax() - 2, false, MOVED, Upgrade.SPEED.getMax() - 2, revision());
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag());
		require(menu.exchangeUpgrade(player, member, revision(), Upgrade.MUFFLING, 0, 1, INSTALL, false).status() == UNSUPPORTED, "Unreviewed native upgrade bypassed its admission gate");
		require(before == data.checkpoint() && inventory.equals(player.getInventory().save(new ListTag())), "Unsupported upgrade mutated assets");
		ModConfig.SERVER.beeNetwork.enabled.set(false); check(INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
		check(REMOVE, 0, 1, false, MOVED, 1, revision()); ModConfig.SERVER.beeNetwork.enabled.set(true); check(INSTALL, 0, 1, false, MOVED, 1, revision());
		sync.failNext = true; revision = revision(); check(INSTALL, 0, 1, false, MOVED, 1, revision); check(INSTALL, 0, 1, false, STALE, 0, revision);
		var nested = new java.util.concurrent.atomic.AtomicReference<MemberUpgradeService.Result>();
		sync.onSend = () -> nested.set(menu.exchangeUpgrade(player, member, revision(), Upgrade.SPEED, 0, 1, REMOVE, false));
		check(REMOVE, 0, 1, false, MOVED, 1, revision()); require(nested.get() != null && nested.get().status() == UNAVAILABLE, "Upgrade sync reentered an asset transaction");
	}
	private static void localEnergyExchanges() {
		slot(3, UpgradeUtils.getStack(Upgrade.ENERGY, 16)); slot(4, ItemStack.EMPTY);
		long stored = record().centrifuge().energy();
		require(stored == record().centrifuge().energyCapacity() && !record().centrifuge().networkPowered(), "Need full local energy before migration");
		check(Upgrade.ENERGY, REMOVE, 4, 1, true, ENERGY_CAPACITY, 0, revision());
		check(Upgrade.ENERGY, REMOVE, 4, 1, false, ENERGY_CAPACITY, 0, revision());
		check(Upgrade.ENERGY, INSTALL, 3, 1, true, MOVED, 1, revision());
		check(Upgrade.ENERGY, INSTALL, 3, 1, false, MOVED, 1, revision()); energyCapacityMatchesPhysical(3);
		require(record().centrifuge().energy() == stored && record().centrifuge().energyCapacity() > stored, "Installing energy created FE");
		new MachineAssetStore(target).validate(record().returnImage(), player.serverLevel());
		check(Upgrade.ENERGY, REMOVE, 4, 1, false, MOVED, 1, revision()); energyCapacityMatchesPhysical(2);
		require(record().centrifuge().energy() == record().centrifuge().energyCapacity(), "Exact capacity boundary changed FE");
		new MachineAssetStore(target).validate(record().returnImage(), player.serverLevel());
	}
	private static void sharedEnergyExchanges() {
		slot(3, UpgradeUtils.getStack(Upgrade.ENERGY, 16)); slot(4, ItemStack.EMPTY);
		check(Upgrade.ENERGY, REMOVE, 4, 2, false, MOVED, 2, revision()); energyCapacityMatchesPhysical(0);
		require(record().centrifuge().networkPowered() && record().centrifuge().energy() == 0
				&& data.checkpoint().energy().stored() > record().centrifuge().energyCapacity(), "Shared FE was constrained by a member capacity");
		for (int count = 1; count <= Upgrade.ENERGY.getMax(); count++) {
			check(Upgrade.ENERGY, INSTALL, 3, 1, false, MOVED, 1, revision()); energyCapacityMatchesPhysical(count);
		}
		check(Upgrade.ENERGY, INSTALL, 3, 1, false, LIMIT, 0, revision());
		check(Upgrade.ENERGY, REMOVE, 4, Upgrade.ENERGY.getMax(), false, MOVED, Upgrade.ENERGY.getMax(), revision()); energyCapacityMatchesPhysical(0);
		check(Upgrade.ENERGY, INSTALL, 3, 2, false, MOVED, 2, revision());
		slot(5, UpgradeUtils.getStack(Upgrade.ENERGY, 63)); long oldRevision = revision();
		check(Upgrade.ENERGY, REMOVE, 5, 2, false, MOVED, 1, oldRevision); energyCapacityMatchesPhysical(1);
		check(Upgrade.ENERGY, REMOVE, 5, 1, false, STALE, 0, oldRevision);
		check(Upgrade.ENERGY, REMOVE, 5, 1, false, NO_SPACE, 0, revision());
		var named = UpgradeUtils.getStack(Upgrade.ENERGY, 1); named.set(DataComponents.CUSTOM_NAME, Component.literal("keep-energy-components")); slot(6, named);
		check(Upgrade.ENERGY, INSTALL, 6, 1, false, UNSUPPORTED, 0, revision());
	}
	private static void energyCapacityMatchesPhysical(int installed) {
		var component = reference.getComponent();
		if (component.getUpgrades(Upgrade.ENERGY) < installed) component.addUpgrades(Upgrade.ENERGY, installed - component.getUpgrades(Upgrade.ENERGY));
		for (int count = component.getUpgrades(Upgrade.ENERGY); count > installed; count--) {
			component.getUpgradeOutputSlot().setStack(ItemStack.EMPTY); component.removeUpgrade(Upgrade.ENERGY, false);
			require(component.getUpgrades(Upgrade.ENERGY) == count - 1, "Independent reference could not remove ENERGY");
		}
		long expected = reference.energyContainer().getMaxEnergy();
		require(record().centrifuge().energyCapacity() == expected && record().assets().copy().getLong("energyCapacity") == expected,
				"Sealed capacity differs from independent physical ENERGY upgrades");
	}
	private static void pbExchanges() {
		reference.getComponent().addUpgrades(Upgrade.SPEED, 2);
		for (var type : PbUpgradeType.values()) {
			if (!PbCentrifugeUpgradeCounts.supported(type)) {
				check(type, INSTALL, 10, 1, false, UNSUPPORTED, 0, revision()); continue;
			}
			pbTypes++; int limit = target.getPbUpgradeLimit(type); require(limit > 0 && limit < 64, "Unexpected PB fixture cap");
			var unit = PbUpgradeInventorySlot.getRepresentativeStack(type); slot(10, unit.copyWithCount(64));
			long oldRevision = revision(); check(type, INSTALL, 10, 1, true, MOVED, 1, oldRevision);
			check(type, INSTALL, 10, 1, false, MOVED, 1, oldRevision); check(type, INSTALL, 10, 1, false, STALE, 0, oldRevision);
			require(reference.installPbUpgradeBulk(type, 1) == 1, "Independent PB install rejected");
			comparePbPhysical(service.candidate(player.serverLevel(), member, input).plan());
			check(type, INSTALL, 10, 64, false, limit > 1 ? MOVED : LIMIT, limit - 1, revision());
			check(type, INSTALL, 10, 1, false, LIMIT, 0, revision());
			if (limit > 1) require(reference.installPbUpgradeBulk(type, limit - 1) == limit - 1, "Physical PB cap differs");
			comparePbPhysical(service.candidate(player.serverLevel(), member, input).plan());
			check(type, REMOVE, 10, 64, false, MOVED, limit, revision());
			while (reference.getPbUpgradeInstalledCount(type) > 0) {
				reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
				require(reference.extractPbUpgradeByType(type), "Physical PB removal failed");
			}
			reference.getPbUpgradeOutputSlot().setStack(ItemStack.EMPTY);
		}
		var unit = PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME);
		slot(10, unit.copyWithCount(64)); check(PbUpgradeType.TIME, INSTALL, 10, 2, false, MOVED, 2, revision());
		slot(11, unit.copyWithCount(63)); check(PbUpgradeType.TIME, REMOVE, 11, 2, false, MOVED, 1, revision());
		check(PbUpgradeType.TIME, REMOVE, 11, 1, false, NO_SPACE, 0, revision());
		var named = unit.copy(); named.set(DataComponents.CUSTOM_NAME, Component.literal("preserve-PB-components")); slot(12, named);
		check(PbUpgradeType.TIME, INSTALL, 12, 1, false, UNSUPPORTED, 0, revision());
		check(PbUpgradeType.TIME, REMOVE, 12, 1, false, NO_SPACE, 0, revision());
		var inventory = player.getInventory().save(new ListTag());
		for (int i = 0; i < 36; i++) slot(i, new ItemStack(Items.COBBLESTONE, 64));
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, NO_SPACE, 0, revision()); player.getInventory().load(inventory);
		slot(12, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME_2));
		check(PbUpgradeType.TIME_2, INSTALL, 12, 1, false, CONFLICT, 0, revision());
		ModConfig.SERVER.beeNetwork.enabled.set(false);
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, UNAVAILABLE, 0, revision());
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision()); ModConfig.SERVER.beeNetwork.enabled.set(true);
		var preset = ModConfig.SERVER.balancePreset.get(); int timeLimit = ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.get();
		boolean speedExclusive = ModConfig.SERVER.speedUpgradeTiersExclusive.get();
		try {
			ModConfig.SERVER.balancePreset.set(BalancePreset.CUSTOM); ModConfig.SERVER.speedUpgradeTiersExclusive.set(false);
			ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.set(8); BalanceConfig.refresh(false);
			check(PbUpgradeType.TIME, INSTALL, 10, 6, false, MOVED, 6, revision());
			check(PbUpgradeType.TIME_2, INSTALL, 12, 1, false, MOVED, 1, revision());
			ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.set(4); ModConfig.SERVER.speedUpgradeTiersExclusive.set(true); BalanceConfig.refresh(false);
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, CONFLICT, 0, revision());
			check(PbUpgradeType.TIME_2, REMOVE, 12, 1, false, MOVED, 1, revision());
			check(PbUpgradeType.TIME, INSTALL, 10, 1, false, LIMIT, 0, revision());
			check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision());
			require(PbCentrifugeUpgradeCounts.read(record().assets().copy().getCompound("extra")).get(PbUpgradeType.TIME) == 5, "Old over-limit count was truncated");
			new MachineAssetStore(target).validate(record().returnImage(), player.serverLevel());
			check(PbUpgradeType.TIME, REMOVE, 10, 5, false, MOVED, 5, revision());
		} finally {
			ModConfig.SERVER.balancePreset.set(preset); ModConfig.SERVER.speedUpgradeTiersExclusive.set(speedExclusive);
			ModConfig.SERVER.mekCentrifugePbUpgradeTimeMaxCount.set(timeLimit); BalanceConfig.refresh(false);
		}
		slot(10, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.PRODUCTIVITY).copyWithCount(2));
		slot(12, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.PRODUCTIVITY_2));
		check(PbUpgradeType.PRODUCTIVITY, INSTALL, 10, 1, false, MOVED, 1, revision());
		check(PbUpgradeType.PRODUCTIVITY_2, INSTALL, 12, 1, false, CONFLICT, 0, revision());
		check(PbUpgradeType.PRODUCTIVITY, REMOVE, 10, 1, false, MOVED, 1, revision());
		slot(10, unit.copyWithCount(2)); sync.failNext = true;
		long oldRevision = revision(); check(PbUpgradeType.TIME, INSTALL, 10, 1, false, MOVED, 1, oldRevision);
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, STALE, 0, oldRevision);
		var nested = new java.util.concurrent.atomic.AtomicReference<MemberUpgradeService.Result>();
		sync.onSend = () -> nested.set(menu.exchangePbUpgrade(player, member, revision(), PbUpgradeType.TIME, 10, 1, REMOVE, false));
		check(PbUpgradeType.TIME, REMOVE, 10, 1, false, MOVED, 1, revision());
		require(nested.get() != null && nested.get().status() == UNAVAILABLE, "PB exchange reentered committed synchronization");
		for (int i = 0; i < 2; i++) {
			reference.getComponent().getUpgradeOutputSlot().setStack(ItemStack.EMPTY); reference.getComponent().removeUpgrade(Upgrade.SPEED, false);
		}
		reference.getComponent().getUpgradeOutputSlot().setStack(ItemStack.EMPTY);
	}
	private static void comparePbPhysical(CentrifugeRecipePlan plan) {
		comparePhysical(plan);
		require(plan.maxParallel() == reference.getOperationsPerTick() * reference.productivityParallelModifier()
				&& plan.productivity() == reference.productivityModifier() && plan.stability() == reference.stabilityBonus(), "PB plan differs from physical capacity");
		var recipe = CentrifugeRecipeIndex.get(IRON).value(); var expected = new HashSet<ProductKey>();
		boolean discard = reference.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;
		for (var stack : recipe.getRecipeOutputs().keySet())
			if (!discard || !stack.is(cy.jdkdigital.productivebees.init.ModTags.Common.WAXES))
				expected.add(ProductKeyCodec.item(stack, player.registryAccess()));
		var fluid = recipe.getFluidOutputs();
		if (!fluid.isEmpty() && (!discard || fluid.getFluid() != cy.jdkdigital.productivebees.init.ModFluids.HONEY.get()))
			expected.add(ProductKeyCodec.fluid(fluid, player.registryAccess()));
		require(expected.equals(plan.outputs().stream().map(CentrifugeRecipePlan.Output::key).collect(java.util.stream.Collectors.toSet())), "PB byproduct filter differs from recipe outputs");
	}
	private static void permissions(MinecraftServer server) throws Exception {
		var old = menu; open(72); require(old.exchangeUpgrade(player, member, revision(), Upgrade.SPEED, 0, 1, INSTALL, false).status() == UNAVAILABLE, "Old upgrade menu accepted");
		require(old.exchangePbUpgrade(player, member, revision(), PbUpgradeType.TIME, 10, 1, INSTALL, false).status() == UNAVAILABLE, "Old PB menu accepted");
		player.setPos(POS.getX() + 20, POS.getY(), POS.getZ()); check(INSTALL, 0, 1, false, UNAVAILABLE, 0, revision());
		check(PbUpgradeType.TIME, INSTALL, 10, 1, false, UNAVAILABLE, 0, revision()); player.setPos(POS.getX() + 0.5, POS.getY(), POS.getZ() + 0.5);
		long revision = revision(); var offThread = java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.exchangeUpgrade(player, member, revision, Upgrade.SPEED, 0, 1, INSTALL, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		require(offThread.status() == UNAVAILABLE, "Off-thread upgrade accepted");
		var offThreadPb = java.util.concurrent.CompletableFuture.supplyAsync(() -> menu.exchangePbUpgrade(player, member, revision, PbUpgradeType.TIME, 10, 1, INSTALL, false)).get(5, java.util.concurrent.TimeUnit.SECONDS);
		require(offThreadPb.status() == UNAVAILABLE, "Off-thread PB upgrade accepted");
		var guest = FakePlayerFactory.get(server.overworld(), new GameProfile(UUID.randomUUID(), "UpgradeGuest")); guest.setPos(player.position());
		require(core.changeGuest(player, guest.getUUID(), true) == CoreAccessState.Change.CHANGED, "Guest fixture failed");
		var guestMenu = (NetworkCoreMenu) core.createMenu(73, guest.getInventory(), guest); require(guestMenu != null, "Authorized guest could not open menu"); guest.containerMenu = guestMenu;
		var before = data.checkpoint(); require(guestMenu.exchangeUpgrade(guest, member, revision(), Upgrade.SPEED, 0, 1, REMOVE, false).status() == UNAVAILABLE, "Guest modified upgrades");
		require(guestMenu.exchangePbUpgrade(guest, member, revision(), PbUpgradeType.TIME, 10, 1, REMOVE, false).status() == UNAVAILABLE, "Guest modified PB upgrades");
		require(before == data.checkpoint(), "Rejected guest changed upgrades"); guest.containerMenu = guest.inventoryMenu; open(74);
	}
	private static void beginWork() {
		reference.getComponent().addUpgrades(Upgrade.SPEED, 2); var candidate = service.candidate(player.serverLevel(), member, input); oldPlan = candidate.plan(); comparePhysical(oldPlan);
		var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(candidate), 0, 1, 1).selection(); workEnergy = data.checkpoint().energy().stored();
		require(service.assign(player.serverLevel(), selection, 817, false), "Upgrade work fixture assignment failed");
		require(service.work(player.serverLevel(), member, revision(), NetworkCentrifugeService.Action.ADVANCE, 1, false), "Upgrade work fixture could not advance");
		var job = record().centrifuge().jobs().get(0); require(job.progress() == 1 && !job.paid(), "Need partial paid work");
		check(INSTALL, 0, 1, false, MOVED, 1, revision()); require(record().centrifuge().jobs().get(0) == job, "Upgrade replaced old work");
		check(Upgrade.ENERGY, INSTALL, 3, 1, false, MOVED, 1, revision()); energyCapacityMatchesPhysical(2);
		require(record().centrifuge().jobs().get(0) == job, "Energy upgrade replaced old work");
		for (var type : WORK_PB) {
			slot(10, PbUpgradeInventorySlot.getRepresentativeStack(type)); check(type, INSTALL, 10, 1, false, MOVED, 1, revision());
			require(record().centrifuge().jobs().get(0) == job, "PB upgrade replaced old work");
		}
		require(service.work(player.serverLevel(), member, revision(), NetworkCentrifugeService.Action.ADVANCE, Integer.MAX_VALUE, false), "Old speed work did not finish");
		require(data.checkpoint().energy().stored() == workEnergy - oldPlan.cycleTicks() * oldPlan.energyPerTick(job.operations()), "Old work charged the new speed price");
		require(service.work(player.serverLevel(), member, revision(), NetworkCentrifugeService.Action.FREEZE, 0, false), "Old work could not freeze");
		var frozen = record().centrifuge().jobs().get(0);
		require(frozen.plan() == oldPlan && frozen.frozen().keySet().stream().anyMatch(key -> key.kind() == ProductKey.Kind.FLUID), "PB filter rewrote the old paid output");
	}
	private static void comparePhysical(CentrifugeRecipePlan plan) {
		int base = CentrifugeRecipeIndex.get(IRON).value().getProcessingTime(); if (base <= 0) base = reference.baseTicksRequired();
		require(plan.cycleTicks() == reference.getTicksForBase(base) && plan.unitEnergyPerTick() == reference.energyContainer().getEnergyPerTick(), "Upgrade capacity differs from independent physical machine");
	}
	private static void check(MemberUpgradeService.Action action, int slot, int requested, boolean simulate, MemberUpgradeService.Status status, int moved, long revision) {
		check(Upgrade.SPEED, action, slot, requested, simulate, status, moved, revision);
	}
	private static void check(Upgrade upgrade, MemberUpgradeService.Action action, int slot, int requested, boolean simulate, MemberUpgradeService.Status status, int moved, long revision) {
		check(upgrade, null, action, slot, requested, simulate, status, moved, revision);
	}
	private static void check(PbUpgradeType pb, MemberUpgradeService.Action action, int slot, int requested, boolean simulate, MemberUpgradeService.Status status, int moved, long revision) {
		check(null, pb, action, slot, requested, simulate, status, moved, revision);
	}
	private static void check(Upgrade upgrade, PbUpgradeType pb, MemberUpgradeService.Action action, int slot, int requested, boolean simulate, MemberUpgradeService.Status status, int moved, long revision) {
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag()); var total = totals(); int packets = sync.packets; boolean failure = sync.failNext;
		long start = System.nanoTime(); var result = pb == null ? menu.exchangeUpgrade(player, member, revision, upgrade, slot, requested, action, simulate)
				: menu.exchangePbUpgrade(player, member, revision, pb, slot, requested, action, simulate);
		maxExchangeNanos = Math.max(maxExchangeNanos, System.nanoTime() - start); checks++; if (upgrade == Upgrade.ENERGY) energyChecks++; if (pb != null) pbChecks++;
		require(result.status() == status && result.moved() == moved, "Upgrade result differs: " + action + " got " + result + " expected " + status + "/" + moved);
		var after = data.checkpoint(); require(total.equals(totals()), "Player/member upgrade conservation failed");
		require(before.ledger() == after.ledger() && before.energy() == after.energy() && before.scheduler() == after.scheduler()
				&& before.ownedMachines().get(otherMember) == after.ownedMachines().get(otherMember)
				&& before.ownedMachines().get(member).centrifuge().jobs().equals(record().centrifuge().jobs())
				&& before.ownedMachines().get(member).centrifuge().energy() == record().centrifuge().energy()
				&& before.ownedMachines().get(member).centrifuge().networkPowered() == record().centrifuge().networkPowered(), "Upgrade changed other assets or old work");
		if (simulate || moved == 0) require(before == after && inventory.equals(player.getInventory().save(new ListTag())), "Rejected/simulated upgrade changed assets");
		require(sync.packets - packets == (simulate || moved == 0 ? 0 : 1), "Wrong upgrade synchronization count");
		if (!simulate && moved > 0 && !failure) require(sync.last.getSlot() == slot && ItemStack.matches(sync.last.getItem(), player.getInventory().items.get(slot)), "Upgrade synchronization differs from actual inventory");
		var oldExtra = before.ownedMachines().get(member).assets().copy().getCompound("extra");
		var newExtra = record().assets().copy().getCompound("extra");
		oldExtra.remove(com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS);
		newExtra.remove(com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS);
		require(oldExtra.equals(newExtra), "Upgrade changed PB input/output slots or unrelated metadata");
		require(new MachineAssetStore(target).empty() && new MachineAssetStore(other).empty(), "Upgrade mutated managed physical inventory");
		require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(4)).isEmpty(), "Upgrade created item entities");
	}
	private static Map<ProductKey, Integer> totals() {
		var result = new HashMap<ProductKey, Integer>();
		for (var stack : player.getInventory().items) if (!stack.isEmpty()) result.merge(ProductKeyCodec.item(stack, player.registryAccess()), stack.getCount(), Math::addExact);
		NativeUpgradeCounts.read(record().assets().copy().getCompound("upgrades")).forEach((upgrade, count) ->
				result.merge(ProductKeyCodec.item(UpgradeUtils.getStack(upgrade, 1), player.registryAccess()), count, Math::addExact));
		PbCentrifugeUpgradeCounts.read(record().assets().copy().getCompound("extra")).forEach((upgrade, count) ->
				result.merge(ProductKeyCodec.item(PbUpgradeInventorySlot.getRepresentativeStack(upgrade), player.registryAccess()), count, Math::addExact));
		return result;
	}
	private static OwnedMachineRecord record() { return data.checkpoint().ownedMachines().get(member); }
	private static long revision() { return record().centrifuge().revision(); }
	private static void slot(int slot, ItemStack item) { player.getInventory().items.set(slot, item); }
	private static void open(int id) { menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player); require(menu != null, "Owner upgrade menu denied"); player.containerMenu = menu; }
	private static java.nio.file.Path path(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + data.identity().networkId() + ".dat"); }
	static void verifyShutdown(MinecraftServer server, JsonObject report) throws Exception {
		if (shutdown == null) return;
		var decoded = NetworkCheckpointCodec.forRegistries(server.registryAccess()).decode(NbtIo.readCompressed(path(server), NbtAccounter.unlimitedHeap()).getCompound("data"));
		require(decoded.equals(shutdown), "Shutdown lost member upgrade return receipt"); report.addProperty("memberUpgradeShutdownSaved", true);
		core = null; target = other = reference = null; data = null; player = null; sync = null; menu = null; service = null; policy = null; saved = shutdown = null;
	}
	private MemberUpgradeProbe() { }
}
