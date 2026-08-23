package com.ayoshiko.productivebeesgenesis.mek.ae2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Ae2PerTileStateNbtCodecTest {

	@Test
	void configurationCardCodecWiresDisabledDirectOutputBothWays() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/mek/ae2/Ae2PerTileStateNbtCodec.java"));

		assertTrue(source.contains("tag.putBoolean(Ae2NbtKeys.NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT, "
				+ "holder.isCentrifugeDirectAeOutputEnabled())"));
		assertTrue(source.contains("holder.setCentrifugeDirectAeOutputEnabled("));
		assertTrue(source.contains("tag.contains(Ae2NbtKeys.NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT)"));
	}
}
