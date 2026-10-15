package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedBeeAccess;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 核心菜单的单格实物交换；不调用外部库存能力、自动投掷或无限产物账本。 */
public final class CoreFeedingExchange {
	public enum Action { DEPOSIT, WITHDRAW }
	public enum Status { MOVED, NO_SPACE, INVALID, STALE, UNAVAILABLE }
	public record Result(Status status, int moved) { }
	private record Prepared(ManagedBeeAccess.Change feeding, ItemStack inventory) { }
	static Result exchange(NetworkCoreMenu menu, ServerPlayer player, UUID member, int feedingSlot,
			long expectedRevision, int inventorySlot, int requested, Action action, boolean simulate) {
		var core = menu.exchangeCore(player);
		if (core == null) return result(Status.UNAVAILABLE);
		if (member == null || action == null || expectedRevision < 0 || feedingSlot < 0
				|| inventorySlot < 0 || inventorySlot >= 36 || requested < 1 || requested > 64) return result(Status.INVALID);
		var authority = core.ownership().readyAuthority();
		if (authority == null) return result(Status.UNAVAILABLE);
		var current = authority.checkpoint(); var record = current.ownedMachines().get(member);
		if (record == null || record.feedingSlots().isEmpty()) return result(Status.UNAVAILABLE);
		if (feedingSlot >= record.workLayout().beeSlots()) return result(Status.INVALID);
		if (record.feedingRevision() != expectedRevision) return result(Status.STALE);
		var feeding = ManagedBeeAccess.capture(player.serverLevel(), authority, record);
		if (feeding == null) return result(Status.UNAVAILABLE);
		var inventory = player.getInventory().items.get(inventorySlot).copy();
		Prepared prepared; NetworkCheckpoint next;
		try {
			prepared = prepare(feeding, feedingSlot, inventory, requested, action, player);
			if (prepared == null || prepared.feeding().moved() == 0) return result(Status.NO_SPACE);
			next = prepared.feeding().next();
		} catch (IllegalArgumentException invalid) { return result(Status.INVALID); }
		catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot prepare bee network feeding exchange at {}", core.getBlockPos(), failure);
			return result(Status.INVALID);
		}
		if (menu.exchangeCore(player) != core || authority.checkpoint() != current || !feeding.current()
				|| !ItemStack.matches(inventory, player.getInventory().items.get(inventorySlot))) return result(Status.STALE);
		if (!simulate) {
			// 候选已校验；同一服务器线程内，两次写入之间无外部回调或保存入口。
			// ledger 保持同一对象，publish 不触发库存索引重建；玩家槽直写不调用可重入能力。
			authority.publish(next);
			player.getInventory().items.set(inventorySlot, prepared.inventory());
			player.getInventory().setChanged();
			// 常规交换随正常世界／玩家保存，不为每次点按提交整域异步保存。
			CoreInventorySync.committed(player, inventorySlot, prepared.inventory());
		}
		return new Result(Status.MOVED, prepared.feeding().moved());
	}
	private static Prepared prepare(ManagedBeeAccess feeding, int slot, ItemStack inventory, int requested, Action action, ServerPlayer player) {
		var registries = player.registryAccess();
		if (action == Action.DEPOSIT) {
			if (inventory.isEmpty()) return null;
			var plan = feeding.feeding(slot, StaticFeedingAdapter.fromStack(inventory, registries), Math.min(requested, inventory.getCount()), true);
			return new Prepared(plan, inventory.copyWithCount(inventory.getCount() - plan.moved()));
		}
		var source = feeding.record().feedingSlots().get(slot); if (source.item() == null) return null;
		var unit = StaticFeedingAdapter.toStack(source.item(), 1, registries);
		if (!inventory.isEmpty() && !ItemStack.isSameItemSameComponents(inventory, unit)) return null;
		int count = inventory.isEmpty() ? 0 : inventory.getCount();
		int space = Math.min(64, unit.getMaxStackSize()) - count; if (space <= 0) return null;
		var plan = feeding.feeding(slot, source.item(), Math.min(requested, space), false);
		return new Prepared(plan, unit.copyWithCount(count + plan.moved()));
	}
	private static Result result(Status status) { return new Result(status, 0); }
	private CoreFeedingExchange() { }
}
