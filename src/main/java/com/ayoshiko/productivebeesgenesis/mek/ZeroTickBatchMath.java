package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;

/**
 * 零耗时配方（CREATIVE 升级）在时间加速下的「合并推进」数学 —— 纯函数，可单测。
 * <p>
 * <b>为什么需要它</b>：CREATIVE 升级让 {@code getTicksRequired()} 返回 0
 * （见 {@code FactoryUpgradeStateHelper.getTicksRequired} / {@code TileEntityMekCentrifuge.getTicksRequired}），
 * 于是 Mekanism 的每个配方 tick 都恰好完成一个完整周期：
 * {@code operatingTicks++ → >= 0 → finishProcessing + resetCache}。
 * {@code CachedRecipeBatchAccelMixin} 原有的快速路径依赖「周期内只推进 operatingTicks」，
 * 在零耗时配方上必然在第一个周期就 break 并把 batchFastOps 归零，
 * 等价于<b>每个虚拟刻一次完整 calculateOperationsThisTick + 一次输出槽 insert</b>——
 * 这正是 spark XnLugba3Cw 里 {@code calculateOperationsThisTick} 1940ms
 * 与 {@code finishProcessing} 1208ms（其中 {@code BasicInventorySlot.insertItem} 748+564ms）的来源。
 * <p>
 * <b>合并原理</b>：零耗时配方的 k 个虚拟刻 = k 个独立完整周期，而
 * {@code OneInputCachedRecipe.finishProcessing(ops)} 对 ops 是线性的
 * （{@code inputHandler.use(input, ops)} 收缩 ops 份输入、
 * {@code outputHandler.handleOutput(output, ops)} 写入 ops 份产物）。
 * 因此 {@code finishProcessing(ops × k)} 与 k 次 {@code finishProcessing(ops)} 完全等价。
 * 实现上只把 OperationTracker 的起始并行上限放大 k 倍，
 * <b>输入不足 / 输出空间不足 / 能量不足的裁剪仍然全部交给 Mekanism 原逻辑</b>，
 * 所以不存在「多产出」或「吃掉不存在的输入」的可能，而完整计算次数从 k 降为 1。
 * <p>
 * <b>能量语义</b>：本模组的高并行边际计费曲线（{@link MekCentrifugeEnergyScaling}）对操作数是
 * <i>次线性</i>的，若直接按 {@code ops × k} 计费会让加速几乎免费。
 * 因此满批与尾批分别计费，单刻上限仍为原始 baseOperations。
 * 可承担量按当前总电量依次支付满刻，剩余电量再支付一个尾刻。
 */
public final class ZeroTickBatchMath {

	private ZeroTickBatchMath() {
	}

	/**
	 * 该配方是否适用合并推进。
	 * <p>
	 * {@code ticksRequired <= 1} 表示每个配方 tick 就是一个完整周期（0 = CREATIVE，1 = 极限速度升级），
	 * 此时逐刻推进没有任何可复用的中间状态，合并才有意义；
	 * {@code >= 2} 的多刻配方交给原有 operatingTicks 快速路径。
	 */
	public static boolean isCoalescible(int ticksRequired) {
		return ticksRequired <= 1;
	}

	/** 把单刻并行上限放大 factor 倍（饱和），交给原版裁剪。 */
	public static int raisedBaseline(int baseOperations, int factor) {
		if (factor <= 1) return baseOperations;
		int base = Math.max(1, baseOperations);
		return SaturatingMath.saturatingToInt(SaturatingMath.saturatingMultiply(base, factor));
	}

	/** 本次合并调用实际代表了多少个虚拟刻（按单刻上限向上取整，至少 1）。 */
	public static int virtualTicksFor(int operations, int baseOperations) {
		if (operations <= 0) return 0;
		int base = Math.max(1, baseOperations);
		return SaturatingMath.saturatingToInt(((long) operations + base - 1L) / base);
	}

	/** 把总操作数均摊回单个虚拟刻（向上取整，计费偏保守，绝不出现免费工作）。 */
	public static int operationsPerVirtualTick(int operations, int virtualTicks) {
		if (operations <= 0 || virtualTicks <= 0) return 0;
		return SaturatingMath.saturatingToInt(((long) operations + virtualTicks - 1L) / virtualTicks);
	}

	/** 单个虚拟刻可用的能量预算。 */
	public static long perVirtualTickEnergyBudget(long storedEnergy, int factor) {
		if (storedEnergy <= 0L) return 0L;
		if (factor <= 1) return storedEnergy;
		return storedEnergy / factor;
	}

	/** 满批逐刻扣费，最后一刻只承担剩余电量实际能支付的操作。 */
	public static int affordableCoalescedOperations(long energyPerOperation, int baseOperations,
			long storedEnergy, int factor) {
		int base = Math.max(1, baseOperations);
		int ticks = Math.max(1, factor);
		if (energyPerOperation <= 0) return raisedBaseline(base, ticks);
		long energy = Math.max(0, storedEnergy);
		int first = MekCentrifugeEnergyScaling.affordableOperations(energyPerOperation, base, energy);
		if (first < base) return first;
		long fullCost = MekCentrifugeEnergyScaling.parallelEnergyCost(energyPerOperation, base);
		long fullTicks = Math.min(ticks, energy / fullCost);
		long operations = fullTicks * base;
		if (fullTicks < ticks) {
			long remaining = energy - fullTicks * fullCost;
			operations += MekCentrifugeEnergyScaling.affordableOperations(energyPerOperation, base, remaining);
		}
		return SaturatingMath.saturatingToInt(operations);
	}
	/** 总量按原始单刻上限分段，尾批不均摊到已经完成的满批。 */
	public static long coalescedEnergyCost(long energyPerOperation, int operations, int baseOperations) {
		if (operations <= 0 || energyPerOperation <= 0) return 0;
		int base = Math.max(1, baseOperations);
		long full = MekCentrifugeEnergyScaling.batchEnergyCost(energyPerOperation, base, operations / base);
		long tail = MekCentrifugeEnergyScaling.parallelEnergyCost(energyPerOperation, operations % base);
		return SaturatingMath.saturatingAdd(full, tail);
	}
}
