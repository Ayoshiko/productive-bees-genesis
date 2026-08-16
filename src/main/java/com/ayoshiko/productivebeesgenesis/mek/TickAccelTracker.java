package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.world.level.Level;

/**
 * 加速倍率自动检测器 — 自动检测方块实体在同一游戏刻内被调用的次数作为加速倍率 M
 * <br/>
 * 兼容所有加速模组（JDT、加速火把、Industrial Foregoing: Souls、JDTE、EAEP 等），无需检测具体模组。
 * 被 IAe2InputHost 实现类持有（通过 Ae2OutputStateHolder），用于自适应节流 AE2 输入拉取逻辑。
 * <p>
 * <b>检测原理</b>：服务端单线程下，正常游戏刻内方块实体每 tick 仅被调用一次；
 * 当安装加速模组时，加速模组会在同一 gameTick 内多次调用方块实体的 tick 方法，
 * 通过统计同一 gameTick 内的调用次数即可得到加速倍率 M。
 * <p>
 * <b>计数来源隔离</b>：{@link #onTick}（真实 ticker 调用）参与倍率计数；
 * {@link #addVirtualTicks}（JDTE 合并接口）仅入账虚拟 tick 银行，不参与倍率计数，
 * 避免网格 tick 污染 multiplier 导致真实 ticker 被误判为"后续调用"而跳过处理。
 * <p>
 * <b>线程安全</b>：本类不使用 synchronized 或 volatile，方块实体在服务端单线程执行，
 * 跨线程访问无需同步。reset() 仅在主线程调用（服务器停止/维度切换时）。
 * <p>
 * <b>性能约束</b>：onTick() 单次调用开销必须 &lt; 10ns（仅 long == 比较 + int++），
 * getMultiplier() 单次调用开销必须 &lt; 5ns（仅 Math.min/max）。
 *
 * @since 2.0.0
 * @see com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost
 * @see com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder
 */
public class TickAccelTracker {

	/** 加速倍率上限 — 防止极端值导致 int 溢出 */
	private static final int MAX_MULTIPLIER = 1024;

	/**
	 * 虚拟 tick 银行挂账上限 — 修复 JDTE 加速停止后残留过久的根因。
	 * <br/>
	 * JDTE 每 gameTick 最多入账 timeAcceleratorMaxExecutionsPerTick（默认 4096）个虚拟 tick，
	 * 而每 gameTick 只消化 getBatchBudget（默认上限 1024，并受 MSPT 降级）个；若上限对齐
	 * JDTE 的 1M 挂账，加速停止后仍会长时间消化已经入账的虚拟 tick。将上限收紧为
	 * 4096（JDTE 单刻最大入账量），既保留单刻突发量的平滑能力，也把残留限制为至多
	 * 四个健康服务器满额批次；降级期间则按实时预算逐步消化。
	 */
	private static final long MAX_PENDING_TICKS = 4_096L;

	/** 每真实 gameTick 最多批量执行的虚拟 tick 数（预算）— 防止无批次限制的加速器（如 1024x 时间杖）造成 MSPT 尖峰 */
	private static final int MAX_BATCH_TICKS = 1024;
	/** 银行饱和告警间隔（tick） */
	private static final int SATURATION_WARN_INTERVAL = 600;

	/** 上次被调用的游戏刻 — 用于检测同一游戏刻内多次调用 */
	private long lastGameTick = Long.MIN_VALUE;

	/** 当前游戏刻内真实 ticker 的调用次数 — 即加速倍率 M 的原始值 */
	private int callsInCurrentTick = 0;

	/** 上一真实游戏刻完成的调用次数 — 供新 tick 首调执行批量工作时参考 */
	private int callsInPreviousTick = 1;

	/** 虚拟 tick 银行字段 — 已发生的加速调用挂账，按每 tick 预算分批消化（饱和累加，上限 MAX_PENDING_TICKS） */
	private long pendingVirtualTicks = 0L;

	/** 配置上限缓存的刷新 tick；MSPT 因子仍每个真实游戏刻读取一次。 */
	private static final int BUDGET_REFRESH_INTERVAL = 10;

	/** 上次刷新批量预算的 gameTick */
	private long lastBudgetRefreshTick = Long.MIN_VALUE;

	/** 缓存的批量预算（配置上限 × TPS 因子，至少 1） */
	private int cachedBatchBudget = MAX_BATCH_TICKS;

	/** 缓存的用户配置上限；与实时 MSPT 因子分开，避免为快速降级反复读取配置。 */
	private int cachedConfiguredMaxBatchTicks = MAX_BATCH_TICKS;

	/** 上次输出银行饱和告警的 gameTick（每 600 tick = 30 秒最多一次，避免刷屏） */
	private long lastSaturationWarnTick = Long.MIN_VALUE;

	/**
	 * 在方块实体 tick 时调用，统计同一游戏刻内的真实 ticker 调用次数
	 * <br/>
	 * <b>性能约束（极重要）</b>：方法体仅允许 {@code long == 比较} + {@code int++}，
	 * 禁止任何其他方法调用、字段反射、字符串拼接、I/O 操作。
	 * 单次调用开销必须 &lt; 10ns。JIT 可将本方法内联为极少的字节码指令。
	 * <p>
	 * <b>逻辑</b>：当前 gameTick 与上次相同时 callsInCurrentTick++，
	 * 否则重置 lastGameTick 并将 callsInCurrentTick 置 1。
	 *
	 * @param level 当前世界（仅用于获取 getGameTime，不进行任何其他访问）
	 */
	public void onTick(Level level) {
		long currentTick = level.getGameTime();
		if (currentTick == lastGameTick) {
			if (callsInCurrentTick < Integer.MAX_VALUE) {
				callsInCurrentTick++;
			}
		} else {
			if (lastGameTick != Long.MIN_VALUE && callsInCurrentTick > 0) {
				callsInPreviousTick = callsInCurrentTick;
			}
			lastGameTick = currentTick;
			callsInCurrentTick = 1;
		}
		// 虚拟 tick 银行入账（<10ns：一次比较 + 一次自增）
		if (pendingVirtualTicks < MAX_PENDING_TICKS) {
			pendingVirtualTicks++;
		}
	}

	/**
	 * 旧版 AE2 网格 tick 钩子。
	 * <br/>
	 * 当前 JDTE 兼容不再注册 {@code IGridTickable}，机器统一走
	 * {@code CoalescedAcceleratedMachine}。保留此方法仅兼容旧调用方；它只入账，
	 * 不参与 {@link #getMultiplier()} 的倍率计数。
	 * <p>
	 * <b>性能约束</b>：与 {@link #onTick} 一致，单次调用开销 &lt; 10ns。
	 *
	 * @param level 旧调用方传入的世界；当前实现无需读取
	 */
	public void onAe2Tick(Level level) {
		// 仅入账银行（<10ns），不修改倍率计数
		if (pendingVirtualTicks < MAX_PENDING_TICKS) {
			pendingVirtualTicks++;
		}
	}

	/**
	 * 获取加速倍率 M（已截断到 [1, 1024]）
	 * <br/>
	 * 返回值范围 [1, 1024]：
	 * <ul>
	 *   <li>1 表示无加速（每 tick 调用 1 次）</li>
	 *   <li>256 表示 256x 加速（每 tick 调用 256 次）</li>
	 *   <li>1024+ 被截断为 1024 防止溢出</li>
	 * </ul>
	 * 单次调用开销 &lt; 5ns（仅 Math.min/max）。
	 *
	 * @return 加速倍率 M，范围 [1, 1024]
	 */
	public int getMultiplier() {
		return Math.min(MAX_MULTIPLIER, Math.max(1, callsInCurrentTick));
	}

	/**
	 * 获取上一真实游戏刻完成的倍率
	 * <br/>
	 * 批量工作在 gameTick 切换后的第一次调用执行，此时 {@link #getMultiplier()}
	 * 还只能观察到本 tick 的首次调用（=1），因此需要上一 tick 的完成值
	 * 供首次批量工作参考。
	 *
	 * @return 上一 gameTick 的调用次数（截断到 [1, 1024]）
	 */
	public int getPreviousTickMultiplier() {
		return Math.min(MAX_MULTIPLIER, Math.max(1, callsInPreviousTick));
	}

	/**
	 * 从虚拟 tick 银行取出本真实 gameTick 的批量预算（虚拟 tick 银行 + 每 tick 预算）。
	 * <br/>
	 * 由机器 tick 处理器在每 gameTick 第一次完整处理时调用，
	 * 后续同 tick 调用仅入账（{@link #onTick} / {@link #onAe2Tick} / {@link #addVirtualTicks}）并跳过处理。
	 * <p>
	 * 效果：
	 * <ul>
	 *   <li>无加速：每次取 1，等价原版逐 tick 处理</li>
	 *   <li>JDTE 类批量加速（≤64/目标/pass）：银行刚好覆盖，无尖峰</li>
	 *   <li>无批次限制加速器（如 1024x 时间杖）：每 tick 最多处理 {@link #getMaxBatchTicks()}，
	 *       余量挂账后续 tick 消化——<b>短期</b>总产出不丢失，MSPT 平滑；
	 *       持续超预算加速时产能被预算封顶（见 {@link #getBatchBudget} 的饱和告警）</li>
	 *   <li>加速停止：残留挂账被 {@link #MAX_PENDING_TICKS} 收紧（4096），随后按实时预算消化，
	 *       不再残留数十分钟的加速效果</li>
	 * </ul>
	 * 单次调用开销 &lt; 5ns（一次 Math.min + 一次减法）。
	 *
	 * @param budget 本 tick 预算上限（建议使用 {@link #getMaxBatchTicks()}）
	 * @return 本 tick 应批量执行的虚拟 tick 数，至少为 1
	 */
	public int takeBatchTicks(int budget) {
		if (pendingVirtualTicks <= 0L) {
			return 1;
		}
		long taken = Math.min(pendingVirtualTicks, Math.max(1, budget));
		pendingVirtualTicks -= taken;
		return (int) taken;
	}

	/**
	 * 按当前游戏刻的自适应预算取出一批虚拟 tick。
	 * <br/>
	 * 未加速时银行中最多只有当前基础 ticker 入账的 1 tick，直接消费即可，
	 * 无需检查配置、TPS 采样和告警状态。只有存在真实加速挂账时才进入预算慢路径。
	 */
	int takeBatchTicksForGameTick(long currentTick) {
		if (pendingVirtualTicks <= 1L) {
			pendingVirtualTicks = 0L;
			return 1;
		}
		return takeBatchTicks(getBatchBudget(currentTick));
	}

	/** 每真实 gameTick 批量执行预算上限（见 {@link #takeBatchTicks}） */
	public static int getMaxBatchTicks() {
		return MAX_BATCH_TICKS;
	}

	/**
	 * 直接向虚拟 tick 银行入账（JDTE {@code CoalescedAcceleratedMachine.accumulateAcceleratedTicks} 路径）。
	 * <br/>
	 * 与 {@link #onTick} / {@link #onAe2Tick} 的按调用入账不同，本方法按 JDTE 传入的虚拟 tick 数入账，
	 * 不修改 ticker 调用计数（不影响 multiplier 的 skip 判定）。
	 *
	 * @param ticks 虚拟 tick 数（≤0 忽略）
	 */
	public void addVirtualTicks(int ticks) {
		if (ticks > 0 && pendingVirtualTicks < MAX_PENDING_TICKS) {
			pendingVirtualTicks = Math.min(MAX_PENDING_TICKS, pendingVirtualTicks + ticks);
		}
	}

	/**
	 * 计算本 gameTick 的批量执行预算（配置上限 × TPS 自适应因子）。
	 * <br/>
	 * 每 {@link #BUDGET_REFRESH_INTERVAL}（10 tick）刷新一次配置上限；MSPT 因子每个
	 * 真实游戏刻刷新，使 1024 默认预算能在出现负载尖峰后立即降级：
	 * <ul>
	 *   <li>健康服务器（avgMSPT ≤ 50ms）：满额 = 配置上限（默认 1024）</li>
	 *   <li>轻微卡顿（50-100ms）：线性降级到 10%</li>
	 *   <li>严重卡顿（>100ms）：保底 10%（最低 1）</li>
	 * </ul>
	 * 与 JDTE 固定预算哲学互补：高 MSPT 时自动降低单 tick 批量，缓解服务器压力。
	 * <p>
	 * 银行饱和（连续多 gameTick 入账等于上限）时输出节流 WARN，提示玩家加速倍率
	 * 已超过预算上限、产能被降级（每 600 tick 最多一次）。
	 *
	 * @param currentTick 当前游戏刻
	 * @return 批量预算（范围 [1, 配置上限]）
	 */
	public int getBatchBudget(long currentTick) {
		if (isIntervalElapsed(currentTick, lastBudgetRefreshTick, BUDGET_REFRESH_INTERVAL)) {
			lastBudgetRefreshTick = currentTick;
			int max = MAX_BATCH_TICKS;
			try {
				if (ModConfig.SERVER_SPEC.isLoaded()) {
					max = Math.max(1, Math.min(1024, ModConfig.SERVER.maxBatchTicksPerTick.get()));
				}
			} catch (RuntimeException ignored) {
				// 配置未就绪时使用内置默认
			}
			cachedConfiguredMaxBatchTicks = max;
		}
		double factor = ServerTickTimeMonitor.getInstance().getTpsFactor(currentTick);
		cachedBatchBudget = Math.max(1, Math.min(cachedConfiguredMaxBatchTicks,
				SaturatingMath.saturatingCeilToInt(cachedConfiguredMaxBatchTicks * factor)));
		if (pendingVirtualTicks >= MAX_PENDING_TICKS
				&& isIntervalElapsed(currentTick, lastSaturationWarnTick, SATURATION_WARN_INTERVAL)) {
			lastSaturationWarnTick = currentTick;
			LogThrottle.warn("tick_bank_saturated",
					"虚拟 tick 银行已饱和（{}），加速倍率超过每刻预算 {}，产能被限制为预算值；"
							+ "可在配置 maxBatchTicksPerTick 中调高上限",
					MAX_PENDING_TICKS, cachedBatchBudget);
		}
		return cachedBatchBudget;
	}

	/** Handles first use and game-time rollback without subtracting from {@link Long#MIN_VALUE}. */
	static boolean isIntervalElapsed(long currentTick, long lastTick, long interval) {
		return lastTick == Long.MIN_VALUE || currentTick < lastTick || currentTick - lastTick >= interval;
	}

	/** 当前挂账的虚拟 tick 数（仅调试/测试用） */
	public long getPendingVirtualTicks() {
		return pendingVirtualTicks;
	}

	/**
	 * 获取未截断的原始调用次数（仅用于调试/测试）
	 * <br/>
	 * 返回当前游戏刻内的实际调用次数，未经过 MAX_MULTIPLIER 截断。
	 * 生产代码应使用 {@link #getMultiplier()}。
	 *
	 * @return 当前游戏刻内的原始调用次数（可能大于 1024）
	 */
	public int getRawCallCount() {
		return callsInCurrentTick;
	}

	/**
	 * 重置追踪状态（用于服务器停止/维度切换）
	 * <br/>
	 * 将 lastGameTick 重置为 Long.MIN_VALUE，callsInCurrentTick 重置为 0。
	 * 下次 {@link #onTick} 调用会重新初始化计数。
	 */
	public void reset() {
		lastGameTick = Long.MIN_VALUE;
		callsInCurrentTick = 0;
		callsInPreviousTick = 1;
		pendingVirtualTicks = 0L;
		lastBudgetRefreshTick = Long.MIN_VALUE;
		cachedBatchBudget = MAX_BATCH_TICKS;
		cachedConfiguredMaxBatchTicks = MAX_BATCH_TICKS;
		lastSaturationWarnTick = Long.MIN_VALUE;
	}
}
