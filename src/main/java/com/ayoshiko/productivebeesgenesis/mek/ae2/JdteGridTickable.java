package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import com.ayoshiko.productivebeesgenesis.mek.TickAccelTracker;
import net.minecraft.world.level.Level;

/**
 * IGridTickable 实现：配合 JDTE Time Accelerator 的 AE2_GRID 兼容层
 * <br/>
 * JDTE 会高频调用 {@code tickingRequest(node, 1)} 请求机器工作，
 * 每个游戏刻内通过 TickAccelTracker 记录 onAe2Tick 次数，用于机器刻批量执行 AE IO，
 * 同时缓存工作检查结果，避免高频重复计算。
 * 空闲时返回 SLOWER，由 AE2 调度 {@code TickingRequest(1,20)} 降低网格调度开销
 * （与 AE2LT ProxyTicker 对齐）。
 */
final class JdteGridTickable implements IGridTickable {

	private final IAe2OutputHostBase host;
	private final Ae2OutputStateHolder holder;
	private final TickAccelTracker tracker;
	private long lastWorkCheckTick = Long.MIN_VALUE;
	private boolean lastWorkResult;

	JdteGridTickable(IAe2OutputHostBase host) {
		this.host = host;
		// Cache holder/tracker at node-prepare time: JDTE can invoke tickingRequest
		// up to timeAcceleratorMaxExecutionsPerTick (4096) times per game tick, so
		// every interface dispatch avoided here matters.
		this.holder = host.productivebeesgenesis$getAe2StateHolder();
		this.tracker = holder == null ? null : holder.getTickAccelTracker();
	}

	@Override
	public TickingRequest getTickingRequest(IGridNode node) {
		// min 1 tick / max 20 ticks: URGENT while there is work, SLOWER when idle
		// (AE2LT AbstractGridRecipeMachineLogic parity: 20-tick idle poll reduces
		// grid scheduling overhead; the machine tick drives actual AE IO).
		return new TickingRequest(1, 20, false);
	}

	@Override
	public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
		// Feed the TickAccelTracker counter (<10ns per call) so the machine tick
		// batches its AE IO by the JDTE multiplier (perSlotQuota x M); AE IO is
		// deliberately not executed here to avoid hundreds of guard-chain calls
		// per game tick under acceleration.
		if (tracker != null) {
			Level level = host.productivebeesgenesis$getAe2Level();
			if (level != null) tracker.onAe2Tick(level);
		}
		return hasAe2Work() ? TickRateModulation.URGENT : TickRateModulation.SLOWER;
	}

	/** Work check cached once per game tick (JDTE may call tickingRequest thousands of times). */
	private boolean hasAe2Work() {
		Level level = host.productivebeesgenesis$getAe2Level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		if (tick != lastWorkCheckTick) {
			lastWorkCheckTick = tick;
			lastWorkResult = computeHasAe2Work();
		}
		return lastWorkResult;
	}

	private boolean computeHasAe2Work() {
		if (holder == null) return false;
		return holder.isInputPullEnabled() || host.productivebeesgenesis$hasOutputItems();
	}
}
