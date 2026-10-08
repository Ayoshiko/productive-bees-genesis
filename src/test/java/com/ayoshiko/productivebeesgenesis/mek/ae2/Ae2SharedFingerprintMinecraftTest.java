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
	void manyHostsEncodeOneExactKeyOnlyOnce() {
		var provider = mock(HolderLookup.Provider.class);
		var calls = new AtomicInteger();
		try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(any(), same(provider)))
					.thenAnswer(call -> "{sample:" + calls.incrementAndGet() + "}");
			for (int i = 0; i < 79; i++) {
				assertEquals("{sample:1}", new Ae2FingerprintCache().get(key("same"), provider));
			}
			assertEquals(1, calls.get());
			assertEquals("{sample:2}", new Ae2FingerprintCache().get(key("different"), provider));
		}
	}

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

	@Test
	void characterBudgetEvictsButNeverTruncatesReturnedFingerprints() {
		var provider = mock(HolderLookup.Provider.class);
		var cache = new Ae2FingerprintCache();
		var calls = new AtomicInteger();
		String large = "{" + "x".repeat(Ae2FingerprintCache.MAX_CACHED_CHARACTERS / 2);
		try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(any(), same(provider)))
					.thenAnswer(call -> { calls.incrementAndGet(); return large; });
			assertEquals(large, cache.get(key("first"), provider));
			assertEquals(large, cache.get(key("second"), provider));
			assertEquals(large, cache.get(key("first"), provider));
			assertEquals(3, calls.get());
			String oversized = large + large;
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(eq(key("oversized")), same(provider))).thenReturn(oversized);
			assertEquals(oversized, cache.get(key("oversized"), provider));
		}
	}

	@Test
	void entryBudgetAndTransientLegacyResultsAreBounded() {
		var provider = mock(HolderLookup.Provider.class);
		var cache = new Ae2FingerprintCache();
		var calls = new AtomicInteger();
		try (var codec = mockStatic(Ae2ItemFingerprint.class)) {
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(any(), same(provider)))
					.thenAnswer(call -> "{entry:" + calls.incrementAndGet() + "}");
			for (int i = 0; i <= Ae2FingerprintCache.MAX_ENTRIES; i++) cache.get(key("key_" + i), provider);
			cache.get(key("key_0"), provider);
			assertEquals(Ae2FingerprintCache.MAX_ENTRIES + 2, calls.get());
			codec.when(() -> Ae2ItemFingerprint.encodeOrLegacy(eq(key("legacy")), same(provider)))
					.thenReturn("legacy", "{recovered:1}");
			assertEquals("legacy", cache.get(key("legacy"), provider));
			assertEquals("{recovered:1}", cache.get(key("legacy"), provider));
		}
	}

	private static AEItemKey key(String name) {
		var stack = new ItemStack(Items.HONEYCOMB);
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return AEItemKey.of(stack);
	}
}
