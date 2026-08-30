package com.ayoshiko.productivebeesgenesis.mek;

import java.util.function.IntSupplier;

/**
 * Per-tile 的「零耗时配方合并推进」窗口，同时作为 Mekanism 的
 * {@code baselineMaxOperations} 供应商。
 * <p>
 * <b>背景</b>：CREATIVE 升级让 {@code getTicksRequired()} 返回 0
 * （{@code FactoryUpgradeStateHelper.getTicksRequired} / {@code TileEntityMekCentrifuge.getTicksRequired}），
 * Mekanism 的每个配方 tick 于是恰好完成一个完整周期
 * （{@code operatingTicks++ → >= ticksRequired → finishProcessing + resetCache}）。
 * {@code CachedRecipeBatchAccelMixin} 原有快速路径以「周期内只推进 operatingTicks」为前提，
 * 对零耗时配方必然在第一次推进就完成周期并退出，等价于<b>每个虚拟刻一次完整
 * calculateOperationsThisTick + 一次输出槽 insertItem</b> —— 正是 spark XnLugba3Cw 中
 * {@code calculateOperationsThisTick} 1940ms、{@code finishProcessing} 1208ms
 * （内含 {@code BasicInventorySlot.insertItem} 748+564ms）的来源。
 * <p>
 * <b>做法</b>：补调窗口内把「单刻并行上限」放大 factor 倍，让 Mekanism 一次调用承担整批。
 * 放大点只有本对象一处 —— 它既是 {@code CachedRecipe} 的 OperationTracker 起始上限来源，
 * 也是 {@code ExtraOutputHelper} 输出空间探测的上限来源，因此不需要按字节码序号注入
 * Mekanism 内部，版本升级不会静默错位。
 * <p>
 * <b>为什么不会多产出</b>：输入不足（{@code InputHelper} 按输入件数）、输出空间不足
 * （{@code ExtraOutputHelper} 按槽位剩余空间）、能量不足（{@code capAtMaxForEnergy}）
 * 的裁剪全部仍由 Mekanism 原逻辑执行；而
 * {@code OneInputCachedRecipe.finishProcessing(ops)} 对 ops 线性
 * （{@code inputHandler.use(input, ops)} / {@code outputHandler.handleOutput(output, ops)}），
 * 故 {@code finishProcessing(ops×k)} 与 k 次 {@code finishProcessing(ops)} 完全等价。
 * <p>
 * <b>线程安全</b>：方块实体只在服务端主线程 tick；字段用 volatile 发布，
 * 窗口开闭由 Mixin 严格配对（获批操作数落定即 {@link #end()}），不会跨 tick 泄漏。
 */
public final class ZeroTickCoalesceState implements IntSupplier {

	/** 未放大的单刻并行上限供应商（{@code operationsPerTick()}，本身已按游戏刻记忆化）。 */
	private final IntSupplier baseOperations;

	/** 当前合并倍数，1 表示未启用合并（常态）。 */
	private volatile int factor = 1;

	/** 打开窗口时快照的单刻上限，供反推虚拟刻数与能量按刻计费。 */
	private volatile int base = 1;

	/**
	 * 窗口内 Mekanism 读到的放大值，在 {@link #begin} 时算好一次。
	 * <p>
	 * {@code getAsInt()} 会被 Mekanism 的 OperationTracker 与
	 * {@code ExtraOutputHelper} 输出空间探测反复调用（spark gUqyZmn5q6 中本方法
	 * 自耗 196ms / 0.65%），因此不在每次读取时重算饱和乘法。
	 */
	private volatile int raised = 1;

	/**
	 * 一次补调批次内的单刻上限快照；{@code -1} 表示未快照。
	 * <p>
	 * 供应商是 {@code operationsPerTick()}，其内部按 gameTick 记忆化，但仍要先取一次
	 * {@code level.getGameTime()}。补调循环每个虚拟刻都要开窗一次，于是 getGameTime
	 * 被放大到每真实刻上千次（spark gUqyZmn5q6 中 {@code Level.getGameTime} 自耗
	 * 464ms / 1.55%）。一个批次必然落在同一 gameTick 内（{@code startBatch} 由
	 * {@code runLightSmeltingTicks} 每 gameTick 调用一次），升级数量在刻内恒定，
	 * 故批内复用快照与逐次查询完全等价。
	 */
	private volatile int batchBase = -1;

	public ZeroTickCoalesceState(IntSupplier baseOperations) {
		this.baseOperations = baseOperations;
	}

	/** Mekanism 读取到的并行上限：窗口内返回放大值，常态返回原值。 */
	@Override
	public int getAsInt() {
		int localFactor = factor;
		if (localFactor <= 1) return baseOperations.getAsInt();
		return raised;
	}

	/** 批次开始：快照本 gameTick 的单刻上限，供批内所有开窗复用。 */
	public void beginBatch() {
		batchBase = Math.max(1, baseOperations.getAsInt());
	}

	/** 批次结束：丢弃快照，下个批次重新取。 */
	public void endBatch() {
		batchBase = -1;
	}

	/** 打开合并窗口；先取单刻上限（批内复用快照），避免窗口内自我放大。 */
	public void begin(int coalesceFactor) {
		int snapshot = batchBase;
		this.base = snapshot > 0 ? snapshot : Math.max(1, baseOperations.getAsInt());
		this.factor = Math.max(1, coalesceFactor);
		this.raised = ZeroTickBatchMath.raisedBaseline(this.base, this.factor);
	}

	/** 关闭合并窗口，恢复常态单刻上限。 */
	public void end() {
		this.factor = 1;
	}

	public int factor() {
		return factor;
	}

	public int base() {
		return base;
	}

	public boolean isActive() {
		return factor > 1;
	}
}
