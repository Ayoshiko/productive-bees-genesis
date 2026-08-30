package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
	 * 物品栈合并工具类
	 * <br/>
	 * 将多个相同物品+组件的栈合并为更少的栈（不超过最大堆叠数），
	 * 用于批量产出场景下减少 insertItem 调用次数。
	 * <p>
	 * 性能优化（基于 Spark 分析 35.54% CPU 热点）：
	 * <br/>
	 * 旧版"全同质快速路径"仍需 N-1 次 {@link ItemStack#isSameItemSameComponents} 调用，
	 * 每次调用经过 GeckoLib 拦截 → {@code PatchedDataComponentMap.equals()} →
	 * {@code Reference2ObjectMap.equals()} → {@code containsAll()}，复杂度 O(n²)（n=组件数）。
	 * <p>
	 * 新版改为 <b>hashCode 预分组</b>策略：
	 * <ol>
	 *   <li>对每个 stack 计算一次 {@code getComponents().hashCode()}（O(n) 复杂度）</li>
	 *   <li>以 (Item identity, componentHash) 为 key 分组到 HashMap</li>
	 *   <li>同组 stack 几乎必然完全相同（hash 冲突概率极低）</li>
	 *   <li>仅在 hash 冲突时才回退到 {@code isSameItemSameComponents} 线性合并</li>
	 * </ol>
	 * 总复杂度从 O(N²×n²) 降为 O(N×n)。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅做物品栈合并，不涉及槽位或配方逻辑</li>
	 *   <li>无状态：纯静态方法，线程安全</li>
	 * </ul>
	 */
public final class ItemStackMergeHelper {

	/** 工具类禁止实例化 */
	private ItemStackMergeHelper() {
	}

	/**
	 * 合并相同物品+组件的栈（hashCode 预分组策略）
	 * <br/>
	 * 将多个相同物品的栈合并为更少的栈（不超过最大堆叠数）。
	 * 用于批量产出分发前预处理，显著减少 insertItem 调用次数。
	 * <p>
	 * 算法原理：
	 * <ol>
	 *   <li>对每个 stack 计算 (Item, componentHash) 复合键</li>
	 *   <li>用 HashMap 分组，同组 stack 组件 hash 相同 → 几乎必然完全相同</li>
	 *   <li>同组内直接按总数量拆分堆叠，无需逐对比较</li>
	 *   <li>hash 冲突时回退到 isSameItemSameComponents 线性合并（极罕见）</li>
	 * </ol>
	 * <p>
	 * 边界场景：空列表返回空列表；单元素列表返回单元素副本。
	 *
	 * @param stacks 待合并的物品栈列表
	 * @return 合并后的物品栈列表
	 */
	public static List<ItemStack> mergeStacks(List<ItemStack> stacks) {
		// 边界场景：空列表直接返回空列表
		if (stacks.isEmpty()) return new ArrayList<>();
		// 单元素：直接返回副本，避免调用方意外修改输入
		if (stacks.size() == 1) {
			ItemStack single = stacks.get(0);
			return single.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(single.copy()));
		}

		// 第一级：按 (Item identity, componentHash) 分组
		Map<MergeKey, List<ItemStack>> groups = new HashMap<>();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) continue;
			MergeKey key = new MergeKey(stack.getItem(), stack.getComponents().hashCode());
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(stack);
		}

		// 第二级：每组内合并（同 hash 组几乎必然全同质，直接按总量拆分）
		List<ItemStack> result = new ArrayList<>(groups.size());
		for (List<ItemStack> group : groups.values()) {
			mergeGroup(group, result);
		}
		return result;
	}

	/**
	 * 合并相同物品+组件的栈，<b>不按最大堆叠数拆分</b>（AE2 直推专用）。
	 * <p>
	 * 与 {@link #mergeStacks(List)} 的区别只有一点：本方法把同类物品的数量全部聚合到
	 * 一个栈里（数量可远超 64），因为 AE2 的 {@code MEStorage.insert(key, amount, ...)}
	 * 接受任意数量，一次 insert 就能收下全部产物。
	 * <p>
	 * <b>为什么需要它</b>：蜂箱批量产出会为四档生产力基因各生成一个同类蜜脾栈
	 * （{@code BeeProduceBatchSampler.sampleInto} 按 productivityLevel 循环调用），
	 * 加上基因采样与万象产物，同一物品在 {@code allItems} 中可能出现 4 次以上。
	 * 每个栈单独调用 {@code pushGeneratedItemToAe} 就是 4 次完整 ME 网络遍历；
	 * 而 Spark 实证单次遍历在昂贵外部存储（EnderDrives WAL fsync、ae2lt 样板解码）
	 * 上要 0.3-10ms。先聚合再推送，把 insert 次数降到「物品种类数」，
	 * 产物数量一件不少 —— 纯降开销、不降吞吐。
	 * <p>
	 * 数量用饱和加法防溢出；超过 {@link Integer#MAX_VALUE} 时才拆成多个栈。
	 *
	 * @param stacks 待聚合的物品栈列表
	 * @return 按物品+组件聚合后的列表；输入 ≤1 个元素时直接返回原列表（零开销）
	 */
	public static List<ItemStack> mergeForBulkTransfer(List<ItemStack> stacks) {
		if (stacks.size() <= 1) return stacks;

		// 一级：按 (Item identity, componentHash) 分组，与 mergeStacks 同策略
		Map<MergeKey, List<ItemStack>> groups = new HashMap<>();
		for (ItemStack stack : stacks) {
			if (stack.isEmpty()) continue;
			MergeKey key = new MergeKey(stack.getItem(), stack.getComponents().hashCode());
			groups.computeIfAbsent(key, k -> new ArrayList<>()).add(stack);
		}
		if (groups.size() == stacks.size()) return stacks; // 无可聚合项，避免多余复制

		List<ItemStack> result = new ArrayList<>(groups.size());
		for (List<ItemStack> group : groups.values()) {
			mergeGroupUnbounded(group, result);
		}
		return result;
	}

	/**
	 * 合并单个分组，数量不按最大堆叠数拆分（仅受 int 上限约束）。
	 * <p>
	 * hash 冲突时与 {@link #mergeGroup} 同策略：保留全部原始栈并记录节流日志，
	 * 绝不合并组件不同的物品。
	 */
	private static void mergeGroupUnbounded(List<ItemStack> group, List<ItemStack> result) {
		if (group.isEmpty()) return;
		if (group.size() == 1) {
			result.add(group.get(0));
			return;
		}
		ItemStack first = group.get(0);
		for (int i = 1; i < group.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(first, group.get(i))) {
				LogThrottle.warn("itemstack_hash_conflict",
						"检测到 ItemStack 组件 hashCode 冲突，保留原始物品栈: item={}, groupSize={}",
						first.getItem(), group.size());
				result.addAll(group);
				return;
			}
		}
		long totalCount = 0L;
		for (ItemStack stack : group) {
			totalCount = SaturatingMath.saturatingAdd(totalCount, stack.getCount());
		}
		// int 溢出保护：超过上限的部分拆成后续栈，数量零丢失
		while (totalCount > 0L) {
			int count = (int) Math.min(totalCount, Integer.MAX_VALUE);
			result.add(first.copyWithCount(count));
			totalCount -= count;
		}
	}

	/**
	 * 复合分组键：Item 引用 + 组件 hashCode
	 * <br/>
	 * 使用 Item 的 identityHashCode 避免 hashCode() 开销（Item 通常不覆盖 hashCode）。
	 * componentHash 来自 {@code DataComponentMap.hashCode()}，O(n) 复杂度。
	 * <p>
	 * equals 仅在 HashMap 内部 hash 冲突时调用，频率极低。
	 */
	private static final class MergeKey {
		private final Item item;
		private final int componentHash;
		private final int cachedHash;

		MergeKey(Item item, int componentHash) {
			this.item = item;
			this.componentHash = componentHash;
			this.cachedHash = System.identityHashCode(item) * 31 + componentHash;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof MergeKey other)) return false;
			return item == other.item && componentHash == other.componentHash;
		}

		@Override
		public int hashCode() {
			return cachedHash;
		}
	}

	/**
	 * 合并单个 (Item, componentHash) 组内的物品栈。
	 * <p>
	 * 同组 stack 的 Item 相同且组件 hashCode 相同，几乎必然完全同质。
	 * <p>
	 * 分组策略（兼顾性能与数据完整性）：
	 * <ul>
	 *   <li>用组件 hash 先缩小候选组，再把每个元素与组首元素比较，总比较次数为 N-1。</li>
	 *   <li>发现 hash 冲突时保留全部原始栈并记录节流日志，绝不合并或丢弃不同组件。</li>
	 * </ul>
	 */
	private static void mergeGroup(List<ItemStack> group, List<ItemStack> result) {
		if (group.isEmpty()) return;
		if (group.size() == 1) {
			result.add(group.get(0).copy());
			return;
		}

		// Equality is transitive, so comparing each entry with the first is enough.
		// On the extremely rare 32-bit component-hash collision, preserve every stack
		// instead of merging incompatible components or dropping the whole group.
		ItemStack first = group.get(0);
		for (int i = 1; i < group.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(first, group.get(i))) {
				LogThrottle.warn("itemstack_hash_conflict",
						"检测到 ItemStack 组件 hashCode 冲突，保留原始物品栈: item={}, groupSize={}",
						first.getItem(), group.size());
				for (ItemStack stack : group) result.add(stack.copy());
				return;
			}
		}

		// All entries are component-equal; aggregate their counts and split by max stack size.
		long totalCount = 0;
		for (ItemStack stack : group) {
			totalCount = SaturatingMath.saturatingAdd(totalCount, stack.getCount());
		}
		int maxSize = first.getMaxStackSize();
		if (maxSize <= 0) {
			for (ItemStack stack : group) result.add(stack.copy());
			return;
		}
		while (totalCount > 0) {
			int count = (int) Math.min(totalCount, maxSize);
			result.add(first.copyWithCount(count));
			totalCount -= count;
		}
	}
}
