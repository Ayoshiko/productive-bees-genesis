package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class LocalOutputTransferMinecraftTest {

	@Test
	void fullOwnedSlotsSkipZeroAcceptanceHandoffs() {
		var fixture = new Fixture(3);
		for (var slot : fixture.slots) slot.setStack(new ItemStack(Items.DIAMOND, 64));
		var request = named(Items.DIAMOND, 8, "other identity");

		assertEquals(0, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		for (var slot : fixture.slots) {
			verify(slot, never()).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
		}
		assertFalse(fixture.custody.blocked());
		assertEquals(192, fixture.index.outputItemCount());
		assertEquals(8, request.getCount());
	}

	@Test
	void matchingSlotsKeepPhysicalOrderAndPrecedeEarlierEmptySlots() {
		var fixture = new Fixture(4);
		fixture.slots.get(1).setStack(new ItemStack(Items.DIAMOND, 61));
		fixture.slots.get(2).setStack(new ItemStack(Items.EMERALD, 64));
		fixture.slots.get(3).setStack(new ItemStack(Items.DIAMOND, 60));
		var order = new ArrayList<Integer>();
		for (int i = 0; i < fixture.slots.size(); i++) {
			int index = i;
			doAnswer(call -> {
				order.add(index);
				return call.callRealMethod();
			}).when(fixture.slots.get(i)).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
		}
		var request = new ItemStack(Items.DIAMOND, 10);

		assertEquals(10, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		assertEquals(List.of(1, 3, 0), order);
		assertEquals(3, fixture.slots.getFirst().getCount());
		assertEquals(64, fixture.slots.get(1).getCount());
		assertEquals(64, fixture.slots.get(3).getCount());
		assertEquals(10, request.getCount());
	}

	@Test
	void multiwordIndexVisitsOnlyMatchingAndEmptySlotsAfterInitialScan() {
		var fixture = new Fixture(70);
		for (var slot : fixture.slots) slot.setStack(new ItemStack(Items.EMERALD, 64));
		fixture.slots.get(63).setStack(new ItemStack(Items.DIAMOND, 63));
		fixture.slots.get(64).setStack(new ItemStack(Items.DIAMOND, 62));
		fixture.slots.get(69).setStack(ItemStack.EMPTY);
		assertTrue(fixture.index.hasOutputItems());
		for (var slot : fixture.slots) clearInvocations(slot);
		var order = new ArrayList<Integer>();
		for (int index : new int[]{63, 64, 69}) {
			doAnswer(call -> {
				order.add(index);
				return call.callRealMethod();
			}).when(fixture.slots.get(index)).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
		}
		var request = new ItemStack(Items.DIAMOND, 6);

		assertEquals(6, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		assertEquals(List.of(63, 64, 69), order);
		for (int i = 0; i < fixture.slots.size(); i++) {
			if (i != 63 && i != 64 && i != 69) verify(fixture.slots.get(i), never()).getStack();
		}
		assertEquals(3, fixture.slots.get(69).getCount());
		assertEquals(6, request.getCount());
	}

	@Test
	void insertionCallbackRefreshesLaterCandidatesWithoutRevisitingEarlierSlots() {
		var fixture = new Fixture(4);
		fixture.slots.get(1).setStack(new ItemStack(Items.DIAMOND, 63));
		fixture.slots.get(2).setStack(new ItemStack(Items.EMERALD, 64));
		fixture.slots.get(3).setStack(new ItemStack(Items.EMERALD, 64));
		fixture.callbacks[1] = () -> {
			fixture.callbacks[1] = () -> {};
			fixture.slots.get(0).setStack(new ItemStack(Items.DIAMOND, 63));
			fixture.slots.get(2).setStack(new ItemStack(Items.DIAMOND, 62));
		};

		assertEquals(3, LocalOutputTransfer.insert(fixture.index, new ItemStack(Items.DIAMOND, 4), fixture.custody));
		assertEquals(63, fixture.slots.getFirst().getCount());
		assertEquals(64, fixture.slots.get(1).getCount());
		assertEquals(64, fixture.slots.get(2).getCount());
		assertEquals(255, fixture.index.outputItemCount());
	}

	@Test
	void emptyPassObservesAChangeMadeByThePriorInsertion() {
		var fixture = new Fixture(3);
		fixture.slots.get(0).setStack(new ItemStack(Items.DIAMOND, 63));
		fixture.slots.get(2).setStack(new ItemStack(Items.EMERALD, 64));
		fixture.callbacks[0] = () -> {
			fixture.callbacks[0] = () -> {};
			fixture.slots.get(1).setStack(new ItemStack(Items.EMERALD, 64));
			fixture.slots.get(2).setStack(ItemStack.EMPTY);
		};

		assertEquals(3, LocalOutputTransfer.insert(fixture.index, new ItemStack(Items.DIAMOND, 3), fixture.custody));
		assertEquals(64, fixture.slots.get(1).getCount());
		assertEquals(2, fixture.slots.get(2).getCount());
		assertTrue(fixture.slots.get(2).getStack().is(Items.DIAMOND));
	}

	@Test
	void itemComponentAndCountChangesRefreshCandidatesAndOutputTotals() {
		var fixture = new Fixture(2);
		var source = named(Items.DIAMOND, 2, "accepted");
		fixture.slots.getFirst().setStack(named(Items.DIAMOND, 60, "different"));
		assertEquals(60, fixture.index.outputItemCount());
		assertEquals(2, LocalOutputTransfer.insert(fixture.index, source, fixture.custody));
		assertEquals(60, fixture.slots.getFirst().getCount());
		assertEquals("accepted", fixture.slots.get(1).getStack().getHoverName().getString());
		fixture.slots.getFirst().setStack(named(Items.DIAMOND, 60, "accepted"));
		assertEquals(2, LocalOutputTransfer.insert(fixture.index, source, fixture.custody));
		assertEquals(62, fixture.slots.getFirst().getCount());
		fixture.slots.getFirst().shrinkStack(2, Action.EXECUTE);
		assertEquals(62, fixture.index.outputItemCount());
		fixture.slots.getFirst().setStack(ItemStack.EMPTY);
		fixture.slots.get(1).setStack(ItemStack.EMPTY);
		assertFalse(fixture.index.hasOutputItems());
		assertEquals(0, fixture.index.outputItemCount());
		assertThrows(UnsupportedOperationException.class,
				() -> fixture.index.set(0, fixture.slots.getFirst()));
	}

	@Test
	void ownedSlotCapacityUsesOfferedComponentsAndLiveMultiplier() {
		var multiplier = new int[]{1};
		var fixture = new Fixture(1, multiplier);
		var request = new ItemStack(Items.DIAMOND, 10);
		request.set(DataComponents.MAX_STACK_SIZE, 16);
		fixture.slots.getFirst().setStack(request.copyWithCount(15));
		assertEquals(1, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		assertEquals(16, fixture.slots.getFirst().getCount());
		fixture.slots.getFirst().setStack(new ItemStack(Items.DIAMOND, 64));
		assertEquals(0, LocalOutputTransfer.insert(fixture.index, new ItemStack(Items.DIAMOND, 8), fixture.custody));
		multiplier[0] = 2;
		TieredInputSlot.MULTIPLIER_VERSION.incrementAndGet();
		try {
			assertEquals(8, LocalOutputTransfer.insert(fixture.index, new ItemStack(Items.DIAMOND, 8), fixture.custody));
			assertEquals(72, fixture.slots.getFirst().getCount());
		} finally {
			multiplier[0] = 1;
			TieredInputSlot.MULTIPLIER_VERSION.incrementAndGet();
		}
	}

	@Test
	void knownPrefixAndUnknownSuffixKeepOriginalCustodyBoundary() {
		var fixture = new Fixture(3);
		fixture.slots.getFirst().setStack(new ItemStack(Items.DIAMOND, 61));
		doAnswer(call -> {
			call.callRealMethod();
			throw new IllegalStateException("after local acceptance");
		}).when(fixture.slots.get(1)).insertItem(any(), eq(Action.EXECUTE), eq(AutomationType.INTERNAL));
		var request = new ItemStack(Items.DIAMOND, 10);

		assertEquals(10, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		assertEquals(64, fixture.slots.getFirst().getCount());
		assertEquals(7, fixture.slots.get(1).getCount());
		assertTrue(fixture.slots.get(2).isEmpty());
		assertTrue(fixture.custody.blocked());
		assertEquals(7, fixture.custody.amount());
		assertEquals(0, LocalOutputTransfer.insert(fixture.index, request, fixture.custody));
		assertEquals(10, request.getCount());
	}

	private static ItemStack named(Item item, int count, String name) {
		var stack = new ItemStack(item, count);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	private static final class Fixture {
		private final List<BasicInventorySlot> slots = new ArrayList<>();
		private final Runnable[] callbacks;
		private final IndexedOutputSlots index;
		private final OutputCustody custody = new OutputCustody(() -> {});

		private Fixture(int count) {
			this(count, new int[]{1});
		}

		private Fixture(int count, int[] multiplier) {
			callbacks = new Runnable[count];
			var holder = new IndexedOutputSlots[1];
			for (int i = 0; i < count; i++) {
				int slot = i;
				callbacks[i] = () -> {};
				slots.add(spy(TieredOutputInventorySlot.at(() -> multiplier[0], () -> {
					if (holder[0] != null) holder[0].contentsChanged(slot);
					callbacks[slot].run();
				}, 0, 0)));
			}
			index = new IndexedOutputSlots(slots);
			holder[0] = index;
		}
	}
}
