package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2NetworkCostWiringTest {

	private static String read(String name) throws Exception {
		return Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/" + name));
	}

	@Test
	void productionPathsDoNotUseLegacyGlobalHardGate() throws Exception {
		for (String name : new String[] {
				"Ae2InputPuller.java", "Ae2OutputMergedPass.java", "Ae2OutputSlotPass.java",
				"Ae2DirectItemPushSession.java", "Ae2FluidPusher.java"}) {
			assertFalse(read(name).contains("Ae2GlobalInsertBudget.isExhausted("),
					name + " 不得让一个网络耗尽旧全服预算后阻塞其它网络");
		}
	}

	@Test
	void exceptionalExtractAndFluidInsertStillTeachNetworkCoordinator() throws Exception {
		String input = read("Ae2InputPuller.java").replaceAll("\\s+", " ");
		assertTrue(input.contains("catch (LinkageError | RuntimeException error) { long extractCost"));
		assertTrue(input.contains("holder.recordNetworkCost(meStorage, gameTick, extractCost"));

		String fluid = read("Ae2FluidPusher.java").replaceAll("\\s+", " ");
		assertTrue(fluid.contains("catch (RuntimeException error) { long insertCost"));
		assertTrue(fluid.contains("holder.recordNetworkCost(meStorage, gameTick, insertCost"));
	}

	@Test
	void leftoverCompensationRecordsBothSimulateAndCommitCost() throws Exception {
		String source = read("Ae2LeftoverReturner.java");
		assertTrue(source.contains("timedInsert(holder, meStorage, key, remaining,"));
		assertTrue(source.contains("timedInsert(holder, meStorage, key, target,"));
		assertTrue(source.contains("holder.recordNetworkCost(meStorage, gameTick, cost,"));
		assertTrue(source.contains("buffers.insertCostTracker.record(gameTick, cost)"));
	}
}
