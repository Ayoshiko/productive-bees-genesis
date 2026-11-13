package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.inventory.TieredOutputInventorySlot;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;

/** 每个输出面的预算与精确请求拒收记录，仅服务端线程访问，随能力失效一起退役。 */
final class NeighborItemTransferState {

	private static final int REJECT_CAPACITY = 64;
	final IItemHandler target;
	final ItemEjectionBudget budget = new ItemEjectionBudget();
	int slotCursor;
	private final Map<Item, List<RejectedRequest>> rejectedByItem = new IdentityHashMap<>();
	private final ArrayDeque<RejectedRequest> rejectedOrder = new ArrayDeque<>();
	private List<IInventorySlot> sourceSlots;
	private SlotBinding[] slotBindings = new SlotBinding[0];
	private long successGeneration;
	private boolean valid = true;

	NeighborItemTransferState(IItemHandler target) {
		this.target = target;
	}

	boolean isRejected(ItemStack stack, long gameTime) {
		return isRejected(stack, stack.getCount(), gameTime);
	}

	boolean isRejected(ItemStack stack, int count, long gameTime) {
		RejectedRequest request = find(stack, count);
		return isRejected(request, gameTime);
	}

	void rememberRejected(ItemStack stack, long gameTime) {
		rememberRejected(stack, gameTime, null);
	}

	void prepareSlots(List<IInventorySlot> slots) {
		if (sourceSlots != slots || slotBindings.length != slots.size()) {
			sourceSlots = slots;
			slotBindings = new SlotBinding[slots.size()];
		}
	}

	SlotBinding binding(int index, IInventorySlot slot) {
		if (!(slot instanceof TieredOutputInventorySlot tiered) || slot.isEmpty()) {
			slotBindings[index] = null;
			return null;
		}
		SlotBinding binding = slotBindings[index];
		if (binding == null || binding.slot != tiered) {
			binding = new SlotBinding(tiered);
			slotBindings[index] = binding;
		}
		binding.refresh();
		return binding;
	}

	boolean isRejected(SlotBinding binding, long gameTime) {
		return binding != null && binding.isCurrent() && isRejected(binding.request, gameTime);
	}

	boolean isRejected(ItemStack stack, long gameTime, SlotBinding binding) {
		return isRejected(match(stack, binding), gameTime);
	}

	void rememberRejected(ItemStack stack, long gameTime, SlotBinding binding) {
		if (!valid || stack.isEmpty()) return;
		RejectedRequest request = match(stack, binding);
		if (request == null) {
			if (rejectedOrder.size() == REJECT_CAPACITY) {
				RejectedRequest oldest = rejectedOrder.removeFirst();
				oldest.live = false;
				List<RejectedRequest> bucket = rejectedByItem.get(oldest.stack.getItem());
				bucket.remove(oldest);
				if (bucket.isEmpty()) rejectedByItem.remove(oldest.stack.getItem());
			}
			request = new RejectedRequest(stack.copy());
			rejectedByItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>(1)).add(request);
			rejectedOrder.addLast(request);
		}
		request.generation = successGeneration;
		request.failedTick = gameTime;
		if (binding != null && binding.isCurrent() && binding.count == stack.getCount()) {
			binding.request = request;
		}
	}

	void recordSuccess() {
		// 接收方可能因此腾出其它类型的空间：撤销全部等待，只保留不变源槽的身份匹配。
		successGeneration++;
	}

	void invalidate() {
		valid = false;
		for (RejectedRequest request : rejectedOrder) request.live = false;
		rejectedByItem.clear();
		rejectedOrder.clear();
		sourceSlots = null;
		slotBindings = new SlotBinding[0];
	}

	boolean isValid() {
		return valid;
	}

	private boolean isRejected(RejectedRequest request, long gameTime) {
		return valid && request != null && request.live && request.generation == successGeneration
				&& gameTime == request.failedTick;
	}

	private RejectedRequest match(ItemStack stack, SlotBinding binding) {
		boolean reusable = binding != null && binding.isCurrent() && binding.count == stack.getCount();
		if (reusable && binding.request != null && binding.request.live) return binding.request;
		RejectedRequest request = find(stack, stack.getCount());
		if (reusable) binding.request = request;
		return request;
	}

	private RejectedRequest find(ItemStack stack, int count) {
		List<RejectedRequest> bucket = rejectedByItem.get(stack.getItem());
		if (bucket == null) return null;
		for (RejectedRequest request : bucket) {
			// 不把较大请求的拒收推广到小批量，保留游戏和已安装模组的组件堆叠规则。
			if (request.stack.getCount() == count
					&& ItemStack.isSameItemSameComponents(request.stack, stack)) return request;
		}
		return null;
	}

	private static final class RejectedRequest {
		private final ItemStack stack;
		private long failedTick;
		private long generation = Long.MIN_VALUE;
		private boolean live = true;

		private RejectedRequest(ItemStack stack) {
			this.stack = stack;
		}
	}

	static final class SlotBinding {
		private final TieredOutputInventorySlot slot;
		private long version;
		private long multiplierVersion;
		private ItemStack stack;
		private int count;
		private RejectedRequest request;

		private SlotBinding(TieredOutputInventorySlot slot) {
			this.slot = slot;
		}

		private void refresh() {
			if (!isCurrent()) {
				version = slot.contentsVersion();
				multiplierVersion = TieredInputSlot.MULTIPLIER_VERSION.get();
				stack = slot.getStack();
				count = stack.getCount();
				request = null;
			} else if (request != null && !request.live) {
				request = null;
			}
		}

		private boolean isCurrent() {
			return slot.contentsVersion() == version
					&& TieredInputSlot.MULTIPLIER_VERSION.get() == multiplierVersion && slot.getStack() == stack
					&& slot.getCount() == count;
		}
	}
}
