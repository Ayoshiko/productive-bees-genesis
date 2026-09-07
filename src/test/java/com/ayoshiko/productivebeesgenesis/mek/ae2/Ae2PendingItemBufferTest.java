package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2PendingItemBufferTest {

	@Test
	void persistenceUsesBoundedMergeAndRetryFields() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PendingItemBuffer.java"));
		assertTrue(source.contains("MAX_ENTRIES"));
		// 条目数有界即可约束 NBT 体积；数量不再设上限，否则会把 AE2 无限拉取吞吐压到 131K
		assertFalse(source.contains("MAX_TOTAL_AMOUNT"));
		assertTrue(source.contains("SaturatingMath.saturatingAdd(before, amount)"));
		assertTrue(source.contains("recordFailure"));
		assertTrue(source.contains("putString(KEY_FINGERPRINT"));
		assertTrue(source.contains("getList(Ae2NbtKeys.NBT_KEY_AE_PENDING_ITEMS"));
	}

	@Test
	void inputPullerDoesNotClampExtractRequestToBufferRoom() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java"));
		// 抽取量必须直接用槽位容量算出的 amount，不得再被 pending 缓冲额度截断
		assertTrue(source.contains("meStorage.extract(key, amount, Actionable.MODULATE, actionSource)"));
		// 抽取前的兜底闸门保留，但只在缓冲真的满了才付一次 SNBT 指纹编码去精确查重
		assertTrue(source.contains("if (!pending.hasFreeEntrySlot()"),
				"正常态（缓冲未满）必须走 O(1) 判定，不得为闸门无条件编码指纹");
		assertTrue(source.contains("&& !pending.canRegister(fingerprintCache.get(key, level.registryAccess())))"),
				"缓冲满时必须仍用指纹精确查重，避免抽出无处安放的物品");
		// 绝不掉落到世界：Containers 未被 import 即不可能调用 dropItemStack（注释中的说明不算）
		assertFalse(source.contains("import net.minecraft.world.Containers;"));
		assertFalse(source.contains("popResource"));
	}

	@Test
	void bufferExposesConstantTimeFreeSlotProbe() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PendingItemBuffer.java"));
		assertTrue(source.contains("public boolean hasFreeEntrySlot()"),
				"必须提供不需要指纹的 O(1) 空位判定");
		assertTrue(source.contains("return entries.size() < MAX_ENTRIES;"),
				"空位判定不得退化为线性扫描");
		assertTrue(source.contains("return hasFreeEntrySlot() || find(fingerprint) != null;"),
				"canRegister 必须复用同一空位判定，避免两处上限语义漂移");
	}

	@Test
	void leftoverFingerprintIsComputedAfterExtractWithLegacyFallback() throws Exception {
		String puller = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2InputPuller.java"));
		assertTrue(puller.contains("String pendingFingerprint = fingerprintCache.get(key, level.registryAccess());"),
				"指纹必须推迟到确实要登记剩余物时才计算");
		assertTrue(puller.contains("pending.enqueue(pendingFingerprint, leftoverRemaining, gameTick)"),
				"登记必须使用该指纹");

		String fingerprint = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2ItemFingerprint.java"));
		assertTrue(fingerprint.contains("public static String encodeOrLegacy("),
				"抽取后取指纹绝不能抛异常，必须有 legacy 兜底键");
		String cache = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2FingerprintCache.java"));
		assertTrue(cache.contains("Ae2ItemFingerprint.encodeOrLegacy(key, provider)"),
				"缓存层必须走兜底编码，否则 extract 之后抛异常等于丢物品");
	}
}
