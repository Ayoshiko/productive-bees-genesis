package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import java.util.List;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2LazyFingerprintMinecraftTest {
	@Test
	void scanningMoreThanCacheCapacityDoesNotEncodeDeferredEntries() {
		var buffers = new Ae2PushBuffers();
		var registries = mock(HolderLookup.Provider.class);
		for (int i = 0; i < 192; i++) {
			var slot = BasicInventorySlot.at(null, 0, 0);
			var stack = new ItemStack(Items.HONEYCOMB);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("type_" + i));
			slot.setStack(stack);
			Ae2OutputCommitter.collectSlot(buffers, i, 0, slot, null, registries);
		}
		assertEquals(192, buffers.entries.size());
		assertTrue(buffers.entries.stream().allMatch(entry -> entry.fingerprint == null));
		verifyNoInteractions(registries);
	}

	@Test
	void selectedEntriesEncodeBeforeReservationAndPoolReuseResetsTheFingerprint() {
		var cache = mock(Ae2FingerprintCache.class);
		var registries = mock(HolderLookup.Provider.class);
		var key = AEItemKey.of(Items.IRON_INGOT);
		var slot = BasicInventorySlot.at(null, 0, 0);
		slot.setStack(new ItemStack(Items.IRON_INGOT, 20));
		var entry = new Ae2SlotEntry();
		entry.set(slot, slot.getStack(), key, 20, 0, 0, cache, registries);
		verifyNoInteractions(cache);
		when(cache.get(key, registries)).thenReturn("exact-iron-fingerprint");
		var ledger = new Ae2OutputLedger();
		var network = mock(MEStorage.class);
		var source = IActionSource.empty();
		when(network.insert(key, 20L, Actionable.MODULATE, source)).thenAnswer(call -> {
			assertEquals("exact-iron-fingerprint", entry.fingerprint);
			verify(cache).get(key, registries);
			return 7L;
		});
		assertEquals(7, Ae2OutputCommitter.pushBatchKey(key, 20, List.of(entry), network, source, ledger));
		assertEquals(13, slot.getCount());
		assertTrue(ledger.snapshot().isEmpty());
		assertEquals("exact-iron-fingerprint", entry.fingerprint());
		verify(cache, times(1)).get(key, registries);

		var gold = AEItemKey.of(Items.GOLD_INGOT);
		entry.set(slot, new ItemStack(Items.GOLD_INGOT), gold, 1, 0, 0, cache, registries);
		assertNull(entry.fingerprint);
		when(cache.get(gold, registries)).thenReturn("exact-gold-fingerprint");
		assertEquals("exact-gold-fingerprint", entry.fingerprint());
	}
}
