package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2SharedFingerprintMinecraftTest {
	@BeforeEach @AfterEach
	void reset() { Ae2FingerprintCache.clearShared(); }

	@Test
	void actualCodecRoundTripKeepsComponentsAcrossHosts() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		AEItemKey key = key("full components");
		String encoded = new Ae2FingerprintCache().get(key, provider);
		assertEquals(Ae2ItemFingerprint.encode(key, provider), encoded);
		assertEquals(key, Ae2ItemFingerprint.decode(encoded, provider));
		assertSame(encoded, new Ae2FingerprintCache().get(key("full components"), provider));
	}

	@Test
	void providerReloadAndStopInvalidateSharedResults() {
		var provider = mock(HolderLookup.Provider.class);
		var cache = new Ae2FingerprintCache();
		var calls = new AtomicInteger();
		try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(any(), any()))
					.thenAnswer(call -> "{generation:" + calls.incrementAndGet() + "}");
			assertEquals("{generation:1}", cache.get(key("same"), provider));
			ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
			assertEquals("{generation:2}", cache.get(key("same"), provider));
			assertEquals("{generation:3}", cache.get(key("same"), mock(HolderLookup.Provider.class)));
			Ae2FingerprintCache.clearShared();
			assertEquals("{generation:4}", cache.get(key("same"), provider));
		}
	}

	private static AEItemKey key(String name) {
		var stack = new ItemStack(Items.HONEYCOMB);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return AEItemKey.of(stack);
	}
}
