package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import java.util.Locale;

/**
	 * 大数字格式化工具 — 支持 K/M/G/T/P/E 缩写与千分位分隔两种模式
	 * <p>
	 * 格式化规则（缩写模式）：
	 * <ul>
	 *   <li>0~999：显示原始数字（如 "999"）</li>
	 *   <li>1K~999K：保留1位小数，整数千不显示小数（如 "1.5K"、"1K"）</li>
	 *   <li>1M~999M：如 "2.5M"</li>
	 *   <li>1G~999G：如 "3.5G"</li>
	 *   <li>1T~999T：如 "4.5T"</li>
	 *   <li>1P~999P：如 "5.5P"</li>
	 *   <li>1E+：如 "6.5E"</li>
	 * </ul>
	 * 非缩写模式：使用千分位分隔（如 "1,234,567"）。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP：仅负责数字到字符串的格式化转换</li>
	 *   <li>线程安全：纯静态方法、无可变状态，所有格式化均使用线程安全的 {@link String#format}</li>
	 *   <li>DIP：格式化模式由 {@link ModConfig#COMMON} 注入，不硬编码开关逻辑</li>
	 * </ul>
	 */
public final class NumberFormatter {

	/** 缩写单位 — 索引对应幂次（0=无、1=K、2=M、3=G、4=T、5=P、6=E） */
	private static final String[] SUFFIXES = {"", "K", "M", "G", "T", "P", "E"};

	/** 缩写模式下的最大单位索引（E） */
	private static final int MAX_SUFFIX_INDEX = SUFFIXES.length - 1;

	/** 每级单位的进率 */
	private static final long UNIT = 1000L;

	/** 整数判断阈值：误差范围内视为整数 */
	private static final double EPSILON = 1e-9;

	private NumberFormatter() {
		// 工具类禁止实例化
	}

	/**
	 * 格式化 long 值 — 根据 {@link ModConfig#COMMON} 中的配置选择缩写或千分位模式
	 * <br/>
	 * 线程安全：方法内仅使用局部变量与不可变常量，无共享可变状态。
	 *
	 * @param value 待格式化的数值（支持负数）
	 * @return 格式化后的字符串
	 */
	public static String format(long value) {
		if (isAbbreviationEnabled()) {
			return formatAbbreviated(value);
		}
		return formatGrouped(value);
	}

	/**
	 * 格式化 int 值 — 便捷重载，委托至 {@link #format(long)}
	 *
	 * @param value 待格式化的数值
	 * @return 格式化后的字符串
	 */
	public static String format(int value) {
		return format((long) value);
	}

	/** Always uses a compact suffix form suitable for a narrow item slot. */
	public static String formatCompact(long value) {
		return formatAbbreviated(value);
	}

	/**
	 * 判断缩写模式是否启用
	 * <br/>
	 * 配置未加载时（如客户端早期渲染阶段）安全降级为 true（默认启用缩写）。
	 *
	 * @return true 表示使用 K/M/G/T 缩写，false 表示使用千分位分隔
	 */
	private static boolean isAbbreviationEnabled() {
		try {
			return ModConfig.COMMON.enableLargeNumberAbbreviation.get();
		} catch (Exception e) {
			// 配置未加载时安全降级
			ProductiveBeesGenesis.LOGGER.debug("读取缩写模式配置失败，降级为默认启用", e);
			return true;
		}
	}

	/**
	 * 千分位分隔格式化 — 如 "1,234,567"
	 * <br/>
	 * 使用 {@link String#format(Locale, String, Object...)} 保证线程安全
	 * （每次调用创建独立的 Formatter 实例，无共享状态）。
	 *
	 * @param value 待格式化的数值
	 * @return 带千分位逗号的字符串
	 */
	private static String formatGrouped(long value) {
		return String.format(Locale.US, "%,d", value);
	}

	/**
	 * K/M/G/T 缩写格式化
	 * <br/>
	 * 原理：对绝对值逐级除以 1000，直到商小于 1000 或达到 T 级。
	 * 整数倍（如 2000 → 2K）不显示小数，非整数倍保留 1 位小数（如 1500 → 1.5K）。
	 * 负数先格式化绝对值再补 "-" 前缀。
	 *
	 * @param value 待格式化的数值
	 * @return 缩写格式字符串
	 */
	private static String formatAbbreviated(long value) {
		if (value == 0) {
			return "0";
		}

		boolean negative = value < 0;
		long absValue = negative ? -value : value;

		String formatted = formatPositiveAbbreviated(absValue);
		return negative ? "-" + formatted : formatted;
	}

	/**
	 * 格式化正数的缩写形式
	 *
	 * @param absValue 绝对值（保证 > 0）
	 * @return 缩写格式字符串
	 */
	private static String formatPositiveAbbreviated(long absValue) {
		// 小于 1000 直接返回原值
		if (absValue < UNIT) {
			return Long.toString(absValue);
		}

		// 计算单位层级
		int suffixIndex = 0;
		double scaled = absValue;
		while (scaled >= UNIT && suffixIndex < MAX_SUFFIX_INDEX) {
			scaled /= UNIT;
			suffixIndex++;
		}

		// 先按 1 位小数四舍五入，再处理进位（如 999999 → 999.999K → "1M" 而非 "1000.0K"）
		double rounded = Math.round(scaled * 10.0) / 10.0;
		if (rounded >= UNIT && suffixIndex < MAX_SUFFIX_INDEX) {
			rounded /= UNIT;
			suffixIndex++;
		}

		// 整数不显示小数（如 2000 → "2K" 而非 "2.0K"）
		if (isWholeNumber(rounded)) {
			return (long) rounded + SUFFIXES[suffixIndex];
		}

		// 非整数保留 1 位小数（如 1500 → "1.5K"）
		return String.format(Locale.US, "%.1f%s", rounded, SUFFIXES[suffixIndex]);
	}

	/**
	 * 判断浮点数是否为整数（在误差范围内）
	 *
	 * @param value 待判断的值
	 * @return true 如果 value 在 EPSILON 误差内为整数
	 */
	private static boolean isWholeNumber(double value) {
		double diff = value - Math.floor(value);
		return diff < EPSILON || (1.0 - diff) < EPSILON;
	}
}
