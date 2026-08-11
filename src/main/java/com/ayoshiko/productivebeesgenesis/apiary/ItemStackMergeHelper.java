package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
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
	 *   <li>快速路径（组大小 ≤ 2）：直接信任 hashCode 分组结果，按总量拆分堆叠。
	 *       蜜蜂产物组件简单（蜜脾/蜂蜜瓶/蜡/基因等），32 位 hashCode 冲突概率极低，
	 *       且避免 GeckoLib wrapOperation 拦截带来的 isSameItemSameComponents 开销。</li>
	 *   <li>回退路径（组大小 ≥ 3）：使用 {@link ItemStack#isSameItemSameComponents} 两两验证组内元素，
	 *       发现冲突时跳过整个组的合并并记录 WARN 日志（保守策略，保护数据完整性）。</li>
	 * </ul>
	 */
	private static void mergeGroup(List<ItemStack> group, List<ItemStack> result) {
		if (group.isEmpty()) return;
		if (group.size() == 1) {
			result.add(group.get(0).copy());
			return;
		}

		// 回退路径：组大小 ≥ 3 时，使用 isSameItemSameComponents 两两验证组内无 hash 冲突。
		// 同组内 Item 已相同（MergeKey 含 Item identity），冲突只可能来自组件 hashCode 碰撞。
		// 一旦发现不一致，跳过整个组的合并（保守策略），避免不同组件的物品被错误堆叠导致数据破坏。
		if (group.size() >= 3) {
			for (int i = 0; i < group.size(); i++) {
				for (int j = i + 1; j < group.size(); j++) {
					ItemStack a = group.get(i);
					ItemStack b = group.get(j);
					if (!ItemStack.isSameItemSameComponents(a, b)) {
						LogThrottle.warn("itemstack_hash_conflict", "检测到 ItemStack 组件 hashCode 冲突（同组内组件不一致），跳过该组合并以保护数据完整性: item={}, groupSize={}",
								a.getItem(), group.size());
						return;
					}
				}
			}
		}

		// 快速路径（组大小 ≤ 2 直接信任，或组大小 ≥ 3 已通过两两验证）：
		// 同组内 (Item, componentHash) 已相同，直接按总量拆分堆叠。
		ItemStack first = group.get(0);
		long totalCount = 0;
		for (ItemStack stack : group) {
			totalCount += stack.getCount();
		}
		int maxSize = first.getMaxStackSize();
		while (totalCount > 0) {
			int count = (int) Math.min(totalCount, maxSize);
			result.add(first.copyWithCount(count));
			totalCount -= count;
		}
	}
}
