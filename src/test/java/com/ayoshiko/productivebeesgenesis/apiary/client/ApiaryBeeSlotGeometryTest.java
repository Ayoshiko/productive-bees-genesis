package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiaryBeeSlotGeometryTest {

	private static final int PITCH = ApiaryGuiLayoutHelper.SLOT + ApiaryGuiLayoutHelper.GAP;

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

}
