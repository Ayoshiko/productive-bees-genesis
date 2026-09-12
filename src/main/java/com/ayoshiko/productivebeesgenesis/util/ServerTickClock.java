package com.ayoshiko.productivebeesgenesis.util;

/**
 * 服务端游戏刻时钟
 * <br/>
 * 为拿不到 {@code Level} 的组件（例如 Mekanism 的槽位对象）提供「当前服务端刻」，
 * 用于判定时间窗口。由 {@code ServerTickEvent.Post} 每刻推进一次。
 * <p>
 * 采用自增计数而非 {@code Level#getGameTime()}：槽位无法访问世界，且自增 long
 * 既不会像 {@code MinecraftServer#getTickCount()}（int）那样溢出，也保证单调递增。
 * <p>
 * 读取方必须先确认 {@link #isSet()}：未设值时任何时间差判断都没有意义，
 * 调用方应把该状态视为「无有效窗口」而不是「窗口无限长」。
 */
public final class ServerTickClock {

	/** 未初始化哨兵；同时覆盖服务器启动前的读取。 */
	public static final long UNSET = Long.MIN_VALUE;

	private static volatile long currentTick = UNSET;

	private ServerTickClock() {
	}

	/** 推进一刻（由 ServerTickEvent.Post 调用）。 */
	public static void tick() {
		long current = currentTick;
		currentTick = current == UNSET ? 1L : current + 1L;
	}

	/** 复位（服务器停止时调用），避免跨存档读数残留。 */
	public static void reset() {
		currentTick = UNSET;
	}

	/** 当前服务端刻；未初始化时为 {@link #UNSET}。 */
	public static long now() {
		return currentTick;
	}

	/** 是否已被服务端 tick 推进过。 */
	public static boolean isSet() {
		return currentTick != UNSET;
	}
}
