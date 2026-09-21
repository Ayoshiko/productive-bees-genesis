package com.ayoshiko.productivebeesgenesis.apiary;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiaryItemContainerLayoutTest {

	@ParameterizedTest
	@ValueSource(ints = {3, 5, 10, 11, 12, 13, 14, 15, 17})
	void blockItemHasRoomForBothInputSlotsAcrossFactoryLayouts(int outputColumns) {
		int outputSlots = outputColumns * ApiaryGuiLayoutHelper.OUT_ROWS * 2;
		var creator = MekApiaryContainerRegistrar.buildApiaryItemSlots(outputSlots);
		// 两页输出之外还须保留蜂笼输入/输出、能量和小食，避免拆机时截掉末槽。
		assertEquals(outputSlots + 4, creator.totalContainers());
	}
}
