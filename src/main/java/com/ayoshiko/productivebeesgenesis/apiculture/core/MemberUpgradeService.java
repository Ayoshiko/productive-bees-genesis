package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberUpgradeChange;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbCentrifugeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import java.util.UUID;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 已激活基础离心机的有限升级实物事务；原生与 PB 类型共用接收、权限和提交边界。 */
public final class MemberUpgradeService {
	public enum Action { INSTALL, REMOVE }
	public enum Status { MOVED, NO_SPACE, LIMIT, EMPTY, STALE, UNSUPPORTED, INVALID, UNAVAILABLE, ENERGY_CAPACITY, CONFLICT }
	public record Result(Status status, int moved, int installed, long revision) { }
	static Result result(Status status) { return new Result(status, 0, -1, -1); }
	static Result exchange(NetworkCoreMenu menu, ServerPlayer player, UUID member, long expectedRevision,
			Upgrade upgrade, int inventorySlot, int requested, Action action, boolean simulate) {
		return exchange(menu, player, member, expectedRevision, upgrade, null, inventorySlot, requested, action, simulate);
	}
	static Result exchangePb(NetworkCoreMenu menu, ServerPlayer player, UUID member, long expectedRevision,
			PbUpgradeType upgrade, int inventorySlot, int requested, Action action, boolean simulate) {
		return exchange(menu, player, member, expectedRevision, null, upgrade, inventorySlot, requested, action, simulate);
	}
	private static Result exchange(NetworkCoreMenu menu, ServerPlayer player, UUID member, long expectedRevision,
			Upgrade upgrade, PbUpgradeType pb, int inventorySlot, int requested, Action action, boolean simulate) {
		var core = menu.exchangeCore(player);
		if (core == null || !core.ownerAllowed(player)) return result(Status.UNAVAILABLE);
		if (member == null || action == null || expectedRevision < 0 || inventorySlot < 0 || inventorySlot >= 36
				|| requested < 1 || requested > 64) return result(Status.INVALID);
		if (pb == null ? upgrade != Upgrade.SPEED && upgrade != Upgrade.ENERGY : !PbCentrifugeUpgradeCounts.supported(pb)) return result(Status.UNSUPPORTED);
		if (action == Action.INSTALL && !ModConfig.SERVER.beeNetwork.enabled.get()) return result(Status.UNAVAILABLE);
		var authority = core.ownership().readyAuthority(); if (authority == null) return result(Status.UNAVAILABLE);
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.centrifuge() == null) return result(Status.UNAVAILABLE);
		if (record.centrifuge().revision() != expectedRevision) return result(Status.STALE);
		var level = player.serverLevel(); var directory = NetworkPersistence.directory(player.server);
		var tile = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class);
		if (tile == null) return result(Status.UNAVAILABLE);
		if (pb == null ? !tile.getComponent().supports(upgrade) : !tile.isPbUpgradeSupported(pb)) return result(Status.UNSUPPORTED);
		var inventory = player.getInventory().items.get(inventorySlot).copy();
		MemberUpgradeChange change; ItemStack received; NetworkCheckpoint next; int moved;
		try {
			StaticCentrifugeAdapter.validateUpgrades(tile, record.assets());
			var unit = pb == null ? UpgradeUtils.getStack(upgrade, 1) : PbUpgradeInventorySlot.getRepresentativeStack(pb);
			if (unit.isEmpty()) return result(Status.UNSUPPORTED);
			var pbCounts = pb == null ? null : PbCentrifugeUpgradeCounts.read(record.assets().copy().getCompound("extra"));
			int installed = pb == null ? NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(upgrade, 0) : pbCounts.getOrDefault(pb, 0);
			int limit = pb == null ? upgrade.getMax() : tile.getPbUpgradeLimit(pb);
			if (action == Action.INSTALL) {
				if (inventory.isEmpty()) return result(Status.EMPTY);
				if (!ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.UNSUPPORTED);
				if (pb != null && !BalanceConfig.canInstall(pb, pbCounts)) return result(Status.CONFLICT);
				moved = Math.min(Math.min(requested, inventory.getCount()), limit - installed);
				if (moved <= 0) return result(Status.LIMIT);
				received = inventory.copyWithCount(inventory.getCount() - moved);
			} else {
				if (installed == 0) return result(Status.EMPTY);
				if (!inventory.isEmpty() && !ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.NO_SPACE);
				int space = Math.min(64, unit.getMaxStackSize()) - inventory.getCount();
				moved = Math.min(Math.min(requested, installed), space);
				if (moved <= 0) return result(Status.NO_SPACE);
				received = unit.copyWithCount(inventory.getCount() + moved);
			}
			int delta = action == Action.INSTALL ? moved : -moved;
			if (pb != null) change = MemberUpgradeChange.pb(record, pb, delta, limit);
			else if (upgrade == Upgrade.ENERGY) {
				long capacity = StaticCentrifugeAdapter.energyCapacity(tile, installed + delta);
				if (record.centrifuge().energy() > capacity) return result(Status.ENERGY_CAPACITY);
				change = MemberUpgradeChange.energy(record, delta, capacity);
			} else change = MemberUpgradeChange.speed(record, delta);
			StaticCentrifugeAdapter.validateUpgrades(tile, change.candidate().assets());
			next = current.exchangeUpgrade(change);
		} catch (IllegalArgumentException unsupported) { return result(Status.UNSUPPORTED); }
		catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot prepare member upgrade exchange at {}", core.getBlockPos(), failure);
			return result(Status.INVALID);
		}
		if (menu.exchangeCore(player) != core || !core.ownerAllowed(player) || authority.checkpoint() != current
				|| !ItemStack.matches(inventory, player.getInventory().items.get(inventorySlot))
				|| ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class) != tile) return result(Status.STALE);
		if (!simulate) {
			// 两次发布之间不进入第三方库存回调；已提交后的同步失败不回退或再次发放物品。
			authority.publish(next);
			player.getInventory().items.set(inventorySlot, received); player.getInventory().setChanged();
			CoreInventorySync.committed(player, inventorySlot, received);
		}
		return new Result(Status.MOVED, moved, change.installed(), change.candidate().centrifuge().revision());
	}
	private MemberUpgradeService() { }
}
