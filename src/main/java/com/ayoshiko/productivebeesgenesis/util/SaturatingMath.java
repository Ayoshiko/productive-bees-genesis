package com.ayoshiko.productivebeesgenesis.util;

/**
	 * 饱和运算工具类 — 防止 long/int 在大数值乘法/加法中溢出。
	 * <br/>
	 * <b>设计动机</b>：256× 加速 + 堆叠插件场景下，{@code baseRate * M * processCount * tpsFactor}
	 * 等链式乘法极易溢出 long 范围（如 64 × 256 × 19 × 1.0 ≈ 311k，看似安全，但叠加
	 * 多级因子后 {@code baseRate * M * processCount * tpsFactor * extraFactor} 可达 10^18+，
	 * 接近 long 上限 9.2×10^18）。溢出后会变成负数，导致下游槽位容量计算错误、
	 * 物品/流体丢失等严重数据完整性问题。
	 * <p>
	 * <b>饱和语义</b>：运算结果溢出时返回类型的最大值（{@link Long#MAX_VALUE} 或
	 * {@link Integer#MAX_VALUE}），而非环绕为负数。下游消费者（如 {@code Math.min}、
	 * 槽位容量限制）天然能处理 "极大值" 的语义，从而保证正确性。
	 * <p>
	 * <b>参考</b>：参考自 Useless Mod 的 {@code saturatingMultiply} 实现，剔除其针对
	 * HighStack 的特化逻辑，仅保留通用的 long/int 饱和运算。
	 * <p>
	 * <b>线程安全</b>：所有方法为纯函数（无状态），任意线程并发调用安全。
	 *
	 * @since 2.0.0
	 */
public final class SaturatingMath {

	private SaturatingMath() {}

	/**
	 * 饱和乘法（long × long → long）。
	 * <br/>
	 * <b>溢出检测策略</b>：使用 {@link Math#multiplyExact(long, long)} 抛出 ArithmeticException
	 * 检测溢出，捕获后返回 {@link Long#MAX_VALUE}。相比手动位运算检测，JDK 内置实现更可靠
	 * 且 HotSpot 会内联为高效的本机指令。
	 * <p>
	 * <b>零值短路</b>：任一操作数为 0 时直接返回 0，避免不必要的异常抛出开销
	 * （256× 加速下 {@code M=0} 的边缘场景可能高频出现）。
	 * <p>
	 * <b>负数处理</b>：任一操作数为负数时返回 0。本项目所有合法调用场景
	 * （baseRate、M、processCount、tpsFactor、insertedAmount）均为非负值，
	 * 负数视为非法输入并短路，避免 {@code multiplyExact} 对负数乘正数得负数
	 * 但未溢出的误判。
	 *
	 * @param a 操作数 1（必须 >= 0）
	 * @param b 操作数 2（必须 >= 0）
	 * @return a × b，溢出时返回 {@link Long#MAX_VALUE}，任一为 0 或负数返回 0
	 */
	public static long saturatingMultiply(long a, long b) {
		if (a <= 0 || b <= 0) return 0;
		return a > Long.MAX_VALUE / b ? Long.MAX_VALUE : a * b;
	}

	/**
	 * 饱和加法（long + long → long）。
	 * <br/>
	 * <b>溢出检测策略</b>：使用 {@link Math#addExact(long, long)} 检测溢出。
	 * <p>
	 * <b>负数处理</b>：任一操作数为负数时返回 0（与 {@link #saturatingMultiply} 对称）。
	 * 本项目场景中累加值均为非负（槽位容量、推送量），负数视为非法输入。
	 *
	 * @param a 操作数 1（必须 >= 0）
	 * @param b 操作数 2（必须 >= 0）
	 * @return a + b，溢出时返回 {@link Long#MAX_VALUE}，任一为负数返回 0
	 */
	public static long saturatingAdd(long a, long b) {
		if (a < 0 || b < 0) return 0;
		return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b;
	}

	/**
	 * 饱和 long → int 转换。
	 * <br/>
	 * <b>用途</b>：替换散布在 AE2 推送/拉取代码中的
	 * {@code (int) Math.min(x, Integer.MAX_VALUE)} 模式，统一语义。
	 * <p>
	 * <b>负数处理</b>：负数视为非法输入返回 0（与 {@link #saturatingMultiply} 对称）。
	 *
	 * @param value 待转换的 long 值
	 * @return 转换为 int 的值，超过 {@link Integer#MAX_VALUE} 返回 {@link Integer#MAX_VALUE}，
	 *         负数返回 0
	 */
	public static int saturatingToInt(long value) {
		if (value < 0) return 0;
		if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int) value;
	}

	/**
	 * 饱和三参数乘法（long × long × long → long）。
	 * <br/>
	 * <b>用途</b>：简化 {@code baseRate × M × processCount} 等三参数链式乘法的调用，
	 * 单次方法调用内部使用 {@link #saturatingMultiply} 链式计算，避免调用方写两行。
	 *
	 * @param a 操作数 1
	 * @param b 操作数 2
	 * @param c 操作数 3
	 * @return a × b × c，溢出时返回 {@link Long#MAX_VALUE}
	 */
	public static long saturatingMultiply(long a, long b, long c) {
		return saturatingMultiply(saturatingMultiply(a, b), c);
	}

	/** Clamps an untrusted external result to the non-negative requested amount. */
	public static long clampToRequest(long result, long requested) {
		if (result <= 0 || requested <= 0) return 0L;
		return Math.min(result, requested);
	}

	/** Returns {@code 2^exponent}, saturating at the largest positive int. */
	public static int saturatingPowerOfTwo(int exponent) {
		if (exponent <= 0) return 1;
		if (exponent >= 31) return Integer.MAX_VALUE;
		return 1 << exponent;
	}

	/** Converts a positive floating-point quantity to a long without NaN or infinity leaks. */
	public static long saturatingCeilToLong(double value) {
		if (Double.isNaN(value) || value <= 0.0D) return 0L;
		if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return Long.MAX_VALUE;
		return (long) Math.ceil(value);
	}

	/** Converts a positive floating-point quantity using {@link Math#round(double)} semantics. */
	public static long saturatingRoundToLong(double value) {
		if (Double.isNaN(value) || value <= 0.0D) return 0L;
		if (!Double.isFinite(value) || value >= Long.MAX_VALUE) return Long.MAX_VALUE;
		return Math.round(value);
	}

	/** Converts a positive floating-point quantity to an int using ceil semantics. */
	public static int saturatingCeilToInt(double value) {
		return saturatingToInt(saturatingCeilToLong(value));
	}

	/** Converts a positive floating-point quantity to an int using round semantics. */
	public static int saturatingRoundToInt(double value) {
		return saturatingToInt(saturatingRoundToLong(value));
	}

	/** Saturating non-negative int addition. */
	public static int saturatingAddToInt(int a, int b) {
		return saturatingToInt(saturatingAdd(Math.max(0, a), Math.max(0, b)));
	}

	/** Converts a positive double to float while preventing NaN, infinity and zero. */
	public static float positiveFiniteFloat(double value, float fallback) {
		if (Double.isNaN(value) || value <= 0.0D) return fallback;
		if (!Double.isFinite(value) || value >= Float.MAX_VALUE) return Float.MAX_VALUE;
		float converted = (float) value;
		return converted > 0.0f && Float.isFinite(converted) ? converted : fallback;
	}
}
