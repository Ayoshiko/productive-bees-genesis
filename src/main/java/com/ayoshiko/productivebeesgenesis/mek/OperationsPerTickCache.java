package com.ayoshiko.productivebeesgenesis.mek;

import java.util.function.IntSupplier;

/**
 * 每游戏刻记忆化的 {@code operationsPerTick} 缓存。
 * <p>
 * 动机（spark XnLugba3Cw）：{@code TileEntityEMExtraMekCentrifugeFactory.operationsPerTick}
 * 自身耗时 776ms / 2.59%，是本模组在该采样里的最大热点。原因是它被
 * {@code CachedRecipe.setBaselineMaxOperations} 持有为 {@link IntSupplier}，
 * Mekanism 的 {@code CachedRecipe.process()} 每次完整计算都会调用一次，而
 * JDTE 时间加速器 / 时间手杖把 {@code process()} 放大到每真实刻 1024 次 × 每 lane。
 * 单次调用的成本并不低：两次 {@code TileComponentUpgrade.getUpgrades}（STACK + CREATIVE）
 * 加上 {@code MekanismUtils.getOperationsPerTick} 内的 {@code Math.pow} 与配置读取。
 * <p>
 * 升级数量只会在 {@code recalculateUpgrades} 时变化，一个游戏刻内恒定，
 * 因此按 gameTick 记忆化在语义上无损：同一刻的所有虚拟 tick 本就该看到同一个值。
 * 调用次数从 M（1024）降为 1。
 * <p>
 * 线程安全：方块实体只在服务端主线程 tick；字段用 volatile 发布，
 * 极端并发下最坏只是多算一次，不影响正确性。
 */
public final class OperationsPerTickCache {

	private volatile long cachedTick = Long.MIN_VALUE;
	private volatile int cachedOperations = 1;

	/**
	 * 返回本游戏刻的操作数，必要时用 compute 重算。
	 *
	 * @param gameTick 当前游戏刻；{@link Long#MIN_VALUE} 表示不可用（构造期 level 为 null），
	 *                 此时直接透传 compute 不缓存
	 * @param compute  真实计算逻辑
	 */
	public int get(long gameTick, IntSupplier compute) {
		if (gameTick == Long.MIN_VALUE) return compute.getAsInt();
		if (cachedTick == gameTick) return cachedOperations;
		int operations = compute.getAsInt();
		cachedOperations = operations;
		cachedTick = gameTick;
		return operations;
	}

	/** 升级数量变更（recalculateUpgrades）时立即失效，不等下一游戏刻。 */
	public void invalidate() {
		cachedTick = Long.MIN_VALUE;
	}
}
