package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;

/**
	 * AEItemKey 缓存
	 * <br/>
	 * 按槽位与完整物品组件缓存 AEItemKey，避免每次推送或列举都调用
	 * {@link AEItemKey#of(ItemStack)}。AEItemKey.of 内部触发
	 * {@code ItemStack.hashItemAndComponents} → DataComponentMap 查询，
	 * 是 Spark 热力图中的主要热点（266-357 次）。
	 * <p>
	 * <b>缓存策略</b>：key 不含数量；数量变化和等价栈替换可复用，组件原地修改必须重新建 key。
	 * <p>
	 * <b>线程安全</b>：缓存由离心机服务端 tick 线程独占访问，无需同步。
	 */
public final class AeItemKeyCache {

	/** 单进程输出槽数量（主+副1+副2） */
	public static final int SLOTS_PER_PROCESS = 3;

	private final AEItemKey[] cachedKeys;

	/**
	 * @param capacity 槽位总数（工厂: processes × 3；基础机: 3）
	 */
	public AeItemKeyCache(int capacity) {
		this.cachedKeys = new AEItemKey[capacity];
	}

	/**
	 * 获取或计算指定槽位的 AEItemKey
	 * <br/>
	 * 完整组件匹配时直接返回缓存，否则重新计算并缓存。
	 *
	 * @param index 槽位索引（工厂: process × 3 + slotIdx；基础机: slotIdx）
	 * @param stack 当前槽位的 ItemStack
	 * @return AEItemKey，stack 为空时返回 null
	 */
	public AEItemKey get(int index, ItemStack stack) {
		if (index < 0 || index >= cachedKeys.length) {
			return stack.isEmpty() ? null : AEItemKey.of(stack);
		}
		if (stack.isEmpty()) {
			cachedKeys[index] = null;
			return null;
		}
		AEItemKey cached = cachedKeys[index];
		if (cached != null && cached.matches(stack)) {
			return cached;
		}
		AEItemKey key = AEItemKey.of(stack);
		cachedKeys[index] = key;
		return key;
	}

	/** 清空所有缓存（节点销毁时调用，释放 ItemStack 引用） */
	public void clear() {
		Arrays.fill(cachedKeys, null);
	}
}
