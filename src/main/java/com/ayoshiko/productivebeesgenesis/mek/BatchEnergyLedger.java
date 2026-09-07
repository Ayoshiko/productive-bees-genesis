package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

import java.util.Objects;
import java.util.function.LongConsumer;

/**
 * 记录一个加速批次内尚未写回能量容器的能耗。
 * <p>
 * 账本只在服务端主线程的单个批次内使用：虚拟 tick 期间累加待扣能量，
 * 批次结束时由外层统一写回 Mekanism 能量容器。
 */
public final class BatchEnergyLedger {

	private static final ThreadLocal<BatchEnergyLedger> ACTIVE = new ThreadLocal<>();

	private long pendingEnergy;

	/**
	 * 在当前线程打开延迟扣能作用域。
	 * <p>
	 * Mekanism 可能在完整 tick 内新建或替换 {@code CachedRecipe}；线程作用域使这些新缓存
	 * 无需依赖调用前快照，也能把首 tick 能耗写入同一账本。作用域必须通过 try-with-resources 关闭。
	 */
	public static Scope activate(BatchEnergyLedger ledger) {
		return new Scope(Objects.requireNonNull(ledger, "ledger"));
	}

	/** 返回当前线程正在使用的批次账本；不在批次中时返回 {@code null}。 */
	public static BatchEnergyLedger active() {
		return ACTIVE.get();
	}

	/** 清空当前批次的待扣能量。 */
	public void reset() {
		pendingEnergy = 0L;
	}

	/** 累加待扣能量，溢出时饱和到 {@link Long#MAX_VALUE}。 */
	public void add(long amount) {
		if (amount > 0L) {
			pendingEnergy = SaturatingMath.saturatingAdd(pendingEnergy, amount);
		}
	}

	/** 返回扣除本批次未提交能量后的可用本地能量。 */
	public long available(long storedEnergy) {
		return Math.max(0L, storedEnergy - pendingEnergy);
	}

	/** 将待扣能量一次性从本地容器提取。 */
	public void flush(MachineEnergyContainer<?> container) {
		flush(amount -> container.extract(amount, Action.EXECUTE, AutomationType.INTERNAL));
	}

	/**
	 * 将待扣能量一次性提交给调用方。
	 * <p>该重载用于不依赖 Mekanism 容器的纯单元测试，也便于未来替换能量后端。</p>
	 */
	public void flush(LongConsumer sink) {
		long amount = pendingEnergy;
		if (amount > 0L) {
			sink.accept(amount);
		}
		pendingEnergy = 0L;
	}

	/** 当前线程批次账本的可关闭作用域。 */
	public static final class Scope implements AutoCloseable {

		private final BatchEnergyLedger previous;
		private boolean closed;

		private Scope(BatchEnergyLedger ledger) {
			previous = ACTIVE.get();
			ACTIVE.set(ledger);
		}

		/** 恢复进入本作用域前的线程状态；重复关闭不会产生副作用。 */
		@Override
		public void close() {
			if (closed) return;
			closed = true;
			if (previous == null) {
				ACTIVE.remove();
			} else {
				ACTIVE.set(previous);
			}
		}
	}
}
