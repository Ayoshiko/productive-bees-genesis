package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeRosterChange;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntimeService;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.UUID;

/** 服务端单蜂／单背包槽事务；先准备双方结果，再同时移交所有权。 */
public final class CoreBeeCageExchange {
	/** INSERT 放入蜂箱，EXTRACT 装入蜂笼。 */
	public enum Action { INSERT, EXTRACT }
	/** 已付费结果由原调度器结清后，使用最新版本重试 DRAIN_FIRST。 */
	public enum Status { MOVED, OCCUPIED, EMPTY, STALE, DRAIN_FIRST, UNSUPPORTED_CAGE, UNSUPPORTED_BEE, INVALID, UNAVAILABLE }
	/** interruptedTicks 供界面解释装笼取消的未完成周期；该能量不退回。 */
	public record Result(Status status, int moved, UUID beeId, int interruptedTicks) { }

	static Result exchange(NetworkCoreMenu menu, ServerPlayer player, UUID member, int slot,
			long expectedRevision, UUID expectedBee, int inventorySlot, Action action, boolean simulate) {
		var core = menu.exchangeCore(player);
		if (core == null) return result(Status.UNAVAILABLE);
		if (member == null || action == null || expectedRevision < 0 || slot < 0 || slot >= 3
				|| inventorySlot < 0 || inventorySlot >= 36
				|| (action == Action.EXTRACT) != (expectedBee != null)) return result(Status.INVALID);
		var authority = core.ownership().readyAuthority();
		if (authority == null) return result(Status.UNAVAILABLE);
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.bees() == null) return result(Status.UNAVAILABLE);
		var state = record.bees();
		if (state.revision() != expectedRevision) return result(Status.STALE);
		var level = player.serverLevel(); var directory = NetworkPersistence.directory(level.getServer());
		var hive = ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class);
		if (hive == null) return result(Status.UNAVAILABLE);
		var bee = state.bees().stream().filter(value -> value.slot() == slot).findFirst().orElse(null);
		if (action == Action.INSERT && bee != null) return result(Status.OCCUPIED);
		if (action == Action.EXTRACT) {
			if (bee == null) return result(Status.EMPTY);
			if (!bee.id().equals(expectedBee)) return result(Status.STALE);
			if (!bee.drained()) return result(Status.DRAIN_FIRST);
		} else if (!ModConfig.SERVER.beeNetwork.enabled.get()) return result(Status.UNAVAILABLE);
		var inventory = player.getInventory().items.get(inventorySlot).copy();
		if (!VerifiedCageProjection.supported(inventory)) return result(Status.UNSUPPORTED_CAGE);
		BeeRosterChange change; ItemStack received; NetworkCheckpoint next;
		try {
			if (action == Action.EXTRACT) {
				received = VerifiedCageProjection.fill(inventory, bee);
				change = BeeRosterChange.extract(state, slot, expectedBee);
			} else {
				var contents = VerifiedCageProjection.contents(inventory);
				received = VerifiedCageProjection.afterRelease(inventory);
				try { change = StaticApiaryAdapter.insertCaged(level, hive, record, slot, contents, current.policyRevision()); }
				catch (IllegalArgumentException unsupported) { return result(Status.UNSUPPORTED_BEE); }
			}
			next = current.exchangeBee(change);
		} catch (IllegalArgumentException invalid) { return result(Status.INVALID); }
		catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot prepare bee network cage exchange at {}", core.getBlockPos(), failure);
			return result(Status.INVALID);
		}
		if (menu.exchangeCore(player) != core || authority.checkpoint() != current
				|| !ItemStack.matches(inventory, player.getInventory().items.get(inventorySlot))
				|| ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class) != hive) {
			return result(Status.STALE);
		}
		if (!simulate) {
			// 两次写入之间无能力回调；只在完成所有权交换后同步和唤醒可丢弃索引。
			authority.publish(next);
			player.getInventory().items.set(inventorySlot, received); player.getInventory().setChanged();
			CoreInventorySync.committed(player, inventorySlot, received);
			try {
				core.runtime().beeChanged(member, slot, change.insertion(), level.getServer().overworld().getGameTime());
				NetworkRuntimeService.watch(core, true);
			} catch (RuntimeException failure) {
				com.mojang.logging.LogUtils.getLogger().warn("Committed bee cage exchange awaits runtime rediscovery", failure);
			}
		}
		return new Result(Status.MOVED, 1, change.bee().id(), change.insertion() ? 0 : change.bee().progress());
	}

	static Result result(Status status) { return new Result(status, 0, null, 0); }
	private CoreBeeCageExchange() { }
}
