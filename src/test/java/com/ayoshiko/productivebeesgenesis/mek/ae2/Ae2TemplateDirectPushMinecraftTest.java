package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Actionable;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2TemplateDirectPushMinecraftTest {
	@Test
	void measuredStorageCountsOnlyReceiverAndPreservesAllKeyKinds() {
		var time = new long[]{0};
		var tracker = new Ae2InsertCostTracker();
		var measured = new Ae2MeasuredStorage(() -> time[0]);
		MEStorage storage = mock(MEStorage.class);
		var source = Ae2PushLimits.ActionSourceHolder.INSTANCE;
		var keys = java.util.List.of(AEItemKey.of(Items.HONEYCOMB),
				appeng.api.stacks.AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER),
				mock(appeng.api.stacks.AEKey.class));
		when(storage.insert(any(), anyLong(), any(), any())).thenAnswer(call -> {
			time[0] += 80_000;
			return 37L;
		});
		for (var key : keys) {
			measured.prepare(storage, 500, tracker);
			time[0] += 10_000_000;
			assertEquals(37, measured.insert(key, 4096, Actionable.MODULATE, source));
			time[0] += 10_000_000;
			assertEquals(80_000, measured.lastCostNanos());
			verify(storage).insert(key, 4096, Actionable.MODULATE, source);
		}
		assertEquals(240_000, tracker.tileSpentThisTickNanos(500));
		assertEquals(0, tracker.successDelayNanos());
	}

	@Test
	void receiverExceptionKeepsCustodyAndPreventsRepeatedInsertion() {
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("after acceptance"));
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 501, new Ae2InsertCostTracker());
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		var stack = new ItemStack(Items.HONEYCOMB);
		assertEquals(4096, session.push(stack, 4096));
		assertTrue(session.custody.blocked());
		assertEquals(0, session.push(stack, 4096));
		verify(storage, times(1)).insert(any(), anyLong(), any(), any());
	}
	@Test
	void explicitAmountAndExactComponentsReachStorageWithoutCopyingTemplate() {
		ItemStack template = spy(new ItemStack(Items.HONEYCOMB));
		template.set(DataComponents.CUSTOM_NAME, Component.literal("exact variant"));
		MEStorage storage = mock(MEStorage.class);
		AEItemKey key = AEItemKey.of(template);
		when(storage.insert(eq(key), eq(4096L), eq(Actionable.MODULATE), any())).thenReturn(1024L);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 123L, null);
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		assertEquals(1024, session.push(template, 4096));
		verify(storage).insert(eq(key), eq(4096L), eq(Actionable.MODULATE), any());
		assertEquals(1, template.getCount());
		assertEquals(key, AEItemKey.of(template));
		assertFalse(session.custody.blocked());
	}

	@Test
	void disabledTargetAndInvalidAmountDoNotAllocateOrInsert() {
		ItemStack template = spy(new ItemStack(Items.HONEYCOMB));
		var host = mock(IAe2OutputHostBase.class);
		assertEquals(0, Ae2OutputPusher.pushItemStack(host, template, 4096));
		MEStorage storage = mock(MEStorage.class);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 124L, null);
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		assertEquals(0, session.push(template, 0));
		assertEquals(0, session.push(template, -1));
		verifyNoInteractions(storage);
		verify(template, never()).copyWithCount(anyInt());
	}

	@Test
	void legacyStackEntryPointStillUsesItsOwnCountAndClampsAcceptance() {
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(any(), anyLong(), eq(Actionable.MODULATE), any())).thenReturn(Long.MAX_VALUE);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 125L, null);
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		ItemStack stack = new ItemStack(Items.IRON_INGOT, 37);
		assertEquals(37, session.applyAsInt(stack));
		verify(storage).insert(eq(AEItemKey.of(stack)), eq(37L), eq(Actionable.MODULATE), any());
		assertEquals(37, stack.getCount());
	}
}
