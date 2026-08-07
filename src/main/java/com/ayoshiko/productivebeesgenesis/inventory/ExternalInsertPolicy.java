package com.ayoshiko.productivebeesgenesis.inventory;

import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.api.Action;
import net.minecraft.world.item.ItemStack;

/** Limits automated external insertion without changing a slot's real capacity. */
@FunctionalInterface
public interface ExternalInsertPolicy {

	/**
	 * @return the effective slot limit for this external insertion, or zero to reject it
	 */
	int getInsertLimit(BasicInventorySlot slot, ItemStack stack, int normalLimit, Action action);

	/** Records the amount accepted by an EXECUTE call so SIMULATE and EXECUTE share one per-tick budget. */
	default void onInserted(BasicInventorySlot slot, ItemStack stack, int amount) {
	}
}
