package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * A stable GUI slot whose backing physical output slot is selected by page.
 * Page changes never alter the tile inventory or the slots exposed to automation.
 */
final class PagedOutputContainerSlot extends InventoryContainerSlot {

	private final List<BasicInventorySlot> outputSlots;
	private final IntSupplier pageSupplier;
	private final int slotsPerPage;
	private final int pageSlot;

	PagedOutputContainerSlot(List<BasicInventorySlot> outputSlots, IntSupplier pageSupplier,
			int slotsPerPage, int pageSlot, int x, int y, BooleanSupplier outputFullWarning) {
		super(firstSlot(outputSlots), x, y, ContainerSlotType.OUTPUT, null,
				warning -> warning.warning(WarningType.NO_SPACE_IN_OUTPUT, outputFullWarning),
				ignored -> { });
		this.outputSlots = outputSlots;
		this.pageSupplier = pageSupplier;
		this.slotsPerPage = slotsPerPage;
		this.pageSlot = pageSlot;
	}

	private static BasicInventorySlot firstSlot(List<BasicInventorySlot> slots) {
		if (slots.isEmpty()) {
			throw new IllegalArgumentException("Paged output requires at least one physical slot");
		}
		return slots.get(0);
	}

	private BasicInventorySlot currentSlot() {
		int pageCount = Math.max(1, (outputSlots.size() + slotsPerPage - 1) / slotsPerPage);
		int page = Math.max(0, Math.min(pageSupplier.getAsInt(), pageCount - 1));
		int index = page * slotsPerPage + pageSlot;
		return outputSlots.get(Math.min(index, outputSlots.size() - 1));
	}

	@Override
	public BasicInventorySlot getInventorySlot() {
		return currentSlot();
	}

	@NotNull
	@Override
	public ItemStack insertItem(@NotNull ItemStack stack, Action action) {
		ItemStack remainder = currentSlot().insertItem(stack, action, AutomationType.MANUAL);
		if (action.execute() && remainder.getCount() != stack.getCount()) setChanged();
		return remainder;
	}

	@Override
	public boolean mayPlace(@NotNull ItemStack stack) {
		if (stack.isEmpty()) return false;
		BasicInventorySlot slot = currentSlot();
		if (slot.isEmpty()) {
			return insertItem(stack, Action.SIMULATE).getCount() < stack.getCount();
		}
		return !slot.extractItem(1, Action.SIMULATE, AutomationType.MANUAL).isEmpty()
				&& slot.isItemValidForInsertion(stack, AutomationType.MANUAL);
	}

	@NotNull
	@Override
	public ItemStack getItem() {
		return currentSlot().getStack();
	}

	@Override
	public boolean hasItem() {
		return !currentSlot().isEmpty();
	}

	@Override
	public void set(@NotNull ItemStack stack) {
		currentSlot().setStackUnchecked(stack);
		setChanged();
	}

	@Override
	public void setChanged() {
		super.setChanged();
		currentSlot().onContentsChanged();
	}

	@Override
	public int getMaxStackSize() {
		return currentSlot().getLimit(ItemStack.EMPTY);
	}

	@Override
	public int getMaxStackSize(@NotNull ItemStack stack) {
		return currentSlot().getLimit(stack);
	}

	@Override
	public boolean mayPickup(@NotNull Player player) {
		return !currentSlot().extractItem(1, Action.SIMULATE, AutomationType.MANUAL).isEmpty();
	}

	@NotNull
	@Override
	public ItemStack remove(int amount) {
		return currentSlot().extractItem(amount, Action.EXECUTE, AutomationType.MANUAL);
	}
}
