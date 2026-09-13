package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
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
import java.util.function.BiConsumer;

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

	private record TemplateKey(Item item, ResourceLocation beeType, Item outputItem) {
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
		// The handler reuses one mutable list across processes, so list identity alone
		// is insufficient. Retain the element identities captured with the snapshot.
		private IInventorySlot[] slotIdentities;
		private Item baseItem;
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
		public final ItemStack[] slotTemplates;
		public final int[] slotCounts;
		public final int[] slotLimits;
		/** 可用于万象产物的剩余总容量（仅统计空槽与候选模板兼容槽） */
		public final long totalRemainingCapacity;

		private SlotCapacitySnapshot(long tick, int slotCount, boolean[] empty,
				ItemStack[] slotTemplates, int[] slotCounts,
				int[] slotLimits, long totalRemainingCapacity) {
			this.tick = tick;
			this.slotCount = slotCount;
			this.empty = empty;
			this.slotTemplates = slotTemplates;
			this.slotCounts = slotCounts;
			this.slotLimits = slotLimits;
			this.totalRemainingCapacity = totalRemainingCapacity;
		}
	}

	/**
	 * 拍摄输出槽容量快照（带跨 tick 缓存）。同 tick+同一槽位元素身份直接返回缓存；snapshot 只读可跨工厂复用。
	 * <br/>
	 * 空槽容量用 {@link IInventorySlot#getLimit(ItemStack)}，异常/非正回退到 {@link Item#getMaxStackSize(ItemStack)}；
	 * 非空槽用 {@code slot.getLimit(stack) - stack.getCount()}。
	 */
	@NotNull
	public static SlotCapacitySnapshot takeSnapshot(List<IInventorySlot> slots, Item baseItem, long tick) {
		SnapshotCache cache = snapshotCache.get();
		if (cache.snapshot != null && cache.tick == tick && cache.baseItem == baseItem
				&& sameSlotIdentities(cache.slotIdentities, slots)) {
			return cache.snapshot;
		}
		SlotCapacitySnapshot snapshot = doTakeSnapshot(slots, baseItem, tick);
		cache.tick = tick;
		cache.slotIdentities = slots.toArray(new IInventorySlot[0]);
		cache.baseItem = baseItem;
		cache.snapshot = snapshot;
		return snapshot;
	}

	private static boolean sameSlotIdentities(@Nullable IInventorySlot[] cached,
			List<IInventorySlot> current) {
		if (cached == null || cached.length != current.size()) return false;
		for (int i = 0; i < cached.length; i++) {
			if (cached[i] != current.get(i)) return false;
		}
		return true;
	}

	/** 实际拍摄快照（无缓存） */
	private static SlotCapacitySnapshot doTakeSnapshot(List<IInventorySlot> slots, Item baseItem, long tick) {
		int slotCount = slots.size();
		boolean[] empty = new boolean[slotCount];
		ItemStack[] slotTemplates = new ItemStack[slotCount];
		int[] slotCounts = new int[slotCount];
		int[] slotLimits = new int[slotCount];
		long totalRemainingCapacity = 0L;

		// 空槽模板仅用于取得基础槽位上限；实际候选模板在 plan 阶段再精确校验。
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
				if (limit <= 0) limit = emptyTemplate.getMaxStackSize();
				slotLimits[i] = limit;
				totalRemainingCapacity += limit;
			} else {
				empty[i] = false;
				slotTemplates[i] = stack.copyWithCount(1);
				int count = stack.getCount();
				slotCounts[i] = count;
				int limit = safeGetSlotLimit(slot, stack);
				if (limit < count) limit = count;
				slotLimits[i] = limit;
				if (stack.getItem() == baseItem || stack.getItem() instanceof net.minecraft.world.item.HoneycombItem) {
					totalRemainingCapacity += (long) limit - count;
				}
			}
		}

		return new SlotCapacitySnapshot(tick, slotCount, empty, slotTemplates,
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

	/** 规划批量插入（兼容旧签名：使用可配置蜜脾默认模板）。 */
	@NotNull
	public static Plan plan(List<IInventorySlot> slots, Item baseItem,
							Map<ResourceLocation, Integer> allocation, long tick) {
		return plan(takeSnapshot(slots, baseItem, tick), baseItem, allocation, Map.of());
	}

	/** 规划批量插入，并使用缓存中的真实蜜脾模板。 */
	@NotNull
	public static Plan plan(List<IInventorySlot> slots, Item baseItem,
			Map<ResourceLocation, Integer> allocation, long tick,
			Map<ResourceLocation, ItemStack> templateByType) {
		return plan(takeSnapshot(slots, baseItem, tick), baseItem, allocation, templateByType);
	}

	/** 基于快照规划批量插入。优先级：1) 相同物品及组件槽；2) 空槽。 */
	@NotNull
	public static Plan plan(SlotCapacitySnapshot snapshot, Item baseItem,
							Map<ResourceLocation, Integer> allocation) {
		return plan(snapshot, baseItem, allocation, Map.of());
	}

	/** 基于快照和真实蜜脾模板规划批量插入。 */
	@NotNull
	public static Plan plan(SlotCapacitySnapshot snapshot, Item baseItem,
			Map<ResourceLocation, Integer> allocation,
			Map<ResourceLocation, ItemStack> templateByType) {
		int slotCount = snapshot.slotCount;
		int[] addAmounts = new int[slotCount];
		boolean[] wasEmpty = new boolean[slotCount];
		ItemStack[] templates = new ItemStack[slotCount];
		boolean[] availableAsEmpty = snapshot.empty.clone();
		ItemStack[] workingTemplates = snapshot.slotTemplates.clone();
		int[] workingCounts = snapshot.slotCounts.clone();

		for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
			ResourceLocation beeType = entry.getKey();
			int remaining = entry.getValue();
			if (remaining <= 0) continue;
			ItemStack outputTemplate = resolveTemplate(baseItem, beeType, templateByType);

			// 第一优先级：已有相同物品及组件的槽位。
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				if (availableAsEmpty[i] || workingTemplates[i] == null
						|| !ItemStack.isSameItemSameComponents(workingTemplates[i], outputTemplate)) continue;
				int space = snapshot.slotLimits[i] - workingCounts[i];
				if (space <= 0) continue;
				int add = Math.min(space, remaining);
				addAmounts[i] += add;
				wasEmpty[i] = false;
				workingCounts[i] += add;
				remaining -= add;
			}

			// 第二优先级：空槽；上限必须按真实模板重新计算。
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				if (!availableAsEmpty[i]) continue;
				int limit = snapshot.slotLimits[i];
				int space = limit - workingCounts[i];
				if (space <= 0) continue;
				int add = Math.min(space, remaining);
				addAmounts[i] += add;
				wasEmpty[i] = true;
				availableAsEmpty[i] = false;
				workingTemplates[i] = outputTemplate;
				workingCounts[i] = add;
				remaining -= add;
				if (templates[i] == null) templates[i] = outputTemplate;
			}
			if (remaining > 0) return Plan.failure();
		}

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

	/** 计算输出槽能容纳的最大输入数量；预检与正式计划必须使用同一真实模板映射。 */
	public static int planOrFindMaxBatch(SlotCapacitySnapshot snapshot, Item baseItem, int multiplier,
			List<ResourceLocation> selectedTypes, int maxRequested,
			Map<ResourceLocation, ItemStack> templateByType) {
		if (snapshot == null || baseItem == null || selectedTypes == null || selectedTypes.isEmpty()
				|| maxRequested <= 0 || multiplier <= 0) {
			return 0;
		}

		int high = maxRequested;
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

			Plan plan = plan(snapshot, baseItem, allocation, templateByType);
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
		apply(plan, slots, () -> {});
	}

	/** Executes a plan and declares each manager-owned slot listener callback. */
	public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots,
			@NotNull Runnable beforeSlotMutation) {
		apply(plan, slots, beforeSlotMutation, (slotIndex, slot) -> {});
	}

	/**
	 * Executes a plan and reports each successful slot mutation after the inventory
	 * listener has been notified. The slot index is the index in {@code slots}; the
	 * caller can map it to a physical machine slot when the list omits null slots.
	 */
	public static void apply(@NotNull Plan plan, @NotNull List<IInventorySlot> slots,
			@NotNull Runnable beforeSlotMutation,
			@NotNull BiConsumer<Integer, IInventorySlot> afterSlotMutation) {
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
					beforeSlotMutation.run();
					slot.setStack(toSet);
					afterSlotMutation.accept(slotPlan.slotIndex(), slot);
				} else {
					// Task 4 修复：growStack 触发 listener，替代直接修改 getStack 返回值
					beforeSlotMutation.run();
					slot.growStack(slotPlan.amount(), Action.EXECUTE);
					afterSlotMutation.accept(slotPlan.slotIndex(), slot);
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

	/** 创建实际产物模板；模板快照缺失时回退到带 bee_type 的可配置蜜脾。 */
	private static ItemStack resolveTemplate(Item baseItem, ResourceLocation beeType,
			Map<ResourceLocation, ItemStack> templateByType) {
		ItemStack resolved = templateByType == null ? null : templateByType.get(beeType);
		Item outputItem = resolved == null || resolved.isEmpty() ? baseItem : resolved.getItem();
		TemplateKey key = new TemplateKey(baseItem, beeType, outputItem);
		ItemStack cached = TEMPLATE_CACHE.get(key);
		if (cached != null) return cached;
		ItemStack template;
		if (resolved != null && !resolved.isEmpty()) {
			template = resolved.copyWithCount(1);
		} else {
			template = new ItemStack(baseItem);
			template.set(PbDataComponents.beeType(), beeType);
		}
		if (TEMPLATE_CACHE.size() < TEMPLATE_CACHE_CAPACITY) TEMPLATE_CACHE.put(key, template);
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
