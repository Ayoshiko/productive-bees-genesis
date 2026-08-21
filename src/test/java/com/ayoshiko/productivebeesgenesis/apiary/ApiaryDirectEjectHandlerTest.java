package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import mekanism.api.AutomationType;
import org.junit.jupiter.api.Test;

class ApiaryDirectEjectHandlerTest {

	@Test
	void directTargetInsertionBypassesExternalStorageAdmissionPolicy() {
		assertEquals(AutomationType.INTERNAL,
				ApiaryDirectEjectHandler.TARGET_INSERT_AUTOMATION);
	}

	@Test
	void virtualStackTracksOnlyItemsThatActuallyLeaveSourceSlots() {
		assertEquals(10, ApiaryDirectEjectHandler.netRemovedFromSources(10, 0, 0));
		assertEquals(6, ApiaryDirectEjectHandler.netRemovedFromSources(10, 4, 0));
		assertEquals(7, ApiaryDirectEjectHandler.netRemovedFromSources(10, 4, 1));
	}

	@Test
	void allRejectedAndRestoredLeavesNothingRemoved() {
		// 目标全部拒收且全部归还源槽：源槽净减少为 0，虚拟栈不应收缩
		assertEquals(0, ApiaryDirectEjectHandler.netRemovedFromSources(10, 10, 0));
	}

	@Test
	void allRejectedButBufferedMeansEverythingLeavesSources() {
		// 目标全部拒收且源槽无法容纳（进入缓冲区）：源槽净减少 = 提取总数
		assertEquals(10, ApiaryDirectEjectHandler.netRemovedFromSources(10, 10, 10));
	}

	@Test
	void mixedOutcomeAccountingNeverExceedsExtractedTotal() {
		// 插入 4 + 归还 2 + 缓冲 4 = 10 = 提取总数，无凭空消失
		assertEquals(8, ApiaryDirectEjectHandler.netRemovedFromSources(10, 6, 4));
	}

	@Test
	void negativeOrInconsistentInputsAreClampedToZero() {
		assertEquals(0, ApiaryDirectEjectHandler.netRemovedFromSources(-3, 0, 0));
		assertEquals(0, ApiaryDirectEjectHandler.netRemovedFromSources(0, 5, 2));
		// rejectedByTarget 超提取总数时按提取总数钳制（防御性，正常流程 R ≤ E）
		assertEquals(0, ApiaryDirectEjectHandler.netRemovedFromSources(10, 12, 0));
	}
}
