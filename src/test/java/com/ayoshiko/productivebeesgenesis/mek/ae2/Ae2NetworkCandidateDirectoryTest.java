package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2NetworkCandidateDirectoryTest {

	@AfterEach
	void resetDirectory() {
		Ae2NetworkCandidateDirectory.resetForTest();
	}

	@Test
	void refreshStateSharesOneGenerationWithinTheWindow() {
		Object inventory = new Object();
		Ae2NetworkCandidateDirectory.RefreshState state =
				new Ae2NetworkCandidateDirectory.RefreshState();

		assertTrue(state.refresh(inventory, 100L));
		assertEquals(1L, state.generation());
		assertFalse(state.refresh(inventory, 100L));
		assertFalse(state.refresh(inventory, 109L));
		assertEquals(1L, state.generation());
		assertTrue(state.refresh(inventory, 110L));
		assertEquals(2L, state.generation());
	}

	@Test
	void refreshStateRebuildsForNewSnapshotAndClockRollback() {
		Ae2NetworkCandidateDirectory.RefreshState state =
				new Ae2NetworkCandidateDirectory.RefreshState();
		Object first = new Object();
		Object second = new Object();

		assertTrue(state.refresh(first, 40L));
		assertTrue(state.refresh(second, 41L));
		assertTrue(state.refresh(second, 10L));
		assertEquals(3L, state.generation());
	}

	@Test
	void pullerUsesSharedDirectoryButKeepsHostClassificationLocal() throws Exception {
		String puller = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java"));
		String normalized = puller.replaceAll("\\s+", " ");

		assertTrue(normalized.contains("Ae2NetworkCandidateDirectory.get( meStorage, availableStacks, "
				+ "currentTick, availableStacks)"));
		assertTrue(puller.contains("for (AEItemKey itemKey : networkDirectory.ordinaryKeys())"));
		assertTrue(puller.contains("for (AEItemKey itemKey : networkDirectory.combKeys())"));
		assertTrue(puller.contains("buffers.smeltingInputCache, tagGate, combGate"),
				"SMELTING、标签和本机可处理性必须保留在宿主本地分类");
	}

	@Test
	void serverStopClearsSharedNetworkState() throws Exception {
		String loader = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2IntegrationLoader.java"));
		String mod = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/ProductiveBeesGenesis.java"));

		assertTrue(loader.contains("Ae2NetworkCandidateDirectory.clearAll()"));
		assertTrue(loader.contains("Ae2NetworkWorkCoordinator.clearAll()"));
		assertTrue(mod.contains("Ae2IntegrationLoader::clearServerCaches"));
	}
}
