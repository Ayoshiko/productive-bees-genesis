package com.ayoshiko.productivebeesgenesis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * 大数字紧凑格式单元测试 — 覆盖缩写边界与四舍五入进位
 */
class NumberFormatterTest {

	@Test
	void belowThousandStaysRaw() {
		assertEquals("0", NumberFormatter.formatCompact(0L));
		assertEquals("999", NumberFormatter.formatCompact(999L));
	}

	@Test
	void thousandUnitsUseSuffix() {
		assertEquals("1K", NumberFormatter.formatCompact(1000L));
		assertEquals("1.5K", NumberFormatter.formatCompact(1500L));
		assertEquals("999.9K", NumberFormatter.formatCompact(999_900L));
	}

	@Test
	void roundingCarriesToNextUnit() {
		// 修复前 999999 → "1000.0K"，修复后进位到 "1M"
		assertEquals("1M", NumberFormatter.formatCompact(999_999L));
		assertEquals("999.9K", NumberFormatter.formatCompact(999_949L));
		assertEquals("1.2M", NumberFormatter.formatCompact(1_234_567L));
	}

	@Test
	void extremeValuesStayCompact() {
		assertEquals("9.2E", NumberFormatter.formatCompact(Long.MAX_VALUE));
	}
}
