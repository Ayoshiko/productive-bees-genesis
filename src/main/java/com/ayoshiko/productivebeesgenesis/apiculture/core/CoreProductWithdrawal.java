package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedBucketProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidType;

/** 所有者从已入账产物取回到一个有限玩家槽；预约和未知外部转移不参与交付。 */
public final class CoreProductWithdrawal {
	public enum Status { MOVED, NO_SPACE, EMPTY_OR_RESERVED, UNSUPPORTED_CONTAINER, INVALID, STALE, UNAVAILABLE }
	public record Result(Status status, int moved) { }
	static Result withdraw(NetworkCoreMenu menu, ServerPlayer player, ProductKey key, long expectedLedgerRevision,
			int inventorySlot, int requested, boolean simulate) {
		var core = menu.exchangeCore(player); if (core == null) return result(Status.UNAVAILABLE);
		if (key == null || expectedLedgerRevision < 0 || inventorySlot < -1 || inventorySlot >= 36 || requested < 1
				|| requested > (key.kind() == ProductKey.Kind.ITEM ? 64 : FluidType.BUCKET_VOLUME)) return result(Status.INVALID);
		var authority = core.ownership().readyAuthority(); if (authority == null) return result(Status.UNAVAILABLE);
		var current = authority.checkpoint();
		if (current.ledger().revision() != expectedLedgerRevision) return result(Status.STALE);
		int available = (int) Math.min(requested, current.ledger().available(key).longSaturated());
		if (available == 0) return result(Status.EMPTY_OR_RESERVED);
		ItemStack inventory, received; int moved; NetworkCheckpoint next;
		try {
			// -1 只表示从当前真实背包选择一个接收槽，不改变有限交付的提交点。
			if (inventorySlot == -1) inventorySlot = destination(player, key);
			if (inventorySlot < 0) return result(key.kind() == ProductKey.Kind.ITEM ? Status.NO_SPACE : Status.UNSUPPORTED_CONTAINER);
			inventory = player.getInventory().items.get(inventorySlot).copy();
			if (key.kind() == ProductKey.Kind.ITEM) {
				var unit = ProductKeyCodec.item(key, 1, player.registryAccess());
				if (!ProductKeyCodec.item(unit, player.registryAccess()).equals(key)) return result(Status.INVALID);
				if (!inventory.isEmpty() && !ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.NO_SPACE);
				int count = inventory.isEmpty() ? 0 : inventory.getCount();
				int space = Math.min(64, unit.getMaxStackSize()) - count; if (space <= 0) return result(Status.NO_SPACE);
				moved = Math.min(available, space); received = unit.copyWithCount(count + moved);
			} else {
				if (available < FluidType.BUCKET_VOLUME) return result(Status.EMPTY_OR_RESERVED);
				received = VerifiedBucketProjection.fill(key, inventory, player.registryAccess());
				if (received.isEmpty()) return result(Status.UNSUPPORTED_CONTAINER);
				moved = FluidType.BUCKET_VOLUME;
			}
			next = current.withdrawProduct(expectedLedgerRevision, key, moved);
		} catch (IllegalArgumentException invalid) { return result(Status.INVALID); }
		catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot prepare bee network product withdrawal at {}", core.getBlockPos(), failure);
			return result(Status.INVALID);
		}
		if (next == current || menu.exchangeCore(player) != core || authority.checkpoint() != current
				|| !ItemStack.matches(inventory, player.getInventory().items.get(inventorySlot))) return result(Status.STALE);
		if (!simulate) {
			authority.publish(next);
			player.getInventory().items.set(inventorySlot, received); player.getInventory().setChanged();
			CoreInventorySync.committed(player, inventorySlot, received);
		}
		return new Result(Status.MOVED, moved);
	}
	/** 最多检查 36 格；同组件未满栈优先于空格，不碰盔甲、副手或第三方容器。 */
	private static int destination(ServerPlayer player, ProductKey key) {
		var unit = key.kind() == ProductKey.Kind.ITEM ? ProductKeyCodec.item(key, 1, player.registryAccess()) : ItemStack.EMPTY;
		int empty = -1;
		for (int slot = 0; slot < 36; slot++) {
			var stack = player.getInventory().items.get(slot);
			if (key.kind() == ProductKey.Kind.FLUID) {
				if (!VerifiedBucketProjection.fill(key, stack, player.registryAccess()).isEmpty()) return slot;
			} else if (stack.isEmpty()) {
				if (empty < 0) empty = slot;
			} else if (ItemStack.isSameItemSameComponents(stack, unit) && stack.getCount() < Math.min(64, unit.getMaxStackSize())) return slot;
		}
		return empty;
	}
	private static Result result(Status status) { return new Result(status, 0); }
	private CoreProductWithdrawal() { }
}
