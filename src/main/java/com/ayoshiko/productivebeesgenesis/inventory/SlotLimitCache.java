package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
	 * 槽位基础堆叠上限缓存（单条目缓存）
	 * <br/>
	 * 用于 {@link mekanism.common.inventory.slot.BasicInventorySlot#getLimit} 的优化。
	 * 256× 加速下 getLimit 每秒被调用数千次，旧实现通过 componentHash 比较缓存命中，
	 * 但 {@code stack.getComponents().hashCode()} 本身会遍历 DataComponentMap（Spark 中
	 * PatchedDataComponentMap.hashCode 占 14~16% CPU），成为新的瓶颈。
	 * <p>
	 * 本缓存改为只按 {@link Item} 引用缓存 baseLimit，不再计算 componentHash。
	 * 依据：蜜蜂产物（蜜脾、蜂蜜瓶、基因、蜡等）的最大堆叠数仅由 Item 类型决定，
	 * 不依赖具体 DataComponent 实例；带自定义组件的物品回退到直接计算。
	 * <p>
	 * 失效条件：物品类型变化、或配置 reload 导致 MULTIPLIER_VERSION 变化。
	 * <p>
	 * 线程安全：getLimit 可能从同步线程调用，{@link #getBaseLimit} 使用 synchronized 守卫
	 * check-then-update 临界区，避免并发线程读到部分新值（如 cachedItem 已更新但 cachedBaseLimit 仍为旧值）。
	 */
public final class SlotLimitCache {

	/** 上次缓存的物品引用 */
	private volatile Item cachedItem;
	/** 上次缓存的基础上限（未乘倍率） */
	private volatile int cachedBaseLimit;
	/** 上次缓存时的倍率版本号 */
	private volatile long cachedMultiplierVersion = -1;

	/**
	 * 获取基础堆叠上限，优先返回缓存值。
	 *
	 * @param stack       被查询的物品栈
	 * @param rawLimit    BasicInventorySlot 的 limit 字段值
	 * @param obeyLimit   是否遵守物品最大堆叠限制
	 * @param multiplier  当前倍率值（已缓存）
	 * @return 基础堆叠上限
	 */
	public synchronized int getBaseLimit(@NotNull ItemStack stack, int rawLimit, boolean obeyLimit, int multiplier) {
		// 空槽直接计算（不缓存空槽）
		if (stack.isEmpty()) {
			return obeyLimit ? Math.min(rawLimit, stack.getMaxStackSize()) : rawLimit;
		}

		Item item = stack.getItem();
		long currentVersion = TieredInputSlot.MULTIPLIER_VERSION.get();

		Item cachedItemLocal = cachedItem;
		if (cachedItemLocal == item && cachedMultiplierVersion == currentVersion) {
			return cachedBaseLimit;
		}

		int baseLimit = obeyLimit ? Math.min(rawLimit, stack.getMaxStackSize()) : rawLimit;
		cachedItem = item;
		cachedBaseLimit = baseLimit;
		cachedMultiplierVersion = currentVersion;
		return baseLimit;
	}

	/**
	 * 从 {@link BasicInventorySlotAccessor} 便捷获取基础上限。
	 */
	public int getBaseLimit(@NotNull ItemStack stack, BasicInventorySlotAccessor accessor, int multiplier) {
		int rawLimit = accessor.productivebeesgenesis$getLimit();
		boolean obeyLimit = accessor.productivebeesgenesis$getObeyStackLimit();
		return getBaseLimit(stack, rawLimit, obeyLimit, multiplier);
	}
}
