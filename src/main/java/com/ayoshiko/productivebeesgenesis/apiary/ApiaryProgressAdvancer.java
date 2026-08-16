package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.DevModeManager;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

import java.util.concurrent.atomic.AtomicInteger;

/**
	 * 蜜蜂槽位生产进度推进辅助 — 从 {@link BeeSlotTickProcessor#tick()}
	 * 循环内「推进计时」代码块整体搬移：计算完成周期、同步
	 * adjustedMinTicks、更新进度并累积待产出次数（含速度诊断日志）。
	 * <p>
	 * 调用时序保持与原来一致：该方法在能量检查之后、设置 WORKING 状态之前执行。
	 *
	 * @param slot 蜜蜂槽
	 * @param slotIndex 槽位索引
	 * @param tickMultiplier 本 tick 批量倍率
	 * @param currentTick 当前游戏刻（调试日志采样用）
	 * @param timeMultiplier 时间倍率（加速 < 1.0 / 减速 > 1.0）
	 * @param beeEnergyCost 单只蜜蜂每 tick 能耗
	 * @param stackProductionCount STACK 升级产出次数倍率
	 * @param cachedProcessingTime 配置缓存的基础处理时间（tick）
	 * @param upgradeHandler 升级处理器
	 * @param pendingProductions 待产出次数数组（按槽位索引）
	 * @param accumulatedProgress 累积产出计数器
	 * @return 本槽位本 tick 应累积的能耗（由调用方加入 pendingEnergyCost）
	 */
final class ApiaryProgressAdvancer {

	private ApiaryProgressAdvancer() {
	}

	static long advance(BeeSlot slot, int slotIndex, int tickMultiplier, long currentTick,
			float timeMultiplier, long beeEnergyCost, int stackProductionCount, int cachedProcessingTime,
			ApiaryUpgradeHandler upgradeHandler, int[] pendingProductions, AtomicInteger accumulatedProgress) {

		long acceleratedEnergyCost = ApiaryEnergyMath.calculateAcceleratedEnergyCost(beeEnergyCost, tickMultiplier);

		// 推进计时
		int currentTicks = slot.getTicksInHive();
		// 模块1修复：从 baseMinOccupationTicks 读取原始基础值，而非 minOccupationTicks（adjusted 值）。
		// 此前从 minOccupationTicks 读取会被上一 tick 回写的 adjustedMinTicks 污染，
		// 导致下一 tick 再次乘以 timeMultiplier 形成指数衰减。
		int baseMinTicks = slot.getBaseMinOccupationTicks();
		if (baseMinTicks <= 0) {
			// 使用配置缓存的基础处理时间（从 ModConfig.SERVER.apiaryProcessingTime 读取，默认1200）
			baseMinTicks = cachedProcessingTime;
		}
		// 应用时间倍率（< 1.0 加速，> 1.0 减速）
		// Task 4：CREATIVE 升级 — adjustedMinTicks=1，每 tick 产出（参考 MEK getTicksRequired 返回 0）
		float safeTimeMultiplier = SaturatingMath.positiveFiniteFloat(timeMultiplier, 1.0f);
		int adjustedMinTicks = upgradeHandler.hasCreativeUpgrade() ? 1
				: Math.max(1, SaturatingMath.saturatingRoundToInt((double) baseMinTicks * safeTimeMultiplier));
		// 模块1：蜂箱速度调试日志 — 每 100 tick 采样一次，仅在 dev 模式开启时输出
		// 外层 isEnabled() 守卫避免 dev 关闭时调用 DevLog.debug 的方法调用开销
		// DevLog.debug 内部还会检查 apiary_speed feature 开关并做 1000ms 节流
		if ((currentTick % 100) == 0 && DevModeManager.isEnabled()) {
			DevLog.debug("apiary_speed",
					"蜂箱速度诊断 slot={} baseMinTicks={} timeMultiplier={} "
							+ "mekTimeMul={} pbTimeDivisor={} speedUpgrades={} maxSpeed={} "
							+ "maxUpgradeMul={} adjustedMinTicks={}",
					slotIndex, baseMinTicks, timeMultiplier,
					upgradeHandler.getMekSpeedTimeMultiplier(),
					ApiaryUpgradeMath.getPbTimeDivisor(upgradeHandler),
					upgradeHandler.getMekSpeedUpgrades(),
					ApiaryUpgradeMath.getMaxSpeedUpgrades(),
					ApiaryUpgradeMath.getMaxUpgradeMultiplier(),
					adjustedMinTicks);
		}
		// 同步 adjustedMinTicks 到 BeeSlot，确保 tooltip 工作进度显示正确的工作 tick 上限
		// 修复：此前不更新 minOccupationTicks 导致 tooltip 始终显示 300/0 tick（0%）
		if (slot.getMinOccupationTicks() != adjustedMinTicks) {
			slot.setMinOccupationTicks(adjustedMinTicks);
		}
		// Tick 加速器会在同一 game tick 重复调用方块实体；后续调用被跳过时，
		// 这里一次推进对应数量的虚拟 tick。这样进度和完成节奏真实加速，
		// 不再等到周期结束后才一次性乘产出，同时总产量保持与原批处理策略一致。
		long advancedTicks = SaturatingMath.saturatingAdd(
				Math.max(0, currentTicks), Math.max(1, tickMultiplier));
		int completedCycles = (int) Math.min(Integer.MAX_VALUE,
				advancedTicks / adjustedMinTicks);
		int newTicks = (int) (advancedTicks % adjustedMinTicks);
		slot.setTicksInHive(newTicks);

		// 更新进度（供 GUI 进度条渲染）
		slot.setProgress((float) newTicks / adjustedMinTicks);

		// 完成累积 — 达到最小 occupation ticks 时累积待产出次数（不立即产出）
		if (completedCycles > 0 && slotIndex < pendingProductions.length) {
			// STACK 倍率作用于每个真实完成周期；概率产出仍由后续批量采样处理。
			int pendingCount = SaturatingMath.saturatingToInt(SaturatingMath.saturatingMultiply(
					Math.max(0, stackProductionCount), completedCycles));
			pendingProductions[slotIndex] = ApiaryEnergyMath.saturatingAdd(pendingProductions[slotIndex], pendingCount);
			saturatingAdd(accumulatedProgress, pendingCount);
		}

		return acceleratedEnergyCost;
	}

	private static void saturatingAdd(AtomicInteger counter, int amount) {
		if (amount <= 0) return;
		int current;
		int updated;
		do {
			current = counter.get();
			updated = ApiaryEnergyMath.saturatingAdd(current, amount);
		} while (!counter.compareAndSet(current, updated));
	}
}
