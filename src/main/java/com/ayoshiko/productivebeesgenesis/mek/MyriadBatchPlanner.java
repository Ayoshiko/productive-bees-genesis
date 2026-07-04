package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 万象创世产物批量插入规划器
 * <br/>
 * 将“按 bee_type 分配的数量”转换为“槽位索引 → 增长数量/是否空槽/模板”的执行计划，
 * 避免传统 insertItem 路径中反复的 ItemStack.copy()、DataComponentMap 派生与 listener 扫描。
 * <p>
 * 设计原则：单一职责、零模拟副本、对象池化、Snapshot 跨 tick 缓存、直接操作。
 * <br/>
 * 线程安全：plan 与 apply 在服务端主线程执行，对象池无需并发安全；
 * Snapshot 缓存使用 ThreadLocal 避免跨线程污染。
 */
public final class MyriadBatchPlanner {

	/** 对象池上限：超过则丢弃由 GC 回收 */
	private static final int POOL_CAPACITY = 256;

	/** SlotPlan 对象池（主线程使用，无需并发安全） */
	private static final Deque<SlotPlan> slotPlanPool = new ArrayDeque<>(POOL_CAPACITY);

	/** Plan 对象池 */
	private static final Deque<Plan> planPool = new ArrayDeque<>(POOL_CAPACITY);

	/** Snapshot 跨 tick 缓存：按 tick + slots identity 复用 */
	private static final ThreadLocal<SnapshotCache> snapshotCache = ThreadLocal.withInitial(SnapshotCache::new);

	private MyriadBatchPlanner() {
	}

	// ===== SlotPlan：可复用的槽位计划 =====

	/** 单个槽位的执行计划（可复用，访问器风格保留 record 语义） */
	public static final class SlotPlan {
		private int slotIndex;
		private int amount;
		private boolean wasEmpty;
		@Nullable
		private ItemStack template;

		private SlotPlan() {
		}

		public int slotIndex() {
			return slotIndex;
		}

		public int amount() {
			return amount;
		}

		public boolean wasEmpty() {
			return wasEmpty;
		}

		@Nullable
		public ItemStack template() {
			return template;
		}

		private void set(int slotIndex, int amount, boolean wasEmpty, @Nullable ItemStack template) {
			this.slotIndex = slotIndex;
			this.amount = amount;
			this.wasEmpty = wasEmpty;
			this.template = template;
		}

		/** 清理引用，避免内存泄漏 */
		private void reset() {
			slotIndex = 0;
			amount = 0;
			wasEmpty = false;
			template = null;
		}
	}

	// ===== Plan：可复用的批量插入计划 =====

	/**
	 * 批量插入计划（可复用）
	 * <br/>
	 * {@link #FAILURE} 为失败单例，不归还池中；
	 * 成功的 Plan 在 {@link #apply(Plan, List)} 后由内部自动回收。
	 */
	public static final class Plan {
		/** 失败单例（不归还） */
		private static final Plan FAILURE = new Plan(null);

		@Nullable
		private List<SlotPlan> plans;

		private Plan(@Nullable List<SlotPlan> plans) {
			this.plans = plans;
		}

		public boolean isSuccess() {
			return plans != null;
		}

		@Nullable
		public List<SlotPlan> getPlans() {
			return plans;
		}

		private void reset(@Nullable List<SlotPlan> plans) {
			this.plans = plans;
		}

		/** 回收：归还 plans 中所有 SlotPlan，再归还自身 */
		private void recycle() {
			List<SlotPlan> p = plans;
			if (p != null) {
				for (SlotPlan sp : p) {
					returnSlotPlan(sp);
				}
				p.clear();
				plans = null;
			}
			returnPlan(this);
		}

		public static Plan success(List<SlotPlan> plans) {
			Plan plan = borrowPlan();
			plan.reset(plans);
			return plan;
		}

		public static Plan failure() {
			return FAILURE;
		}
	}

	// ===== 对象池 API =====

	/** 借用 SlotPlan，池空则新建（不阻塞） */
	private static SlotPlan borrowSlotPlan() {
		SlotPlan sp = slotPlanPool.pollLast();
		return sp != null ? sp : new SlotPlan();
	}

	/** 归还 SlotPlan，清理引用；超上限则丢弃由 GC 回收 */
	private static void returnSlotPlan(@Nullable SlotPlan plan) {
		if (plan == null) return;
		plan.reset();
		if (slotPlanPool.size() < POOL_CAPACITY) {
			slotPlanPool.offerLast(plan);
		}
	}

	/** 借用 Plan，池空则新建 */
	private static Plan borrowPlan() {
		Plan p = planPool.pollLast();
		return p != null ? p : new Plan(null);
	}

	/** 归还 Plan，清理引用 */
	private static void returnPlan(@Nullable Plan plan) {
		if (plan == null) return;
		plan.reset(null);
		if (planPool.size() < POOL_CAPACITY) {
			planPool.offerLast(plan);
		}
	}

	/** 回收 Plan：仅非 FAILURE 实例回收，供调用方在未走 apply 路径时手动回收 */
	public static void recyclePlan(@Nullable Plan plan) {
		if (plan == null || !plan.isSuccess()) return;
		plan.recycle();
	}

	// ===== Snapshot 缓存与拍摄 =====

	/** Snapshot 缓存条目：按 tick + slots identity 复用 */
	private static final class SnapshotCache {
		private long tick = -1L;
		private int slotsIdentity = 0;
		@Nullable
		private SlotCapacitySnapshot snapshot;
	}

	/**
	 * 槽位容量快照（只读）
	 * <br/>
	 * 一次性读取每个输出槽的 limit/count/bee_type，避免批量规划过程中反复调用
	 * {@link IInventorySlot#getLimit(ItemStack)} 等可能开销较大的方法。
	 * 同一 tick 内同一 slots 实例的 limit 不变，因此一次 complete 调用只需拍一张快照。
	 */
	public static final class SlotCapacitySnapshot {
		public final long tick;
		public final int slotCount;
		public final boolean[] empty;
		public final ResourceLocation[] slotBeeTypes;
		public final int[] slotCounts;
		public final int[] slotLimits;
		/** 可用于万象产物的剩余总容量（仅统计空槽与可配置蜜脾/蜜脾块槽） */
		public final long totalRemainingCapacity;

		private SlotCapacitySnapshot(long tick, int slotCount, boolean[] empty,
									 ResourceLocation[] slotBeeTypes, int[] slotCounts,
									 int[] slotLimits, long totalRemainingCapacity) {
			this.tick = tick;
			this.slotCount = slotCount;
			this.empty = empty;
			this.slotBeeTypes = slotBeeTypes;
			this.slotCounts = slotCounts;
			this.slotLimits = slotLimits;
			this.totalRemainingCapacity = totalRemainingCapacity;
		}
	}

	/**
	 * 拍摄输出槽容量快照（带跨 tick 缓存）
	 * <br/>
	 * 同 tick 内同一 slots 实例的多次调用直接返回缓存，避免重复拍摄；
	 * 不同 slots 或 tick 变化时重新拍摄并缓存。snapshot 只读，跨工厂复用安全。
	 * <br/>
	 * 空槽容量优先使用 {@link IInventorySlot#getLimit(ItemStack)}（传入基础物品模板），
	 * 若调用异常或返回非正数则回退到 {@link Item#getMaxStackSize(ItemStack)}；
	 * 非空槽容量使用 {@code slot.getLimit(stack) - stack.getCount()}。
	 */
	@NotNull
	public static SlotCapacitySnapshot takeSnapshot(List<IInventorySlot> slots, Item baseItem, long tick) {
		SnapshotCache cache = snapshotCache.get();
		int identity = System.identityHashCode(slots);
		if (cache.snapshot != null && cache.tick == tick && cache.slotsIdentity == identity) {
			return cache.snapshot;
		}
		SlotCapacitySnapshot snapshot = doTakeSnapshot(slots, baseItem, tick);
		cache.tick = tick;
		cache.slotsIdentity = identity;
		cache.snapshot = snapshot;
		return snapshot;
	}

	/** 实际拍摄快照（无缓存） */
	private static SlotCapacitySnapshot doTakeSnapshot(List<IInventorySlot> slots, Item baseItem, long tick) {
		int slotCount = slots.size();
		boolean[] empty = new boolean[slotCount];
		ResourceLocation[] slotBeeTypes = new ResourceLocation[slotCount];
		int[] slotCounts = new int[slotCount];
		int[] slotLimits = new int[slotCount];
		long totalRemainingCapacity = 0L;

		// 空槽模板： bee_type 不影响槽位 limit，统一用一个无 bee_type 模板
		ItemStack emptyTemplate = new ItemStack(baseItem);

		for (int i = 0; i < slotCount; i++) {
			IInventorySlot slot = slots.get(i);
			if (slot == null) {
				slotLimits[i] = 0;
				continue;
			}
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				empty[i] = true;
				int limit = safeGetSlotLimit(slot, emptyTemplate);
				if (limit <= 0) {
					// 回退到基础物品模板的默认最大堆叠数，避免 ItemStack.EMPTY 无 bee_type 组件导致 maxStack 计算错误
					limit = emptyTemplate.getMaxStackSize();
				}
				slotLimits[i] = limit;
				totalRemainingCapacity += limit;
			} else {
				empty[i] = false;
				int count = stack.getCount();
				slotCounts[i] = count;
				int limit = safeGetSlotLimit(slot, stack);
				if (limit < count) {
					// 防御性：limit 不应小于当前数量，若出现则按当前数量处理（剩余 0）
					limit = count;
				}
				slotLimits[i] = limit;
				Item item = stack.getItem();
				if (item == ModItems.CONFIGURABLE_HONEYCOMB.get() || item == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
					slotBeeTypes[i] = stack.get(ModDataComponents.BEE_TYPE.get());
					totalRemainingCapacity += (long) limit - count;
				}
			}
		}

		return new SlotCapacitySnapshot(tick, slotCount, empty, slotBeeTypes,
				slotCounts, slotLimits, totalRemainingCapacity);
	}

	private static int safeGetSlotLimit(IInventorySlot slot, ItemStack stack) {
		try {
			return slot.getLimit(stack);
		} catch (Exception e) {
			// 某些槽位实现可能因组件派生抛出异常，回退到物品自身上限保证稳定性
			return stack.getItem().getMaxStackSize(stack);
		}
	}

	// ===== 规划 =====

	/** 规划批量插入（兼容旧签名：内部自动拍摄快照） */
	@NotNull
	public static Plan plan(List<IInventorySlot> slots, Item baseItem,
							Map<ResourceLocation, Integer> allocation) {
		return plan(takeSnapshot(slots, baseItem, -1L), baseItem, allocation);
	}

	/**
	 * 基于容量快照规划批量插入
	 * <br/>
	 * 优先级：1) 已有相同 bee_type 的槽位（grow 路径）；2) 空槽（setStack 路径）。
	 * 任一 bee_type 无法完整放下时返回 {@link Plan#failure()}，保证原子性。
	 */
	@NotNull
	public static Plan plan(SlotCapacitySnapshot snapshot, Item baseItem,
							Map<ResourceLocation, Integer> allocation) {
		int slotCount = snapshot.slotCount;
		int[] addAmounts = new int[slotCount];
		boolean[] wasEmpty = new boolean[slotCount];
		ItemStack[] templates = new ItemStack[slotCount];

		for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
			ResourceLocation beeType = entry.getKey();
			int remaining = entry.getValue();
			if (remaining <= 0) {
				continue;
			}

			// 第一优先级：已有同类型槽位（grow 路径，不需要 template）
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				if (snapshot.empty[i] || snapshot.slotBeeTypes[i] == null
						|| !snapshot.slotBeeTypes[i].equals(beeType)) {
					continue;
				}
				int space = snapshot.slotLimits[i] - snapshot.slotCounts[i];
				if (space <= 0) {
					continue;
				}
				int add = Math.min(space, remaining);
				addAmounts[i] += add;
				wasEmpty[i] = false;
				remaining -= add;
			}

			// 第二优先级：空槽
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				if (!snapshot.empty[i]) {
					continue;
				}
				int space = snapshot.slotLimits[i];
				if (space <= 0) {
					continue;
				}
				int add = Math.min(space, remaining);
				addAmounts[i] += add;
				wasEmpty[i] = true;
				remaining -= add;
				if (templates[i] == null) {
					templates[i] = createTemplate(baseItem, beeType);
				}
			}

			// 仍有剩余说明物理上放不下
			if (remaining > 0) {
				return Plan.failure();
			}
		}

		// 构建可复用 SlotPlan 列表：从对象池借用
		List<SlotPlan> plans = new ArrayList<>(slotCount);
		for (int i = 0; i < slotCount; i++) {
			if (addAmounts[i] > 0) {
				SlotPlan sp = borrowSlotPlan();
				sp.set(i, addAmounts[i], wasEmpty[i], templates[i]);
				plans.add(sp);
			}
		}
		return Plan.success(plans);
	}

	/**
	 * 计算输出槽能容纳的最大输入数量（二分搜索）
	 * <br/>
	 * 根据 {@code totalRemainingCapacity} 与 {@code multiplier} 确定上界，二分查找最大可行 batch size。
	 * 内部循环产生的 Plan 在每次迭代后回收，避免对象泄漏。
	 */
	public static int planOrFindMaxBatch(SlotCapacitySnapshot snapshot, Item baseItem, int multiplier,
										 List<ResourceLocation> selectedTypes, int maxRequested) {
		if (snapshot == null || baseItem == null || selectedTypes == null || selectedTypes.isEmpty()
				|| maxRequested <= 0 || multiplier <= 0) {
			return 0;
		}

		long maxByCapacity = snapshot.totalRemainingCapacity / multiplier;
		int high = (int) Math.min(maxByCapacity, maxRequested);
		if (high <= 0) {
			return 0;
		}

		int best = 0;
		int low = 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			int totalCount = mid * multiplier;
			// 类型数不超过总数量（避免 allocateEvenly 出现大量 0 分配），也不超过 3 种
			int typesToUse = Math.min(selectedTypes.size(), Math.max(1, Math.min(totalCount, 3)));
			Map<ResourceLocation, Integer> allocation = RandomHoneycombSelector.allocateEvenly(
					totalCount, selectedTypes.subList(0, typesToUse));

			Plan plan = plan(snapshot, baseItem, allocation);
			try {
				if (plan.isSuccess()) {
					best = mid;
					low = mid + 1;
				} else {
					high = mid - 1;
				}
			} finally {
				// 二分搜索每次迭代的 plan 必须回收，否则会随迭代次数泄漏
				recyclePlan(plan);
			}
		}
		return best;
	}

	// ===== 执行 =====

	/**
	 * 执行插入计划，使用后自动归还对象池
	 * <br/>
	 * 调用后 plan 不可再使用（已归还池中）。FAILURE 单例不归还。
	 * 应在 {@code productivebeesgenesis$beginOutputBatch()} /
	 * {@code productivebeesgenesis$endOutputBatch(int)} 之间调用，
	 * 或基础机的等效批量包装内调用，以避免每次插入都触发 listener 扫描。
	 */
	public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots) {
		if (!plan.isSuccess()) {
			return;
		}
		try {
			List<SlotPlan> plans = plan.getPlans();
			if (plans == null) {
				return;
			}
			for (SlotPlan slotPlan : plans) {
				IInventorySlot slot = slots.get(slotPlan.slotIndex());
				if (slot == null) {
					continue;
				}
				if (slotPlan.wasEmpty()) {
					ItemStack toSet = slotPlan.template().copyWithCount(slotPlan.amount());
					slot.setStack(toSet);
				} else {
					slot.getStack().grow(slotPlan.amount());
				}
			}
		} finally {
			// 借用/归还路径在 try/finally 中执行：成功 Plan 与其中 SlotPlan 一并归还
			recyclePlan(plan);
		}
	}

	/** 执行插入计划（重载：指定 begin/end 回调），供基础机使用 */
	public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots,
							 @NotNull Runnable beginBatch, @NotNull Runnable endBatch) {
		beginBatch.run();
		try {
			apply(plan, slots);
		} finally {
			endBatch.run();
		}
	}

	/** 创建带 bee_type 的模板（不设置 count） */
	private static ItemStack createTemplate(Item baseItem, ResourceLocation beeType) {
		ItemStack template = new ItemStack(baseItem);
		template.set(ModDataComponents.BEE_TYPE.get(), beeType);
		return template;
	}
}
