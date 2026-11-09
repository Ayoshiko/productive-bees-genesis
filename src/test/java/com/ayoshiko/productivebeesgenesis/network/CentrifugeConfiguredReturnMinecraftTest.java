package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class CentrifugeConfiguredReturnMinecraftTest {
	@Test
	void partialSideEffectThenThrowMovesWholeRequestToCustodyWithoutRestoringOrRetrying() {
		var source = BasicInventorySlot.at(stack -> true, () -> {}, 0, 0);
		source.setStack(new ItemStack(Items.IRON_INGOT, 10));
		var custody = new OutputCustody(() -> {});
		var target = new ItemStackHandler(1) {
			int calls;
			@Override
			public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
				if (simulate) return ItemStack.EMPTY;
				calls++;
				setStackInSlot(slot, stack.copyWithCount(3));
				throw new IllegalStateException("received three");
			}
		};
		assertEquals(0, CentrifugeConfiguredOutputService.transferToTarget(source, target, custody, "test-side"));
		assertTrue(source.isEmpty());
		assertEquals(3, target.getStackInSlot(0).getCount());
		assertEquals(10, custody.amount());
		assertTrue(custody.blocked());
		assertEquals(0, CentrifugeConfiguredOutputService.transferToTarget(source, target, custody, "test-side"));
		assertEquals(1, target.calls);
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var restored = new OutputCustody(() -> {});
		restored.load(registries, custody.save(registries));
		restored.resolve(restored.sequence(), 3);
		assertEquals(7, restored.recoveredItem().getCount());
	}

	@Test
	void knownPartialReceiptRestoresOnlyTheKnownRemainder() {
		var source = BasicInventorySlot.at(stack -> true, () -> {}, 0, 0);
		source.setStack(new ItemStack(Items.IRON_INGOT, 10));
		var custody = new OutputCustody(() -> {});
		var target = new ItemStackHandler(1) {
			@Override
			public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
				if (simulate) return ItemStack.EMPTY;
				setStackInSlot(slot, stack.copyWithCount(3));
				return stack.copyWithCount(stack.getCount() - 3);
			}
		};
		assertEquals(3, CentrifugeConfiguredOutputService.transferToTarget(source, target, custody, "test-side"));
		assertEquals(7, source.getCount());
		assertEquals(3, target.getStackInSlot(0).getCount());
		assertFalse(custody.blocked());
	}
}
