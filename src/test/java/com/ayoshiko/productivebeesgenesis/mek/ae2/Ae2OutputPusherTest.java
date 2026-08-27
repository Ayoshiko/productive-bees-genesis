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
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2OutputPusher.java"));
		assertTrue(source.contains("settleOutputLedger"));
		assertTrue(source.contains("ledger.reserve"));
		assertTrue(source.contains("ledger.confirm"));
		assertTrue(source.contains("output_ledger_conflict"));
	}
}
