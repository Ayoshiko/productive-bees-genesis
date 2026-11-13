package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.me.storage.NetworkStorage;
import com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import java.util.List;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

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
	void longRequestAndPartialReceiptRemainExactAcrossTheSessionBoundary() {
		MEStorage storage = mock(MEStorage.class);
		long requested = 3L * Integer.MAX_VALUE + 7;
		long accepted = 2L * Integer.MAX_VALUE + 3;
		var template = new ItemStack(Items.HONEYCOMB);
		template.set(DataComponents.CUSTOM_NAME, Component.literal("large exact variant"));
		var key = AEItemKey.of(template);
		when(storage.insert(eq(key), eq(requested), eq(Actionable.MODULATE), any())).thenReturn(accepted);
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 502, null);
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		assertEquals(accepted, session.push(template, requested));
		assertEquals(1, template.getCount());
		assertEquals(key, AEItemKey.of(template));
		assertFalse(session.custody.blocked());
		verify(storage).insert(eq(key), eq(requested), eq(Actionable.MODULATE), any());
	}

	@Test
	void unknownLongRequestIsHeldExactlyAndNeverInsertedAgain() {
		MEStorage storage = mock(MEStorage.class);
		when(storage.insert(any(), anyLong(), any(), any())).thenThrow(new IllegalStateException("unknown prefix"));
		var session = new Ae2DirectItemPushSession(new Ae2OutputStateHolder(), storage, null, 503, null);
		session.custody = new com.ayoshiko.productivebeesgenesis.logistics.OutputCustody(() -> {});
		var template = new ItemStack(Items.HONEYCOMB);
		long requested = 4_000_000_000L;
		assertEquals(requested, session.push(template, requested));
		assertTrue(session.custody.blocked());
		assertEquals(requested, session.custody.amount());
		assertEquals(0, session.push(template, requested));
		verify(storage).insert(any(), eq(requested), eq(Actionable.MODULATE), any());
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

	@Test
	void publicPushRoutesLongPartialReceiptThroughPriorityMounts() {
		var template = new ItemStack(Items.HONEYCOMB);
		var key = AEItemKey.of(template);
		long requested = 3L * Integer.MAX_VALUE + 7;
		long firstAccepted = 2L * Integer.MAX_VALUE + 3;
		long fallbackAccepted = 1024;
		MEStorage first = mock(MEStorage.class);
		MEStorage fallback = mock(MEStorage.class);
		when(first.insert(eq(key), eq(requested), eq(Actionable.MODULATE), any())).thenReturn(firstAccepted);
		when(fallback.isPreferredStorageFor(eq(key), any())).thenReturn(true);
		when(fallback.insert(eq(key), eq(requested - firstAccepted), eq(Actionable.MODULATE), any()))
				.thenReturn(fallbackAccepted);
		try (var fixture = new MixedNetworkFixture()) {
			fixture.network.mount(-10, fallback);
			fixture.network.mount(100, first);

			long handedOff = Ae2OutputPusher.pushItemStack(fixture.host, template, requested);

			assertEquals(firstAccepted + fallbackAccepted, handedOff);
			assertEquals(Integer.MAX_VALUE + 4L - fallbackAccepted, requested - handedOff);
			assertFalse(fixture.custody.blocked());
			assertEquals(1, template.getCount());
			var order = inOrder(first, fallback);
			order.verify(first).insert(eq(key), eq(requested), eq(Actionable.MODULATE), any());
			order.verify(fallback).insert(eq(key), eq(requested - firstAccepted), eq(Actionable.MODULATE), any());
		}
	}

	@Test
	void publicPushKeepsCompleteComponentVariantsDistinctAcrossFilteredMounts() {
		var firstTemplate = new ItemStack(Items.HONEYCOMB);
		firstTemplate.set(DataComponents.CUSTOM_NAME, Component.literal("first exact variant"));
		var secondTemplate = new ItemStack(Items.HONEYCOMB);
		secondTemplate.set(DataComponents.CUSTOM_NAME, Component.literal("second exact variant"));
		var firstKey = AEItemKey.of(firstTemplate);
		var secondKey = AEItemKey.of(secondTemplate);
		assertNotEquals(firstKey, secondKey);
		MEStorage first = mock(MEStorage.class);
		MEStorage second = mock(MEStorage.class);
		when(first.isPreferredStorageFor(eq(firstKey), any())).thenReturn(true);
		when(first.insert(eq(firstKey), eq(17L), eq(Actionable.MODULATE), any())).thenReturn(17L);
		when(second.isPreferredStorageFor(eq(secondKey), any())).thenReturn(true);
		when(second.insert(eq(secondKey), eq(19L), eq(Actionable.MODULATE), any())).thenReturn(19L);
		try (var fixture = new MixedNetworkFixture()) {
			fixture.network.mount(100, first);
			fixture.network.mount(0, second);

			assertEquals(17L, Ae2OutputPusher.pushItemStack(fixture.host, firstTemplate, 17L));
			fixture.advanceTick();
			assertEquals(19L, Ae2OutputPusher.pushItemStack(fixture.host, secondTemplate, 19L));

			verify(first).insert(eq(firstKey), eq(17L), eq(Actionable.MODULATE), any());
			verify(first).insert(eq(secondKey), eq(19L), eq(Actionable.MODULATE), any());
			verify(second, never()).insert(eq(firstKey), anyLong(), any(), any());
			verify(second).insert(eq(secondKey), eq(19L), eq(Actionable.MODULATE), any());
			assertEquals(firstKey, AEItemKey.of(firstTemplate));
			assertEquals(secondKey, AEItemKey.of(secondTemplate));
			assertFalse(fixture.custody.blocked());
		}
	}

	@Test
	void acceptedPrefixBeforeLaterMountFailureTransfersWholeRequestToCustody() {
		var template = new ItemStack(Items.HONEYCOMB);
		template.set(DataComponents.CUSTOM_NAME, Component.literal("unresolved mixed output"));
		var key = AEItemKey.of(template);
		long requested = 4_000_000_000L;
		long acceptedPrefix = 1_000_000_000L;
		long[] storedPrefix = {0};
		MEStorage first = mock(MEStorage.class);
		MEStorage failing = mock(MEStorage.class);
		when(first.insert(eq(key), eq(requested), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
			storedPrefix[0] += acceptedPrefix;
			return acceptedPrefix;
		});
		when(failing.insert(eq(key), eq(requested - acceptedPrefix), eq(Actionable.MODULATE), any()))
				.thenThrow(new IllegalStateException("later mount failed after accepted prefix"));
		try (var fixture = new MixedNetworkFixture()) {
			fixture.network.mount(100, first);
			fixture.network.mount(0, failing);

			long sourceRemaining = requested;
			sourceRemaining -= Ae2OutputPusher.pushItemStack(fixture.host, template, sourceRemaining);

			assertEquals(0, sourceRemaining);
			assertEquals(acceptedPrefix, storedPrefix[0]);
			assertTrue(fixture.custody.blocked());
			assertEquals(requested, fixture.custody.amount());
			assertNotNull(fixture.custody.sequence());
			assertEquals("ae2-item", fixture.custody.destination());
			assertTrue(fixture.custody.failure().contains("later mount failed after accepted prefix"));
			assertTrue(fixture.custody.recoveredItem().isEmpty());
			assertEquals(0L, Ae2OutputPusher.pushItemStack(fixture.host, template, requested));
			fixture.advanceTick();
			assertEquals(0L, Ae2OutputPusher.pushItemStack(fixture.host, template, requested));
			verify(first, times(1)).insert(eq(key), eq(requested), eq(Actionable.MODULATE), any());
			verify(failing, times(1)).insert(eq(key), eq(requested - acceptedPrefix), eq(Actionable.MODULATE), any());
			assertEquals(acceptedPrefix, storedPrefix[0]);
		}
	}

	@Test
	void legalVoidReceiptConsumesOfferedAmountWithoutFallbackOrQuarantine() {
		var template = new ItemStack(Items.HONEYCOMB);
		var key = AEItemKey.of(template);
		long requested = 4096;
		long[] retained = {0};
		MEStorage voidReceiver = mock(MEStorage.class);
		MEStorage fallback = mock(MEStorage.class);
		when(voidReceiver.insert(eq(key), eq(requested), eq(Actionable.MODULATE), any())).thenAnswer(call -> {
			retained[0] += 3;
			return requested;
		});
		try (var fixture = new MixedNetworkFixture()) {
			fixture.network.mount(100, voidReceiver);
			fixture.network.mount(0, fallback);

			long handedOff = Ae2OutputPusher.pushItemStack(fixture.host, template, requested);

			// 虚空策略消费的数量也在合法回执内，不能以库存留存量重算交付量。
			assertEquals(requested, handedOff);
			assertEquals(3, retained[0]);
			assertEquals(0, requested - handedOff);
			assertFalse(fixture.custody.blocked());
			assertEquals(0, fixture.custody.amount());
			verify(fallback, never()).insert(any(), anyLong(), any(), any());
		}
	}

	private static final class MixedNetworkFixture implements AutoCloseable {
		final NetworkStorage network = new NetworkStorage();
		final Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
		final OutputCustody custody = new OutputCustody(() -> {});
		final TileEntityMekanism owner = mock(TileEntityMekanism.class,
				withSettings().extraInterfaces(IAe2OutputHostBase.class));
		final IAe2OutputHostBase host = (IAe2OutputHostBase) owner;
		final Level level = mock(Level.class);
		final MockedStatic<Ae2IntegrationLoader> integration = mockStatic(Ae2IntegrationLoader.class);
		private long gameTick = 600;

		MixedNetworkFixture() {
			integration.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(true);
			var component = mock(GenesisTileComponentEjector.class);
			when(component.outputCustody()).thenReturn(custody);
			when(owner.getComponents()).thenReturn(List.of(component));
			when(host.productivebeesgenesis$isOutputPushEnabled()).thenReturn(true);
			when(host.productivebeesgenesis$isAeItemOutputEnabled()).thenReturn(true);
			when(host.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
			when(host.productivebeesgenesis$getAe2Level()).thenReturn(level);
			when(level.getGameTime()).thenAnswer(call -> gameTick);
			var managed = mock(IManagedGridNode.class);
			var node = mock(IGridNode.class);
			var grid = mock(IGrid.class);
			var service = mock(IStorageService.class);
			when(host.productivebeesgenesis$getAe2GridNode()).thenReturn(managed);
			when(managed.getNode()).thenReturn(node);
			when(managed.getGrid()).thenReturn(grid);
			when(node.isPowered()).thenReturn(true);
			when(node.hasGridBooted()).thenReturn(true);
			when(node.meetsChannelRequirements()).thenReturn(true);
			when(grid.getService(IStorageService.class)).thenReturn(service);
			when(service.getInventory()).thenReturn(network);
		}

		void advanceTick() {
			gameTick += 1000;
		}

		@Override
		public void close() {
			holder.clear();
			integration.close();
			Ae2NetworkWorkCoordinator.clearAll();
		}
	}
}
