package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class Ae2OutputPusherTest {

	@Test
	void mergesTwoOrMoreSlotsToReduceNetworkInsertCalls() {
		assertFalse(Ae2OutputMergePolicy.shouldMergeEntries(1));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(2));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(3));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(4));
		assertTrue(Ae2OutputMergePolicy.shouldMergeEntries(8));
	}

	@Test
	void outputPathContainsConfirmationLedgerAndFingerprintGuard() throws Exception {
		// 提交语义已拆到 Ae2OutputCommitter（Ae2OutputPusher 只保留编排），账本三段式必须完整保留
		String committer = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2OutputCommitter.java"));
		assertTrue(committer.contains("ledger.reserve"));
		assertTrue(committer.contains("ledger.confirm"));
		assertTrue(committer.contains("output_ledger_conflict"));
		// 编排层必须在开关判定之前先结算账本，否则关闭输出会留下未扣减窗口
		String pusher = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2OutputPusher.java"));
		assertTrue(pusher.contains("Ae2OutputCommitter.settleOutputLedger"));
	}

	@Test
	void splitKeepsEveryFileUnderTheSizeThreshold() throws Exception {
		String[] files = {
				"Ae2OutputPusher.java", "Ae2OutputCommitter.java", "Ae2OutputSlotPass.java",
				"Ae2OutputMergedPass.java", "Ae2DirectItemPushSession.java", "Ae2PushBuffers.java",
				"Ae2PushLimits.java", "Ae2SlotEntry.java", "Ae2PullCandidateAmounts.java",
				"Ae2OutputBackoffLog.java", "Ae2PushExceptionLog.java", "Ae2OutputPushContext.java",
		};
		for (String name : files) {
			long lines = Files.readAllLines(Path.of(
					"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/" + name)).size();
			assertTrue(lines <= 500, name + " 已达 " + lines + " 行，超过 500 行拆分阈值");
		}
	}
}
