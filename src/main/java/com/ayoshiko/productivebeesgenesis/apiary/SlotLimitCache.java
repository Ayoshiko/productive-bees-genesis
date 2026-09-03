package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.atomic.AtomicLong;

/**
	 * 输出槽位堆叠上限缓存（按索引 identity 缓存 + 版本号失效）
	 * <br/>
	 * 缓存每个输出槽位的 {@link BasicInventorySlot#getLimit} 结果，避免高频
	 * getLimit 调用（{@code isOutputFull} 每次遍历所有输出槽都会触发）。
	 * <p>
	 * 从 {@link ApiarySlotManager} 拆分而来，原 v5 L-13 identity 缓存与
	 * v5 CP-59 配置不可变性约束在此处保留。
	 * <p>
	 * 缓存命中条件：
	 * <ul>
	 *   <li>ItemStack 引用未变（identity {@code ==} 比较，非 {@code equals}）</li>
	 *   <li>实例缓存版本号与全局版本号一致</li>
	 * </ul>
	 * <p>
	 * 缓存约束说明（v5 CP-59）：
	 * <ul>
	 *   <li>ItemStack 为可变对象，count 增减不改变引用 identity，故 count 变化时缓存仍有效</li>
	 *   <li>limit 值依赖 {@link TieredOutputInventorySlot} 的当前游戏会话倍率快照，
	 *       配置 reload 不改变该值，修改后重启生效</li>
	 *   <li>显式调用 {@link #invalidateCache()} 时递增 {@link #CACHE_VERSION}，
	 *       本类检测到版本号不匹配后重新计算 limit</li>
	 *   <li>线程安全：服务端 tick 与外部物流能力调用均在主线程执行；
	 *       {@code CACHE_VERSION} 为 AtomicLong，{@code cachedVersion} 为 volatile。
	 *       极端并发调用下最坏仅重复计算一次 limit，不影响正确性</li>
	 * </ul>
	 */
final class SlotLimitCache {

	/**
	 * 缓存全局版本号 — 仅在显式失效时递增，使所有实例的缓存失效。
	 * <br/>
	 * 采用版本号方案而非维护实例集合：
	 * <ul>
	 *   <li>避免维护弱引用实例集合的复杂性和 GC 隐患</li>
	 *   <li>AtomicLong 保证原子递增，线程安全</li>
	 *   <li>配置 reload 为低频事件，版本号递增开销可忽略</li>
	 * </ul>
	 */
	private static final AtomicLong CACHE_VERSION = new AtomicLong(0L);

	/** 缓存上次查询的 ItemStack 引用（identity 比较，非 equals） */
	private final ItemStack[] cachedLimitStacks;
	/** 缓存对应的 limit 值，避免高频 getLimit 调用 */
	private final int[] cachedLimits;
	/** 当前实例缓存对应的版本号，与 CACHE_VERSION 不匹配时缓存失效 */
	private volatile long cachedVersion;

	/**
	 * 构造指定槽位数量的缓存。
	 *
	 * @param slotCount 槽位数量（输出槽数量）
	 */
	SlotLimitCache(int slotCount) {
		this.cachedLimitStacks = new ItemStack[slotCount];
		this.cachedLimits = new int[slotCount];
	}

	/**
	 * 获取指定输出槽的堆叠上限（带 identity 缓存 + 版本号失效）
	 * <br/>
	 * ItemStack 引用未变（{@code ==} 比较）且缓存版本号与全局版本号一致时直接返回缓存值，
	 * 避免高频 getLimit 调用。
	 *
	 * @param index 槽位索引
	 * @param slot  对应的输出槽
	 * @param stack 当前槽内物品栈
	 * @return 堆叠上限
	 */
	int getCachedSlotLimit(int index, BasicInventorySlot slot, ItemStack stack) {
		if (stack == cachedLimitStacks[index] && cachedVersion == CACHE_VERSION.get()) {
			return cachedLimits[index];
		}
		int limit = slot.getLimit(stack);
		cachedLimitStacks[index] = stack;
		cachedLimits[index] = limit;
		cachedVersion = CACHE_VERSION.get();
		return limit;
	}

	/**
	 * 失效所有 {@link SlotLimitCache} 实例的槽位上限缓存
	 * <br/>
	 * 递增全局版本号 {@link #CACHE_VERSION}，所有实例下次调用 {@link #getCachedSlotLimit}
	 * 时检测到版本号不匹配将主动重新计算 limit。
	 * <p>
	 * 配置 reload 不调用此方法；倍率快照在当前游戏会话内保持稳定。
	 */
	static void invalidateCache() {
		CACHE_VERSION.incrementAndGet();
	}
}
