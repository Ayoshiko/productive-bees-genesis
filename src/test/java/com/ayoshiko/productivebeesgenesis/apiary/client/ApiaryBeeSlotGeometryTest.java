package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 蜜蜂槽几何命中判定回归
 * <br/>
 * 原实现是 O(N) 逐格 AABB 遍历，重构为"整除定位候选格 + 一次范围校验"。
 * 两者必须完全等价，否则会重现历史上"点击选中错格 / 蜜蜂偏移"类 Bug。
 * 本测试用与原实现相同的朴素算法作为参照实现逐点比对。
 */
class ApiaryBeeSlotGeometryTest {

	private static final int PITCH = ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP;

	@Test
	void hitTestMatchesNaiveScanForSingleRowLayout() {
		assertMatchesNaiveScan(176, 3, 1, ApiaryGuiLayoutHelper.getBeeRowH(1), 3);
	}

	@Test
	void hitTestMatchesNaiveScanForFactoryLayout() {
		assertMatchesNaiveScan(296, 5, 3, ApiaryGuiLayoutHelper.getBeeRowH(3), 15);
	}

	@Test
	void hitTestMatchesNaiveScanForCompactLayout() {
		// 5 行触发紧凑模式（行高 20 而非 28）
		assertMatchesNaiveScan(356, 12, 5, ApiaryGuiLayoutHelper.getBeeRowH(5), 60);
	}

	/** 最后一行不满时，越界槽位不得被判为命中 */
	@Test
	void hitTestRejectsIndexBeyondSlotCount() {
		int cols = 5;
		int rows = 3;
		int rowHeight = ApiaryGuiLayoutHelper.getBeeRowH(rows);
		// 13 个槽 = 2 整行 + 第三行 3 个，第三行第 4/5 格为空位
		ApiaryBeeSlotGeometry geometry = ApiaryBeeSlotGeometry.of(296, cols, rows, rowHeight, 13);
		int originX = ApiaryGuiLayoutHelper.getBeeX(296, cols);
		int originY = ApiaryGuiLayoutHelper.getBeeY(rows);
		double emptyCellX = originX + 3 * PITCH + 4;
		double emptyCellY = originY + 2 * rowHeight + 4;
		assertEquals(-1, geometry.hitTest(0, 0, emptyCellX, emptyCellY));
		// 同一行的第 3 格（索引 12）仍应命中
		assertEquals(12, geometry.hitTest(0, 0, originX + 2 * PITCH + 4, emptyCellY));
	}

	/** 空蜂箱（槽位数 0）任何坐标都不命中，且不得抛异常 */
	@Test
	void hitTestHandlesEmptyGrid() {
		ApiaryBeeSlotGeometry geometry = ApiaryBeeSlotGeometry.of(176, 3, 1, 28, 0);
		assertEquals(-1, geometry.hitTest(0, 0, 50, 50));
		assertEquals(0, geometry.getSlotCount());
	}

	/**
	 * 与朴素参照实现逐像素比对
	 * <br/>
	 * 扫描范围覆盖网格外 8px 边界，确保"槽间间距""网格左/上外侧"都判为未命中。
	 */
	private static void assertMatchesNaiveScan(int imageWidth, int cols, int rows, int rowHeight, int slotCount) {
		ApiaryBeeSlotGeometry geometry = ApiaryBeeSlotGeometry.of(imageWidth, cols, rows, rowHeight, slotCount);
		int originX = ApiaryGuiLayoutHelper.getBeeX(imageWidth, cols);
		int originY = ApiaryGuiLayoutHelper.getBeeY(rows);
		int gridRight = originX + cols * PITCH + 8;
		int gridBottom = originY + rows * rowHeight + 8;
		for (int y = Math.max(0, originY - 8); y < gridBottom; y++) {
			for (int x = Math.max(0, originX - 8); x < gridRight; x++) {
				int expected = naiveHitTest(originX, originY, cols, rowHeight, slotCount, x, y);
				assertEquals(expected, geometry.hitTest(0, 0, x, y),
						"坐标 (" + x + "," + y + ") 的命中结果与朴素实现不一致");
			}
		}
	}

	/** 原实现的逐格 AABB 遍历（参照实现） */
	private static int naiveHitTest(int originX, int originY, int cols, int rowHeight,
			int slotCount, double mouseX, double mouseY) {
		for (int i = 0; i < slotCount; i++) {
			int slotX = originX + (i % cols) * PITCH;
			int slotY = originY + (i / cols) * rowHeight;
			if (mouseX >= slotX && mouseX < slotX + ApiaryGuiLayoutHelper.SLOT
					&& mouseY >= slotY && mouseY < slotY + ApiaryGuiLayoutHelper.SLOT) {
				return i;
			}
		}
		return -1;
	}
}
