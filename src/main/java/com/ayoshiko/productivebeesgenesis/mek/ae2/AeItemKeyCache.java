package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.Arrays;

import net.minecraft.world.item.ItemStack;

import appeng.api.stacks.AEItemKey;

/**
 * AEItemKey 缓存
 * <br/>
 * 按 (槽位索引, ItemStack identity) 缓存 AEItemKey，避免每次推送都调用
 * {@link AEItemKey#of(ItemStack)}。AEItemKey.of 内部触发
 * {@code ItemStack.hashItemAndComponents} → DataComponentMap 查询，
 * 是 Spark 热力图中的主要热点（266-357 次）。
 * <p>
 * <b>缓存策略</b>：identity 匹配（{@code ==} 比较）。Mekanism BasicInventorySlot
 * 在 count 变化时不创建新 ItemStack 对象，仅 setStack 替换引用，故 identity 足以检测变化。
 * <p>
 * <b>线程安全</b>：缓存由离心机服务端 tick 线程独占访问，无需同步。
 */
public final class AeItemKeyCache {

	/** 单进程输出槽数量（主+副1+副2） */
	public static final int SLOTS_PER_PROCESS = 3;

	private final ItemStack[] cachedStacks;
	private final AEItemKey[] cachedKeys;

	/**
	 * @param capacity 槽位总数（工厂: processes × 3；基础机: 3）
	 */
	public AeItemKeyCache(int capacity) {
		this.cachedStacks = new ItemStack[capacity];
		this.cachedKeys = new AEItemKey[capacity];
	}

	/**
	 * 获取或计算指定槽位的 AEItemKey
	 * <br/>
	 * identity 匹配时直接返回缓存，否则重新计算并缓存。
	 *
	 * @param index 槽位索引（工厂: process × 3 + slotIdx；基础机: slotIdx）
	 * @param stack 当前槽位的 ItemStack
	 * @return AEItemKey，stack 为空时返回 null
	 */
	public AEItemKey get(int index, ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (index < 0 || index >= cachedStacks.length) {
			return AEItemKey.of(stack);
		}
		if (stack == cachedStacks[index]) {
			return cachedKeys[index];
		}
		AEItemKey key = AEItemKey.of(stack);
		cachedStacks[index] = stack;
		cachedKeys[index] = key;
		return key;
	}

	/** 清空所有缓存（节点销毁时调用，释放 ItemStack 引用） */
	public void clear() {
		Arrays.fill(cachedStacks, null);
		Arrays.fill(cachedKeys, null);
	}
}
