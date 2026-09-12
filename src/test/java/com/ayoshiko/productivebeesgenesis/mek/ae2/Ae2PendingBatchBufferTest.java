package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Ae2PendingBatchBufferTest {

	@Test
	void windowMaturesAtConfiguredIntervalAndResetDefersNextScan() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();

		assertEquals(Ae2PendingBatchBuffer.RIPE_TICKS, buffer.getRipeTicksRemaining());
		for (int tick = 1; tick < Ae2PendingBatchBuffer.RIPE_TICKS; tick++) {
			buffer.tick();
			assertFalse(buffer.isWindowRipe());
		}

		buffer.tick();
		assertTrue(buffer.isWindowRipe(), "窗口必须按真实游戏刻推进到成熟");

		buffer.reset();
		assertFalse(buffer.isWindowRipe());
		assertEquals(Ae2PendingBatchBuffer.RIPE_TICKS, buffer.getRipeTicksRemaining());
	}

	@Test
	void windowIsNotRipeWithoutPendingFluidEvenWhenTimerElapsed() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		for (int tick = 0; tick < Ae2PendingBatchBuffer.RIPE_TICKS; tick++) {
			buffer.tick();
		}
		// 计时器到期但没有待推送流体：不应触发任何推送（否则每刻空转一次全网络遍历）
		assertTrue(buffer.isWindowRipe());
		assertFalse(buffer.isRipe());
	}

	@Test
	void tankSampleReusesOneMapAcrossFlushes() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		Object first = buffer.beginTankSample();
		// 空采样提交必须安全，且不得清空表（推送阶段仍要把它当 clamp 索引读）
		buffer.commitTankSample();
		Object second = buffer.beginTankSample();
		assertSame(first, second, "采样表必须跨 tick/跨轮复用，避免每刻分配 Map");
	}

	@Test
	void committingASampleNeverResetsTheMatureWindow() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		buffer.tick();
		buffer.tick();
		int before = buffer.getRipeTicksRemaining();
		buffer.beginTankSample();
		buffer.commitTankSample();
		// 参考 useless PendingAEBatch#add：持续产出时若每次都重置计时器，窗口永远不会成熟
		assertEquals(before, buffer.getRipeTicksRemaining(),
				"累积采样不得重置成熟窗口");
	}

	@Test
	void emptyBufferReportsNoPendingFluidSoNothingIsPushed() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		assertEquals(0L, buffer.getTotalAmount());
		assertEquals(0, buffer.getKeyCount());
		buffer.commitTankSample();
		assertEquals(0L, buffer.getTotalAmount(), "空采样不得抬高待推送总量");
	}

	@Test
	void resetClearsPendingTotalAndKeepsReusingTheSampleMap() {
		Ae2PendingBatchBuffer buffer = new Ae2PendingBatchBuffer();
		buffer.beginTankSample();
		buffer.commitTankSample();
		buffer.reset();
		assertEquals(0L, buffer.getTotalAmount(), "重置后待推送总量必须归零");
		assertEquals(0, buffer.getKeyCount());
		Object first = buffer.beginTankSample();
		Object second = buffer.beginTankSample();
		assertSame(first, second);
	}
}
