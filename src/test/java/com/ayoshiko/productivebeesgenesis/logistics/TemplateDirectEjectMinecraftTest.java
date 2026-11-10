package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;
import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.component.config.slot.InventorySlotInfo;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.IItemHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

@Tag("minecraft")
class TemplateDirectEjectMinecraftTest {
	private static TileEntityMekanism ownerWithCustody() {
		var tile = mock(TileEntityMekanism.class);
		var component = mock(GenesisTileComponentEjector.class);
		when(component.outputCustody()).thenReturn(new OutputCustody(() -> {}));
		when(tile.getComponents()).thenReturn(List.of(component));
		return tile;
	}

	@Test
	void actualNeighborAcceptsExplicitAmountAndPreservesTheTemplate() {
		var target = new ItemStackHandler(1) {
			@Override public int getSlotLimit(int slot) { return 13; }
		};
		try (var targets = mockConstruction(NeighborItemTargets.class, (mock, context) -> {
			when(mock.outputSides(any())).thenReturn(List.of(Direction.NORTH));
			when(mock.handler(Direction.NORTH)).thenReturn(target);
		})) {
			var ejector = mock(TileComponentEjector.class);
			var config = mock(ConfigInfo.class);
			when(ejector.isEjecting(config, TransmissionType.ITEM)).thenReturn(true);
			var fast = new FastItemEjector(ownerWithCustody());
			var template = new ItemStack(Items.DIAMOND);
			assertEquals(13, fast.pushDirect(ejector, config, template, 50, 11L));
			assertEquals(13, target.getStackInSlot(0).getCount());
			assertEquals(1, template.getCount());
		}
	}

	@Test
	void disabledOrMissingTargetDoesNotCopyTheTemplate() {
		var template = spy(new ItemStack(Items.DIAMOND));
		var fast = new FastItemEjector(ownerWithCustody());
		assertEquals(0, fast.pushDirect(mock(TileComponentEjector.class), null, template, 4096, 12L));
		assertEquals(0, IFastEjectHost.push(null, template, 4096));
		verify(template, never()).copyWithCount(anyInt());
	}

	@Test
	void legacyHostReceivesAnIndependentFullRequest() {
		var template = new ItemStack(Items.DIAMOND);
		IFastEjectHost legacy = request -> {
			assertNotSame(template, request);
			assertEquals(500, request.getCount());
			request.shrink(7);
			return 7;
		};
		assertEquals(7, IFastEjectHost.push(legacy, template, 500));
		assertEquals(1, template.getCount());
	}

	@Test
	void directRejectionSurvivesGameTicksAndRetriesWhenTheContainerHasSpace() {
		var target = new CountingHandler();
		target.reject = true;
		try (var fixture = new Fixture(target)) {
			assertEquals(0, fixture.direct(new ItemStack(Items.DIAMOND, 64), 1L));
			int reads = target.reads;
			for (int tick = 2; tick <= 20; tick++) {
				fixture.nanos = tick * 1_000_000L;
				assertEquals(0, fixture.direct(new ItemStack(Items.DIAMOND, 64), tick));
			}
			assertEquals(reads, target.reads);
			assertEquals(1, target.executions);
			target.reject = false;
			fixture.nanos = 50_000_000L;
			assertEquals(64, fixture.direct(new ItemStack(Items.DIAMOND, 64), 21L));
			assertEquals(64, target.getStackInSlot(0).getCount());
			assertFalse(OutputCustody.of(fixture.tile).blocked());
		}
	}

	@Test
	void smallerRequestAndDifferentComponentsStillTryAndSuccessWakesTheRejectedRequest() {
		var target = new CountingHandler();
		target.maximumRequest = 16;
		try (var fixture = new Fixture(target)) {
			var large = new ItemStack(Items.DIAMOND, 64);
			assertEquals(0, fixture.direct(large, 1L));
			var named = large.copy();
			named.set(DataComponents.CUSTOM_NAME, Component.literal("another identity"));
			assertEquals(0, fixture.direct(named, 2L));
			assertEquals(2, target.executions);
			assertEquals(8, fixture.direct(new ItemStack(Items.DIAMOND, 8), 3L));
			target.maximumRequest = Integer.MAX_VALUE;
			assertEquals(56, fixture.direct(large, 4L));
			assertEquals(64, large.getCount());
			assertEquals(64, target.getStackInSlot(0).getCount());
		}
	}

	@Test
	void rejectedTargetDoesNotStopOtherDirectionsOrLoseItsOwnCacheAfterTheirSuccess() {
		var north = new CountingHandler();
		north.reject = true;
		var south = new CountingHandler();
		try (var fixture = new Fixture(north, south)) {
			assertEquals(8, fixture.direct(new ItemStack(Items.DIAMOND, 8), 1L));
			int northReads = north.reads;
			assertEquals(8, fixture.direct(new ItemStack(Items.DIAMOND, 8), 2L));
			assertEquals(northReads, north.reads);
			assertEquals(16, south.getStackInSlot(0).getCount());
		}
	}

	@Test
	void sameHandlerInvalidationReplacementAndRoutingChangeAllWakeImmediately() {
		var first = new CountingHandler();
		first.reject = true;
		try (var fixture = new Fixture(first)) {
			assertEquals(0, fixture.direct(new ItemStack(Items.DIAMOND, 8), 1L));
			first.reject = false;
			fixture.invalidated.accept(Direction.NORTH);
			assertEquals(8, fixture.direct(new ItemStack(Items.DIAMOND, 8), 1L));

			first.reject = true;
			assertEquals(0, fixture.direct(new ItemStack(Items.EMERALD, 8), 2L));
			var replacement = new CountingHandler();
			fixture.handlers.put(Direction.NORTH, replacement);
			assertEquals(8, fixture.direct(new ItemStack(Items.EMERALD, 8), 2L));

			replacement.reject = true;
			assertEquals(0, fixture.direct(new ItemStack(Items.EMERALD, 8), 3L));
			replacement.reject = false;
			fixture.fast.onConfigChanged();
			assertEquals(8, fixture.direct(new ItemStack(Items.EMERALD, 8), 3L));
		}
	}

	@Test
	void fullOutputSlotsKeepAssetsAndShareRejectionWithDirectOutputDespiteBufferVersionGrowth() {
		var target = new CountingHandler();
		target.reject = true;
		try (var fixture = new Fixture(target)) {
			var source = BasicInventorySlot.at(null, 0, 0);
			source.setStack(new ItemStack(Items.DIAMOND, 64));
			fixture.output(source);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
			assertEquals(64, source.getCount());
			int reads = target.reads;
			fixture.nanos = 1_000_000L;
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 2L, 99L, true);
			assertEquals(0, fixture.direct(new ItemStack(Items.DIAMOND, 64), 2L));
			assertEquals(reads, target.reads);
			assertEquals(0, target.executions);
			assertEquals(64, source.getCount());

			target.reject = false;
			fixture.nanos = 50_000_000L;
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 3L, 99L, true);
			assertTrue(source.isEmpty());
			assertEquals(64, target.getStackInSlot(0).getCount());
		}
	}

	@Test
	void targetInvalidationClearsTheSameTickGateForExistingOutputSlots() {
		var target = new CountingHandler();
		target.reject = true;
		try (var fixture = new Fixture(target)) {
			var source = BasicInventorySlot.at(null, 0, 0);
			source.setStack(new ItemStack(Items.DIAMOND, 8));
			fixture.output(source);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
			target.reject = false;
			fixture.invalidated.accept(Direction.NORTH);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
			assertTrue(source.isEmpty());
			assertEquals(8, target.getStackInSlot(0).getCount());
		}
	}

	@Test
	void partialReceiptAndInvalidationPreserveTheSuffixWithoutCallingTheOldTargetAgain() {
		var target = new CountingHandler(2);
		target.perCallLimit = 3;
		try (var fixture = new Fixture(target)) {
			target.afterInsert = () -> fixture.invalidated.accept(Direction.NORTH);
			var request = new ItemStack(Items.DIAMOND, 8);
			assertEquals(3, fixture.direct(request, 1L));
			assertEquals(1, target.executions);
			assertEquals(3, target.getStackInSlot(0).getCount());
			assertTrue(target.getStackInSlot(1).isEmpty());
			assertEquals(8, request.getCount());
			assertFalse(OutputCustody.of(fixture.tile).blocked());
			target.afterInsert = () -> {};
			assertEquals(5, fixture.direct(new ItemStack(Items.DIAMOND, 5), 1L));
			assertEquals(8, target.getStackInSlot(0).getCount() + target.getStackInSlot(1).getCount());
		}
	}

	@Test
	void unknownReceiptMovesTheRequestToCustodyAndNeverRetriesIt() {
		var target = new CountingHandler();
		target.perCallLimit = 3;
		target.afterInsert = () -> { throw new IllegalStateException("accepted before failing"); };
		try (var fixture = new Fixture(target)) {
			assertEquals(8, fixture.direct(new ItemStack(Items.DIAMOND, 8), 1L));
			var custody = OutputCustody.of(fixture.tile);
			assertTrue(custody.blocked());
			assertEquals(8L, custody.amount());
			fixture.nanos = 1_000_000_000L;
			assertEquals(0, fixture.direct(new ItemStack(Items.DIAMOND, 8), 2L));
			assertEquals(1, target.executions);
			assertEquals(3, target.getStackInSlot(0).getCount());
			custody.resolve(custody.sequence(), 3);
			assertEquals(5, custody.recoveredItem().getCount());
		}
	}

	@Test
	void simulationInvalidationStopsEveryLaterOldTargetCallAndLeavesSourceUntouched() {
		for (String invalidateAt : List.of("slots", "stack", "insert")) {
			var invalidated = new boolean[1];
			Runnable[] invalidate = {() -> {}};
			int[] writes = {0};
			var target = new ItemStackHandler(2) {
				private void entered(String operation) {
					assertFalse(invalidated[0], "Old target called after invalidation: " + operation);
					if (operation.equals(invalidateAt)) {
						invalidated[0] = true;
						invalidate[0].run();
					}
				}

				@Override
				public int getSlots() {
					entered("slots");
					return 2;
				}

				@Override
				public ItemStack getStackInSlot(int slot) {
					entered("stack");
					return ItemStack.EMPTY;
				}

				@Override
				public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
					entered("insert");
					if (!simulate) writes[0]++;
					return stack.copyWithCount(stack.getCount() - 1);
				}
			};
			try (var fixture = new Fixture(target)) {
				invalidate[0] = () -> fixture.invalidated.accept(Direction.NORTH);
				var source = BasicInventorySlot.at(null, 0, 0);
				source.setStack(new ItemStack(Items.DIAMOND, 8));
				fixture.output(source);
				fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
				assertTrue(invalidated[0], invalidateAt);
				assertEquals(0, writes[0]);
				assertEquals(8, source.getCount());
				assertFalse(OutputCustody.of(fixture.tile).blocked());
			}
		}
	}

	@Test
	void guardedSimulationMatchesVanillaStackingOrderAndPartialCapacity() {
		var target = new ItemStackHandler(3);
		target.setStackInSlot(0, new ItemStack(Items.DIAMOND, 61));
		target.setStackInSlot(2, new ItemStack(Items.EMERALD, 64));
		var request = new ItemStack(Items.DIAMOND, 80);
		assertEquals(67, ItemPushHelper.simulateInsert(target, request, () -> true));
		assertEquals(ItemPushHelper.simulateInsert(target, request),
				ItemPushHelper.simulateInsert(target, request, () -> true));
		assertEquals(80, request.getCount());
		assertEquals(61, target.getStackInSlot(0).getCount());
		assertTrue(target.getStackInSlot(1).isEmpty());
		var tool = new ItemStack(Items.DIAMOND_PICKAXE);
		assertEquals(ItemPushHelper.simulateInsert(target, tool),
				ItemPushHelper.simulateInsert(target, tool, () -> true));
	}

	@Test
	void stableTieredSlotsSkipExtractionAndShareARejectionAfterAnotherProductSucceeds() {
		var target = new ItemStackHandler(1) {
			@Override
			public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
				return stack.is(Items.DIAMOND) ? stack : super.insertItem(slot, stack, simulate);
			}
		};
		// 本例验证完整扫描后的拒收共享，固定预算以免实际耗时改变轮转起点。
		try (var budgets = mockConstruction(ItemEjectionBudget.class,
				(mock, context) -> when(mock.canAttempt(anyLong(), anyBoolean())).thenReturn(true));
				var fixture = new Fixture(target)) {
			var first = spy(TieredOutputInventorySlot.at(() -> 2, null, 0, 0));
			var second = spy(TieredOutputInventorySlot.at(() -> 2, null, 0, 0));
			first.setStack(new ItemStack(Items.DIAMOND, 64));
			second.setStack(new ItemStack(Items.DIAMOND, 64));
			fixture.output(first, second);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 2L, 2L, true);
			verify(first, times(1)).extractItem(64, Action.SIMULATE, AutomationType.EXTERNAL);
			verify(second, times(1)).extractItem(64, Action.SIMULATE, AutomationType.EXTERNAL);
			assertEquals(4, fixture.direct(new ItemStack(Items.EMERALD, 4), 3L));
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 4L, 2L, true);
			verify(first, times(2)).extractItem(64, Action.SIMULATE, AutomationType.EXTERNAL);
			verify(second, times(1)).extractItem(64, Action.SIMULATE, AutomationType.EXTERNAL);
			assertEquals(128, first.getCount() + second.getCount());
			assertEquals(4, target.getStackInSlot(0).getCount());
		}
	}

	@Test
	void legalComponentCountAndSlotReplacementChangesDoNotReuseOldSourceBindings() {
		for (String change : List.of("component", "count", "slot")) {
			var target = new CountingHandler();
			target.reject = true;
			try (var fixture = new Fixture(target)) {
				var source = TieredOutputInventorySlot.at(() -> 2, null, 0, 0);
				source.setStack(new ItemStack(Items.DIAMOND, 64));
				fixture.output(source);
				fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
				long version = source.contentsVersion();
				if (change.equals("slot")) source = TieredOutputInventorySlot.at(() -> 2, null, 0, 0);
				if (change.equals("count")) {
					source.shrinkStack(1, Action.EXECUTE);
				} else {
					var replacement = new ItemStack(Items.DIAMOND, 64);
					replacement.set(DataComponents.CUSTOM_NAME, Component.literal("new identity"));
					source.setStack(replacement);
				}
				if (!change.equals("slot")) assertTrue(source.contentsVersion() > version);
				if (change.equals("slot")) fixture.output(source);
				target.reject = false;
				fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 2L, 1L, true);
				assertTrue(source.isEmpty(), change);
				assertEquals(change.equals("count") ? 63 : 64, target.getStackInSlot(0).getCount());
			}
		}
	}

	@Test
	void directRequestEvictionRetiresAStableSlotPointerBeforeItsOldDeadline() {
		var target = new CountingHandler();
		target.reject = true;
		try (var fixture = new Fixture(target)) {
			var source = TieredOutputInventorySlot.at(() -> 2, null, 0, 0);
			source.setStack(new ItemStack(Items.DIAMOND, 64));
			fixture.output(source);
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 1L, 1L, true);
			for (int i = 1; i <= 64; i++) {
				assertEquals(0, fixture.direct(new ItemStack(Items.EMERALD, i), i + 1L));
			}
			target.reject = false;
			fixture.fast.tick(fixture.tile, fixture.ejector, fixture.config, 66L, 1L, true);
			assertTrue(source.isEmpty());
			assertEquals(64, target.getStackInSlot(0).getCount());
		}
	}

	private static final class Fixture implements AutoCloseable {
		private final TileEntityMekanism tile = ownerWithCustody();
		private final TileComponentEjector ejector = mock(TileComponentEjector.class);
		private final ConfigInfo config = new ConfigInfo();
		private final Map<Direction, IItemHandler> handlers = new EnumMap<>(Direction.class);
		private final MockedConstruction<NeighborItemTargets> construction;
		private final FastItemEjector fast;
		private Consumer<Direction> invalidated;
		private long nanos;

		@SuppressWarnings("unchecked")
		private Fixture(IItemHandler... targets) {
			handlers.put(Direction.NORTH, targets[0]);
			if (targets.length > 1) handlers.put(Direction.SOUTH, targets[1]);
			when(tile.getDirection()).thenReturn(Direction.NORTH);
			when(ejector.isEjecting(config, TransmissionType.ITEM)).thenReturn(true);
			construction = mockConstruction(NeighborItemTargets.class, (mock, context) -> {
				invalidated = (Consumer<Direction>) context.arguments().get(1);
				when(mock.outputSides(any())).thenAnswer(call -> List.copyOf(handlers.keySet()));
				when(mock.handler(any())).thenAnswer(call -> handlers.get(call.getArgument(0)));
			});
			fast = new FastItemEjector(tile, () -> nanos);
		}

		private int direct(ItemStack stack, long tick) {
			return fast.pushDirect(ejector, config, stack, tick);
		}

		private void output(IInventorySlot... slots) {
			config.addSlotInfo(DataType.OUTPUT, new InventorySlotInfo(false, true, slots));
			config.setDataType(DataType.OUTPUT, RelativeSide.FRONT);
		}

		@Override
		public void close() {
			construction.close();
		}
	}

	private static class CountingHandler extends ItemStackHandler {
		private int reads;
		private int executions;
		private boolean reject;
		private int maximumRequest = Integer.MAX_VALUE;
		private int perCallLimit = Integer.MAX_VALUE;
		private Runnable afterInsert = () -> {};

		private CountingHandler() {
			this(1);
		}

		private CountingHandler(int slots) {
			super(slots);
		}

		@Override
		public ItemStack getStackInSlot(int slot) {
			reads++;
			return super.getStackInSlot(slot);
		}

		@Override
		public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
			if (!simulate) executions++;
			if (reject || stack.getCount() > maximumRequest) return stack;
			int requested = Math.min(stack.getCount(), perCallLimit);
			ItemStack rest = super.insertItem(slot, stack.copyWithCount(requested), simulate);
			int accepted = requested - rest.getCount();
			if (!simulate) afterInsert.run();
			return stack.copyWithCount(stack.getCount() - accepted);
		}
	}
}
