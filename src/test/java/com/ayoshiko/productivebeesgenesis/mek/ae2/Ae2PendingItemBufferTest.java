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
		assertTrue(source.contains("pending.canRegister(fingerprint)"));
		// 绝不掉落到世界：Containers 未被 import 即不可能调用 dropItemStack（注释中的说明不算）
		assertFalse(source.contains("import net.minecraft.world.Containers;"));
		assertFalse(source.contains("popResource"));
	}
}
