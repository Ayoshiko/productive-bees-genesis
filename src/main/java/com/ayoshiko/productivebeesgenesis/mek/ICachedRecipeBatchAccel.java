package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.recipes.cache.CachedRecipe;

/**
 * {@link CachedRecipe} 批量快速推进接口 — 由 {@code CachedRecipeBatchAccelMixin} 注入实现。
 * <br/>
 * 借鉴 JDTE 合并 flush（CoalescedAcceleratedMachine）思想：把 M 次逐 tick 的
 * {@link CachedRecipe#process()} 调用合并为"一次完整计算 + 预算内循环推进"，
 * 使 smelt（电力熔炼炉）配方在时间加速下不再每 tick 重复执行
 * calculateOperationsThisTick（getRecipeInput / 创建输出 ItemStack / 输出空间检查）。
 * <p>
 * 语义与逐次调用 process() 完全等价（能量消耗、输入消费、产出、进度推进一致）：
 * <ul>
 *   <li>快速路径只做字段级推进（useEnergy / operatingTicks++ / 周期完成判定）</li>
 *   <li>配方周期完成点自动退出快速路径，下一次调用走完整计算（输入/输出槽已变化，必须重算）</li>
 *   <li>能量不足 / 配方暂停 / 持有者不可用 / 预算耗尽时自动终止，剩余交给原版逻辑处理</li>
 * </ul>
 */
public interface ICachedRecipeBatchAccel {

	/**
	 * Enables the centrifuge marginal-energy curve for both normal and accelerated recipe ticks.
	 * The Mixin is global, so callers must opt in only for recipes owned by this mod.
	 */
	void productivebeesgenesis$enableMarginalEnergyPricing();

	/**
	 * 绑定 per-tile 的零耗时合并窗口（CREATIVE 升级路径）。
	 * <br/>
	 * 传入的状态对象同时是 Mekanism 的 {@code baselineMaxOperations} 供应商，
	 * 合并窗口打开时它返回放大后的单刻并行上限，使一次完整计算承担整批虚拟刻。
	 * 未绑定（null）时零耗时合并不生效，行为与旧版完全一致。
	 *
	 * @param state per-tile 合并窗口，可为 null
	 */
	void productivebeesgenesis$bindZeroTickCoalesce(ZeroTickCoalesceState state);

	/**
	 * 启动一次批量快速推进（预算 = ticks 个配方 tick）。
	 * <br/>
	 * 调用后对 {@link CachedRecipe#process()} 的后续调用会在预算内走快速路径；
	 * 预算耗尽、周期完成或异常条件出现时自动退出，恢复原版逐 tick 语义。
	 *
	 * @param ticks 批量预算（batchMultiplier - 1；<= 0 表示不激活）
	 */
	void productivebeesgenesis$startBatch(int ticks);

	/**
	 * 查询批量预算是否已耗尽（或已被异常条件终止）。
	 * <br/>
	 * 补调循环每次调用 {@link CachedRecipe#process()} 后检查此方法，预算耗尽立即停止补调，
	 * 避免"合并推进后剩余的逐 tick 调用"造成过度推进。
	 *
	 * @return true 表示预算耗尽/终止，后续 process() 调用恢复原版逐 tick 语义
	 */
	boolean productivebeesgenesis$isBatchExhausted();
}
