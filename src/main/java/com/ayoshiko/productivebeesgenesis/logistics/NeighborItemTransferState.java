package com.ayoshiko.productivebeesgenesis.logistics;

import java.util.Arrays;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 每个输出面的预算与本刻拒收记录，目标替换后由弹出器整体重建。仅服务端线程访问。 */
final class NeighborItemTransferState {

	private static final int REJECT_CAPACITY = 64;
	final IItemHandler target;
	final ItemEjectionBudget budget = new ItemEjectionBudget();
	int slotCursor;
	private final ItemStack[] rejected = new ItemStack[REJECT_CAPACITY];
	private int rejectedCount;
	private long rejectedTick = Long.MIN_VALUE;

	NeighborItemTransferState(IItemHandler target) {
		this.target = target;
	}

	boolean isRejected(ItemStack stack, long gameTime) {
		return isRejected(stack, stack.getCount(), gameTime);
	}

	boolean isRejected(ItemStack stack, int count, long gameTime) {
		refresh(gameTime);
		for (int i = 0; i < rejectedCount; i++) {
			// 大批量拒收不代表减半后的批量也会被拒收。
			if (rejected[i].getCount() == count
					&& ItemStack.isSameItemSameComponents(rejected[i], stack)) return true;
		}
		return false;
	}

	void rememberRejected(ItemStack stack, long gameTime) {
		refresh(gameTime);
		if (rejectedCount < REJECT_CAPACITY) rejected[rejectedCount++] = stack.copy();
	}

	private void refresh(long gameTime) {
		if (rejectedTick == gameTime) return;
		rejectedTick = gameTime;
		Arrays.fill(rejected, 0, rejectedCount, null);
		rejectedCount = 0;
	}
}
