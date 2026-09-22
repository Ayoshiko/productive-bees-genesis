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
		if (key == null || expectedLedgerRevision < 0 || inventorySlot < 0 || inventorySlot >= 36 || requested < 1
				|| requested > (key.kind() == ProductKey.Kind.ITEM ? 64 : FluidType.BUCKET_VOLUME)) return result(Status.INVALID);
		var authority = core.ownership().readyAuthority(); if (authority == null) return result(Status.UNAVAILABLE);
		var current = authority.checkpoint();
		if (current.ledger().revision() != expectedLedgerRevision) return result(Status.STALE);
		int available = (int) Math.min(requested, current.ledger().available(key).longSaturated());
		if (available == 0) return result(Status.EMPTY_OR_RESERVED);
		var inventory = player.getInventory().items.get(inventorySlot).copy();
		ItemStack received; int moved; NetworkCheckpoint next;
		try {
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
	private static Result result(Status status) { return new Result(status, 0); }
	private CoreProductWithdrawal() { }
}
