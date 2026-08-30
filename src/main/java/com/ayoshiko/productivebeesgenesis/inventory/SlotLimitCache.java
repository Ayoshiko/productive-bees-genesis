package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
	 * 线程安全：服务端 tick 与外部物流能力调用均在主线程执行；字段为 volatile 保证可见性。
	 * 若极端情况下发生并发调用，最坏结果为重复计算一次 baseLimit，不影响正确性。
	 */
public final class SlotLimitCache {

	/** 上次缓存的物品引用 */
	private volatile Item cachedItem;
	/** 上次缓存的基础上限（未乘倍率） */
	private volatile int cachedBaseLimit;
	/** 上次缓存时的倍率版本号 */
	private volatile long cachedMultiplierVersion = -1;

	/** 有效上限（已乘倍率）缓存的物品引用；null 表示尚无缓存。 */
	private volatile Item effectiveItem;
	/** 有效上限缓存值（{@code baseLimit × multiplier} 的最终钳制结果）。 */
	private volatile int effectiveLimit;
	/** 有效上限缓存时的倍率版本号 */
	private volatile long effectiveVersion = -1;

	/**
	 * 查询「已乘倍率」的最终上限缓存。
	 * <br/>
	 * 命中时调用方可直接返回，跳过 倍率读取 → accessor 取字段 → baseLimit 计算 → 乘法钳制
	 * 的整条链路。这是 {@code getLimit} 唯一的真正热路径：外部物流（AE2/SFM）与 Mekanism
	 * 的 {@code insertItem}/{@code setStackSize} 每次都会调用它，时间加速下每真实刻数千次
	 * （spark BHSGIz87Uw 中 {@code getCachedBaseLimit} 自耗 1464ms/2.44%，
	 * spark gUqyZmn5q6 中 1272ms/4.24%，为全服第 2-3 热点）。
	 *
	 * @param stack 被查询的物品栈
	 * @return 命中时返回非负上限；未命中返回 -1
	 */
	public int peekEffectiveLimit(@NotNull ItemStack stack) {
		Item item = stack.isEmpty() ? Items.AIR : stack.getItem();
		if (effectiveItem != item) return -1;
		return effectiveVersion == TieredInputSlot.MULTIPLIER_VERSION.get() ? effectiveLimit : -1;
	}

	/**
	 * 记录本次算出的最终上限，供下次同 Item 命中。
	 *
	 * @param stack 被查询的物品栈
	 * @param limit 已乘倍率并钳制后的最终上限
	 */
	public void storeEffectiveLimit(@NotNull ItemStack stack, int limit) {
		if (limit < 0) return;
		effectiveLimit = limit;
		effectiveVersion = TieredInputSlot.MULTIPLIER_VERSION.get();
		// 最后发布 item：读侧先比 item 再比 version，此顺序保证不会读到「item 已匹配但值未写入」
		effectiveItem = stack.isEmpty() ? Items.AIR : stack.getItem();
	}

	/**
	 * 立即失效两级缓存。
	 * <br/>
	 * 供 {@code productivebeesgenesis$setInputStackMultiplier} 调用：替换倍率供应商
	 * 不会递增全局 {@link TieredInputSlot#MULTIPLIER_VERSION}，若不清本地缓存，
	 * 旧倍率算出的上限会继续命中。
	 */
	public void invalidate() {
		effectiveItem = null;
		effectiveVersion = -1;
		cachedItem = null;
		cachedMultiplierVersion = -1;
	}

	/**
	 * 获取基础堆叠上限，优先返回缓存值。
	 *
	 * @param stack       被查询的物品栈
	 * @param rawLimit    BasicInventorySlot 的 limit 字段值
	 * @param obeyLimit   是否遵守物品最大堆叠限制
	 * @param multiplier  当前倍率值（已缓存）
	 * @return 基础堆叠上限
	 */
	public int getBaseLimit(@NotNull ItemStack stack, int rawLimit, boolean obeyLimit, int multiplier) {
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
