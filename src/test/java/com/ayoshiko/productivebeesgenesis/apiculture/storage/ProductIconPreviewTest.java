package com.ayoshiko.productivebeesgenesis.apiculture.storage;

import java.util.Base64;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProductIconPreviewTest {
	@Test void completeBoundedComponentsRoundTripWithoutMutatingIdentity() {
		var tag = new CompoundTag();
		tag.putString("productivebees:bee_type", "productivebees:gold");
		tag.putInt("minecraft:custom_model_data", 42);
		tag.putString("minecraft:custom_name", "\"金蜜脾\"");
		var encoded = ProductIconPreview.capture(tag);
		assertFalse(encoded.isEmpty());
		assertEquals(tag, ProductIconPreview.decode(encoded));
		ProductIconPreview.decode(encoded).putInt("minecraft:custom_model_data", 99);
		assertEquals(42, tag.getInt("minecraft:custom_model_data"));
	}
	@Test void hugeWideAndDeepDataHaveNoPartialPreview() {
		var tag = new CompoundTag(); tag.putByteArray("inventory", new byte[1_000_000]);
		assertEquals("", ProductIconPreview.capture(tag));
		tag = new CompoundTag();
		for (int i = 0; i < 65; i++) tag.putInt("x" + i, i);
		assertEquals("", ProductIconPreview.capture(tag));
		tag = new CompoundTag(); var cursor = tag;
		for (int i = 0; i < 9; i++) { var child = new CompoundTag(); cursor.put("child", child); cursor = child; }
		assertEquals("", ProductIconPreview.capture(tag));
	}
	@Test void encodedBytesAndUtfCostRemainBounded() {
		var tag = new CompoundTag(); tag.putString("name", "蜂".repeat(256));
		assertEquals("", ProductIconPreview.capture(tag));
		for (int size = 0; size <= 256; size++) {
			tag.putString("name", "蜂".repeat(size)); var encoded = ProductIconPreview.capture(tag);
			if (!encoded.isEmpty()) {
				assertTrue(encoded.length() <= ProductIconPreview.MAX_TEXT);
				assertTrue(Base64.getDecoder().decode(encoded).length <= ProductIconPreview.MAX_BYTES);
				assertEquals(tag, ProductIconPreview.decode(encoded));
			}
		}
	}
	@Test void malformedOrTrailingDataCannotProduceAnIcon() {
		assertThrows(IllegalArgumentException.class, () -> ProductIconPreview.decode("?"));
		assertThrows(IllegalArgumentException.class, () -> ProductIconPreview.decode("a".repeat(685)));
		var tag = new CompoundTag(); tag.putInt("x", 7);
		byte[] bytes = Base64.getDecoder().decode(ProductIconPreview.capture(tag));
		var extra = java.util.Arrays.copyOf(bytes, bytes.length + 1);
		assertThrows(IllegalArgumentException.class, () -> ProductIconPreview.decode(Base64.getEncoder().encodeToString(extra)));
	}
}
