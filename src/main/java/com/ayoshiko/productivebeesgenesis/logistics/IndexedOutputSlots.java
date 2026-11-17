package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import java.util.AbstractList;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** 固定自有输出槽的只读视图；物理槽监听标记变更，同一世界主线程内保序查询候选。 */
public final class IndexedOutputSlots extends AbstractList<BasicInventorySlot> implements RandomAccess {

	private final List<BasicInventorySlot> slots;
	private final Map<Item, BitSet> slotsByItem = new IdentityHashMap<>();
	private final BitSet emptySlots;
	private final BitSet changedSlots;
	private final BitSet unreadableSlots;
	private final Item[] indexedItems;
	private final int[] indexedCounts;
	private int nonEmptySlots;
	private long itemCount;

	/** 宿主必须在每个槽的内容监听器中调用 {@link #contentsChanged(int)}，并先于其它回调标记。 */
	public IndexedOutputSlots(List<BasicInventorySlot> slots) {
		this.slots = List.copyOf(slots);
		for (BasicInventorySlot slot : this.slots) {
			if (!(slot instanceof TieredOutputInventorySlot)) {
				throw new IllegalArgumentException("Only owned tiered output slots can be indexed");
			}
		}
		emptySlots = new BitSet(slots.size());
		changedSlots = new BitSet(slots.size());
		unreadableSlots = new BitSet(slots.size());
		changedSlots.set(0, slots.size());
		indexedItems = new Item[slots.size()];
		indexedCounts = new int[slots.size()];
	}

	@Override
	public BasicInventorySlot get(int index) {
		return slots.get(index);
	}

	@Override
	public int size() {
		return slots.size();
	}

	public void contentsChanged(int index) {
		if (index < 0 || index >= slots.size()) throw new IndexOutOfBoundsException(index);
		changedSlots.set(index);
	}

	public boolean hasOutputItems() {
		refreshChangedSlots();
		return nonEmptySlots > 0;
	}

	public long outputItemCount() {
		refreshChangedSlots();
		return itemCount;
	}

	int nextMatchingSlot(Item item, int fromIndex) {
		refreshChangedSlots();
		BitSet matching = slotsByItem.get(item);
		return matching == null ? -1 : nextReadableCandidate(matching, fromIndex);
	}

	int nextEmptySlot(int fromIndex) {
		refreshChangedSlots();
		return nextReadableCandidate(emptySlots, fromIndex);
	}

    private int nextReadableCandidate(BitSet candidates, int fromIndex) {
        int candidate = candidates.nextSetBit(fromIndex);
        while (candidate >= 0 && unreadableSlots.get(candidate)) candidate = candidates.nextSetBit(candidate + 1);
        return candidate;
    }

	private void refreshChangedSlots() {
        for (int index = changedSlots.nextSetBit(0); index >= 0; index = changedSlots.nextSetBit(index + 1)) {
            ItemStack stack;
            try {
                stack = slots.get(index).getStack();
            } catch (RuntimeException | LinkageError error) {
                if (!unreadableSlots.get(index)) {
                    LogThrottle.error("local_output_slot_index_failure",
                            "Local output slot could not be indexed; other slots remain available: slot={}", index, error);
                }
                unreadableSlots.set(index);
                // 保留原索引与脏标记；未知位置不作为空槽，后续查询继续恢复读取。
                continue;
            }
			Item item = stack.isEmpty() ? null : stack.getItem();
			Item previous = indexedItems[index];
			if (item != previous) {
				if (previous != null) {
					BitSet oldSlots = slotsByItem.get(previous);
					oldSlots.clear(index);
					if (oldSlots.isEmpty()) slotsByItem.remove(previous);
					nonEmptySlots--;
				}
				if (item != null) {
					slotsByItem.computeIfAbsent(item, ignored -> new BitSet(slots.size())).set(index);
					nonEmptySlots++;
				}
				indexedItems[index] = item;
			}
			emptySlots.set(index, item == null);
			int count = item == null ? 0 : stack.getCount();
			itemCount += (long) count - indexedCounts[index];
			indexedCounts[index] = count;
			changedSlots.clear(index);
            if (unreadableSlots.get(index)) {
                unreadableSlots.clear(index);
                LogThrottle.infoWithCooldown("local_output_slot_index_recovered", 60_000L,
                        "Local output slot became readable again: slot={}", index);
            }
		}
	}
}
