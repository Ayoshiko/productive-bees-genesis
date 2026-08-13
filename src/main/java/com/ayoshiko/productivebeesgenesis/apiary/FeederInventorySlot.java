package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.IContentsListener;
import mekanism.api.functions.ConstantPredicates;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

class FeederInventorySlot extends BasicInventorySlot {

	static FeederInventorySlot create(@Nullable IContentsListener listener) {
		return new FeederInventorySlot(listener);
	}

	private FeederInventorySlot(@Nullable IContentsListener listener) {
		super(
				ConstantPredicates.manualOnly(),
				ConstantPredicates.manualOnly(),
				(Predicate<ItemStack>) stack -> !PbUpgradeInventorySlot.isValidUpgradeItem(stack),
				listener, 0, 0
		);
	}

	@NotNull
	@Override
	public VirtualInventoryContainerSlot createContainerSlot() {
		return new VirtualInventoryContainerSlot(this, SelectedWindowData.UNSPECIFIED, getSlotOverlay(),
			this::setStackUnchecked);
	}
}
