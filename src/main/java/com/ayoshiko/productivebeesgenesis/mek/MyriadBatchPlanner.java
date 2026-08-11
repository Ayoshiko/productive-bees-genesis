package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * 万象创世产物批量插入规划器：将按 bee_type 分配的数量转换为槽位索引执行计划，
	 * 避免传统 insertItem 路径的 copy/组件派生/listener 扫描开销。
	 * <p>
	 * 设计原则：单一职责、零模拟副本、对象池化、Snapshot 跨 tick 缓存。
	 * 线程安全：plan/apply 主线程执行，对象池无需并发安全；Snapshot 缓存用 ThreadLocal 隔离。
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

	/**
	 * bee_type 模板缓存（按 baseItem + beeType）。高倍加速下高频创建仅 bee_type 不同的模板，
	 * 用静态有界缓存避免重复构造与组件派生。模板仅用于 {@link #apply} 的 copyWithCount，不修改。
	 */
	private static final int TEMPLATE_CACHE_CAPACITY = 512;
	private static final Map<TemplateKey, ItemStack> TEMPLATE_CACHE = new ConcurrentHashMap<>(TEMPLATE_CACHE_CAPACITY);

	private record TemplateKey(Item item, ResourceLocation beeType) {
	}

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

	/** 批量插入计划（可复用）。{@link #FAILURE} 失败单例不归还；成功 Plan 在 apply 后自动回收 */
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
	 * 拍摄输出槽容量快照（带跨 tick 缓存）。同 tick+同一 slots 直接返回缓存；snapshot 只读可跨工厂复用。
	 * <br/>
	 * 空槽容量用 {@link IInventorySlot#getLimit(ItemStack)}，异常/非正回退到 {@link Item#getMaxStackSize(ItemStack)}；
	 * 非空槽用 {@code slot.getLimit(stack) - stack.getCount()}。
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

	/** 规划批量插入（兼容旧签名：内部自动拍快照）。传 tick 保证与批量路径快照缓存键一致以跨路径复用 */
	@NotNull
	public static Plan plan(List<IInventorySlot> slots, Item baseItem,
							Map<ResourceLocation, Integer> allocation, long tick) {
		return plan(takeSnapshot(slots, baseItem, tick), baseItem, allocation);
	}

	/** 基于快照规划批量插入。优先级：1) 同 bee_type 槽（grow）；2) 空槽（setStack）。放不下返回 {@link Plan#failure()} */
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

	/** 计算输出槽能容纳的最大输入数量（二分搜索）。据容量与 multiplier 定上界；每次迭代回收 Plan 防泄漏 */
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
			int totalCount = SaturatingMath.saturatingToInt(
					SaturatingMath.saturatingMultiply(mid, multiplier));
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
	 * 执行插入计划，使用后自动归还对象池。调用后 plan 不可再用，FAILURE 不归还。
	 * <br/>
	 * 应在 beginOutputBatch/endOutputBatch 之间或基础机等效批量包装内调用，避免每次插入触发 listener 扫描。
	 * <p>
	 * <b>Task 4 关键修复：</b>原实现 {@code slot.getStack().grow(n)} 违反 IInventorySlot 契约
	 * （IInventorySlot.java 明确禁止修改 getStack 返回的 ItemStack），导致 onContentsChanged 不被调用
	 * → outputBatchDirty 不被设置 → endOutputBatch 不更新标志位 → 后续 tick 无效重试。
	 * 改用 {@link IInventorySlot#growStack}（default 方法，内部走 setStack → onContentsChanged）。
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
					// Task 4 修复：growStack 触发 listener，替代直接修改 getStack 返回值
					slot.growStack(slotPlan.amount(), Action.EXECUTE);
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

	/** 创建带 bee_type 的模板（不设置 count），优先从静态缓存读取 */
	private static ItemStack createTemplate(Item baseItem, ResourceLocation beeType) {
		TemplateKey key = new TemplateKey(baseItem, beeType);
		ItemStack cached = TEMPLATE_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		ItemStack template = new ItemStack(baseItem);
		template.set(ModDataComponents.BEE_TYPE.get(), beeType);
		// 有界缓存：超过容量时直接丢弃，由 GC 回收
		if (TEMPLATE_CACHE.size() < TEMPLATE_CACHE_CAPACITY) {
			TEMPLATE_CACHE.put(key, template);
		}
		return template;
	}

	/** 清空模板缓存（配置重载/数据包变更时调用），防止 bee_type 变化后旧模板残留导致泄漏 */
	public static void clearTemplateCache() {
		TEMPLATE_CACHE.clear();
	}

	/**
	 * 清理 ThreadLocal 快照缓存（服务端停止时调用，防止线程池复用场景下的引用残留）
	 */
	public static void clearThreadLocals() {
		snapshotCache.remove();
	}
}
