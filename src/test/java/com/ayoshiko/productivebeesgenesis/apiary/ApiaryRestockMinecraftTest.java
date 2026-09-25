package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.*;

import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneGroup;
import cy.jdkdigital.productivebees.util.GeneValue;
import java.util.List;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class ApiaryRestockMinecraftTest {

	@Test
	void templateKeepsAllComponentsAfterEmptyingAndClearsOnlyWhenDisabled() {
		var state = new GeneTreatRestockState();
		var treat = treat(12, 100);
		assertFalse(state.isEnabled());
		assertFalse(state.observe(treat));
		state.setEnabled(true);
		assertTrue(state.observe(treat));
		assertEquals(1, state.template().getCount());
		assertFalse(state.observe(ItemStack.EMPTY));
		assertTrue(ItemStack.isSameItemSameComponents(treat, state.template()));
		var changedPurity = treat(4, 80);
		assertTrue(state.observe(changedPurity));
		changedPurity.set(DataComponents.CUSTOM_NAME, Component.literal("exact template"));
		assertTrue(state.observe(changedPurity));
		assertTrue(ItemStack.isSameItemSameComponents(changedPurity, state.template()));
		state.template().setCount(9);
		assertEquals(1, state.template().getCount());
		state.acceptExtracted(treat);
		state.setEnabled(false);
		assertTrue(state.template().isEmpty());
		assertTrue(state.hasPending());
	}

	@Test
	void deliveryPreservesIncompatibleComponentsAndCountsAcrossSaveReload() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var state = new GeneTreatRestockState();
		state.setEnabled(true);
		state.observe(treat(1, 100));
		state.acceptExtracted(treat(12, 100));
		var slot = BasicInventorySlot.at(ApiarySlotManager::isGeneTreat, null, 0, 0);
		slot.setStack(treat(60, 80));
		assertFalse(state.deliverPending(slot));
		assertEquals(60, slot.getCount());
		slot.setStack(treat(60, 100));
		assertTrue(state.deliverPending(slot));
		assertEquals(64, slot.getCount());
		var restored = new GeneTreatRestockState();
		restored.load(state.save(provider), provider);
		assertTrue(restored.isEnabled());
		assertTrue(ItemStack.isSameItemSameComponents(state.template(), restored.template()));
		slot.setStack(ItemStack.EMPTY);
		assertTrue(restored.deliverPending(slot));
		assertEquals(8, slot.getCount());
		assertFalse(restored.hasPending());
	}

	@Test
	void listenerFailureAfterInsertionDoesNotDuplicatePaidItems() {
		var state = new GeneTreatRestockState();
		state.setEnabled(true);
		state.observe(treat(1, 100));
		state.acceptExtracted(treat(12, 100));
		var slot = BasicInventorySlot.at(() -> { throw new IllegalStateException("listener failed"); }, 0, 0);
		assertThrows(IllegalStateException.class, () -> state.deliverPending(slot));
		assertEquals(12, slot.getCount());
		assertFalse(state.hasPending());
		assertTrue(state.isSuspended());
		state.setEnabled(false);
		state.setEnabled(true);
		assertFalse(state.deliverPending(slot));
		assertEquals(12, slot.getCount());
	}

	@Test
	void emptyStockBackoffIsBoundedAndCannotRunTwiceInOneTick() {
		var state = new GeneTreatRestockState();
		state.setEnabled(true);
		state.observe(treat(1, 100));
		long tick = 0;
		for (int delay : new int[]{10, 20, 40, 80, 160, 200, 200}) {
			assertTrue(state.tryBegin(tick, 0));
			assertFalse(state.tryBegin(tick, 0));
			state.complete(tick, false);
			assertFalse(state.tryBegin(tick + delay - 5, 0));
			tick += delay;
		}
		assertTrue(state.tryBegin(tick, 0));
		state.suspend();
		assertFalse(state.tryBegin(tick + 1000, 0));
		state.setEnabled(false);
		state.setEnabled(true);
		assertFalse(state.tryBegin(tick + 1005, 0));
	}

	@Test
	void invalidSavedAssetsArePreservedAndCannotBeReenabled() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var invalid = new CompoundTag();
		invalid.putInt("version", 1);
		invalid.putBoolean("enabled", true);
		var pending = new ListTag();
		pending.add(new ItemStack(Items.DIAMOND).save(provider));
		invalid.put("pending", pending);
		var state = new GeneTreatRestockState();
		state.load(invalid, provider);
		assertTrue(state.isSuspended());
		state.setEnabled(false);
		state.setEnabled(true);
		assertTrue(state.isSuspended());
		assertEquals(invalid, state.save(provider));
		assertFalse(state.deliverPending(BasicInventorySlot.at(null, 0, 0)));
	}

	@Test
	void mergeKeepsEveryPaidStackAndRejectsOverflowWithoutMutatingSources() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var first = new GeneTreatRestockState();
		first.setEnabled(true);
		first.observe(treat(1, 100));
		first.acceptExtracted(treat(5, 100));
		var second = new GeneTreatRestockState();
		second.acceptExtracted(treat(7, 80));
		var savedFirst = first.save(provider);
		var savedSecond = second.save(provider);
		var merged = new GeneTreatRestockState();
		merged.load(GeneTreatRestockState.mergeSaved(List.of(savedFirst, savedSecond)), provider);
		var slot = BasicInventorySlot.at(null, 0, 0);
		assertTrue(merged.isEnabled());
		assertTrue(merged.deliverPending(slot));
		assertEquals(5, slot.getCount());
		slot.setStack(ItemStack.EMPTY);
		assertTrue(merged.deliverPending(slot));
		assertEquals(7, slot.getCount());
		assertFalse(merged.hasPending());
		assertEquals(savedFirst, first.save(provider));
		assertEquals(savedSecond, second.save(provider));
		var many = java.util.Collections.nCopies(GeneTreatRestockState.MAX_PENDING_STACKS + 1, savedFirst);
		assertThrows(IllegalArgumentException.class, () -> GeneTreatRestockState.mergeSaved(many));
	}

	@Test
	void quickInsertChoosesSelectedEmptySlotThenFirstAvailable() {
		BeeSlot[] slots = {new BeeSlot(), new BeeSlot(), new BeeSlot()};
		assertEquals(2, ApiaryQuickInsertHandler.findEmptySlot(slots, 2));
		slots[2].setBeeData(new CompoundTag());
		assertEquals(0, ApiaryQuickInsertHandler.findEmptySlot(slots, 2));
		slots[0].setBeeData(new CompoundTag());
		assertEquals(1, ApiaryQuickInsertHandler.findEmptySlot(slots, -1));
		slots[1].setBeeData(new CompoundTag());
		assertEquals(-1, ApiaryQuickInsertHandler.findEmptySlot(slots, 1));
		assertTrue(ApiaryQuickInsertHandler.isCandidate(treat(1, 100)));
		assertFalse(ApiaryQuickInsertHandler.isCandidate(new ItemStack(ModItems.HONEY_TREAT.get())));
		assertFalse(ApiaryQuickInsertHandler.isCandidate(new ItemStack(ModItems.BEE_CAGE.get())));
	}

	@Test
	void quickInsertionAccountsForPartialDeliveryAndListenerFailure() {
		var slot = BasicInventorySlot.at(ApiarySlotManager::isGeneTreat, null, 0, 0);
		slot.setStack(treat(60, 100));
		var held = treat(12, 100);
		assertEquals(4, ApiaryQuickInsertHandler.insertGeneTreat(slot, held));
		assertEquals(64, slot.getCount());
		assertEquals(8, held.getCount());
		assertEquals(0, ApiaryQuickInsertHandler.insertGeneTreat(slot, held));
		assertEquals(8, held.getCount());
		var incompatible = treat(3, 80);
		assertEquals(0, ApiaryQuickInsertHandler.insertGeneTreat(slot, incompatible));
		assertEquals(3, incompatible.getCount());
		var failing = BasicInventorySlot.at(() -> { throw new IllegalStateException("listener failed"); }, 0, 0);
		assertThrows(IllegalStateException.class, () -> ApiaryQuickInsertHandler.insertGeneTreat(failing, held));
		assertEquals(8, failing.getCount());
		assertTrue(held.isEmpty());
	}

	@Test
	void malformedRootAndFlagsRemainQuarantinedAcrossReload() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var root = new CompoundTag();
		root.putString(GeneTreatRestockState.NBT_KEY, "malformed assets");
		var state = new GeneTreatRestockState();
		state.loadRoot(root, provider);
		assertTrue(state.isSuspended());
		var saved = state.save(provider);
		assertEquals("malformed assets", saved.getString("invalid_root"));
		state.load(saved, provider);
		state.setEnabled(true);
		assertTrue(state.isSuspended());
		assertEquals(saved, state.save(provider));
		assertThrows(IllegalArgumentException.class, () -> GeneTreatRestockState.mergeSaved(List.of(saved)));
		var badFlag = new CompoundTag();
		badFlag.putString("enabled", "true");
		state.load(badFlag, provider);
		assertTrue(state.isSuspended());
		assertEquals(badFlag, state.save(provider));
	}

	@Test
	void unknownExtractionSurvivesToggleReloadAndRejectsCrafting() {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var state = new GeneTreatRestockState();
		state.setEnabled(true);
		state.observe(treat(1, 100));
		assertTrue(state.tryBegin(0, 0));
		state.quarantineExtraction();
		state.setEnabled(false);
		state.setEnabled(true);
		assertTrue(state.isSuspended());
		assertFalse(state.observe(treat(1, 80)));
		assertFalse(state.tryBegin(1000, 0));
		var saved = state.save(provider);
		assertTrue(saved.getBoolean("extraction_unknown"));
		var restored = new GeneTreatRestockState();
		restored.load(saved, provider);
		assertTrue(restored.isSuspended());
		assertFalse(restored.tryBegin(2000, 0));
		assertEquals(saved, restored.save(provider));
		assertThrows(IllegalArgumentException.class,
				() -> GeneTreatRestockState.mergeSaved(List.of(new CompoundTag(), saved)));
		assertEquals(saved, state.save(provider));
	}

	@Test
	void extractionKeyTracksComponentsAndLifecycleButNotCount() {
		var state = new GeneTreatRestockState();
		state.setEnabled(true);
		var stack = treat(12, 100);
		state.observe(stack);
		Object key = new Object();
		state.cacheExtractionKey(key);
		stack.setCount(4);
		assertFalse(state.observe(stack));
		assertSame(key, state.extractionKey());
		assertFalse(state.observe(ItemStack.EMPTY));
		assertSame(key, state.extractionKey());
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("new components"));
		assertTrue(state.observe(stack));
		assertNull(state.extractionKey());
		state.cacheExtractionKey(key);
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		state.load(state.save(provider), provider);
		assertNull(state.extractionKey());
		state.cacheExtractionKey(key);
		state.setEnabled(false);
		assertNull(state.extractionKey());
		state.cacheExtractionKey(key);
		state.clearAfterTransfer();
		assertNull(state.extractionKey());
	}

	private static ItemStack treat(int count, int purity) {
		ItemStack stack = new ItemStack(ModItems.HONEY_TREAT.get(), count);
		stack.set(ModDataComponents.GENE_GROUP_LIST.get(), List.of(new GeneGroup(
				GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_VERY_HIGH.getSerializedName(), purity)));
		return stack;
	}
}
