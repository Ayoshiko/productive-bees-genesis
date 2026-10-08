package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import java.util.List;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2InputCapacityMinecraftTest {
	@Test
	void emptyLanesPlanOnlyWhatTheActualCandidateCanInsert() throws Exception {
		for (int multiplier : new int[] {1, 4, 4096}) {
			for (Item item : List.of(Items.IRON_INGOT, Items.ENDER_PEARL, Items.IRON_SWORD)) {
				var slot = BasicInventorySlot.at(null, 0, 0);
				((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> multiplier);
				assertCapacityMatchesInsertion(slot, new ItemStack(item));
			}
		}
	}

	@Test
	void modifiedStackLimitDoesNotReuseAnotherComponentVariantsCapacity() throws Exception {
		var slot = BasicInventorySlot.at(null, 0, 0);
		((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> 4);
		var regular = new ItemStack(Items.HONEYCOMB);
		var smaller = regular.copy();
		smaller.set(DataComponents.MAX_STACK_SIZE, 16);
		// 查询顺序也覆盖槽位缓存：相同 Item 的组件变化必须改变真实上限。
		assertEquals(256, slot.getLimit(regular));
		assertEquals(64, slot.getLimit(smaller));
		assertCapacityMatchesInsertion(slot, smaller);
		assertEquals(256, slot.getLimit(regular));
	}

	private static void assertCapacityMatchesInsertion(BasicInventorySlot slot, ItemStack template) throws Exception {
		var key = AEItemKey.of(template);
		var entry = new Ae2InputPuller.PullEntry(key, Integer.MAX_VALUE);
		entry.beginComponentMatchCache(1);
		var snapshot = new Ae2InputLaneSnapshot();
		snapshot.capture(List.of(slot), 1);
		var lane = Ae2InputPuller.class.getDeclaredMethod("laneCapacity", Ae2InputLaneSnapshot.class,
				int.class, Item.class, Ae2InputPuller.PullEntry.class, ItemStack.class);
		lane.setAccessible(true);
		var fallback = Ae2InputPuller.class.getDeclaredMethod("getSlotRemainingCapacity", IInventorySlot.class,
				int.class, Ae2InputPuller.PullEntry.class, ItemStack.class);
		fallback.setAccessible(true);
		int requested = 1_000_000;
		int actual = requested - slot.insertItem(template.copyWithCount(requested),
				Action.SIMULATE, AutomationType.INTERNAL).getCount();
		var aggregate = Ae2InputPuller.class.getDeclaredMethod("calculateInputCapacity", List.class);
		aggregate.setAccessible(true);
		assertTrue((long) aggregate.invoke(null, List.of(slot)) >= actual,
				"the pre-candidate budget must not cap a valid batch at the empty stack's limit");
		assertEquals(actual, (long) lane.invoke(null, snapshot, 0, template.getItem(), entry, template));
		assertEquals(actual, (long) fallback.invoke(null, slot, 0, entry, template));
		assertTrue(slot.isEmpty());
	}
}
