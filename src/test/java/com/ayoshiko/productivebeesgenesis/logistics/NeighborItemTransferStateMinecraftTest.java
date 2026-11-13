package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class NeighborItemTransferStateMinecraftTest {

	@Test
	void exactRequestWaitsAcrossTicksAndRepeatedFailuresCapAtTwoHundredMilliseconds() {
		var state = new NeighborItemTransferState(new ItemStackHandler(1));
		var stack = new ItemStack(Items.DIAMOND, 64);
		long first = 1_000_000_000L;
		state.rememberRejected(stack, first);
		assertTrue(state.isRejected(stack, first + 49_999_999L));
		assertFalse(state.isRejected(stack, first + 50_000_000L));

		long second = first + 50_000_000L;
		state.rememberRejected(stack, second);
		assertTrue(state.isRejected(stack, second + 99_999_999L));
		assertFalse(state.isRejected(stack, second + 100_000_000L));

		long third = second + 100_000_000L;
		state.rememberRejected(stack, third);
		assertTrue(state.isRejected(stack, third + 199_999_999L));
		assertFalse(state.isRejected(stack, third + 200_000_000L));
		state.rememberRejected(stack, third + 200_000_000L);
		assertFalse(state.isRejected(stack, third + 400_000_000L));
	}

	@Test
	void quantitiesItemsAndComponentsRemainIndependentAndTemplatesAreCopied() {
		var state = new NeighborItemTransferState(new ItemStackHandler(1));
		var request = named("first", 64);
		var original = request.copy();
		state.rememberRejected(request, 0L);
		request.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
		request.setCount(32);

		assertTrue(state.isRejected(original, 1L));
		assertFalse(state.isRejected(original, 32, 1L));
		assertFalse(state.isRejected(request, 1L));
		assertFalse(state.isRejected(named("second", 64), 1L));
		assertFalse(state.isRejected(new ItemStack(Items.EMERALD, 64), 1L));
	}

	@Test
	void successWakesEveryRequestAndInvalidatedStatesCannotRememberNewFailures() {
		var state = new NeighborItemTransferState(new ItemStackHandler(1));
		var diamond = new ItemStack(Items.DIAMOND, 64);
		var emerald = new ItemStack(Items.EMERALD, 32);
		state.rememberRejected(diamond, 1L);
		state.rememberRejected(emerald, 1L);
		state.recordSuccess();
		assertFalse(state.isRejected(diamond, 2L));
		assertFalse(state.isRejected(emerald, 2L));

		state.rememberRejected(diamond, 3L);
		state.invalidate();
		state.rememberRejected(emerald, 4L);
		assertFalse(state.isValid());
		assertFalse(state.isRejected(diamond, 5L));
		assertFalse(state.isRejected(emerald, 5L));
	}

	@Test
	void capacityEvictionReleasesTheOldestIdentityAndStillProtectsRecentRequests() {
		var state = new NeighborItemTransferState(new ItemStackHandler(1));
		for (int i = 0; i < 65; i++) {
			state.rememberRejected(named("variant-" + i, 64), 0L);
		}
		assertFalse(state.isRejected(named("variant-0", 64), 1L));
		assertTrue(state.isRejected(named("variant-1", 64), 1L));
		assertTrue(state.isRejected(named("variant-64", 64), 1L));
		state.recordSuccess();
		assertFalse(state.isRejected(named("variant-64", 64), 1L));
	}

	@Test
	void monotonicElapsedTimeStillExpiresAcrossSignedNanoTimeWrap() {
		var state = new NeighborItemTransferState(new ItemStackHandler(1));
		var stack = new ItemStack(Items.DIAMOND, 64);
		long start = Long.MAX_VALUE - 10_000_000L;
		state.rememberRejected(stack, start);
		assertTrue(state.isRejected(stack, start + 49_999_999L));
		assertFalse(state.isRejected(stack, start + 50_000_000L));
	}

	private static ItemStack named(String name, int count) {
		var stack = new ItemStack(Items.DIAMOND, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}
}
