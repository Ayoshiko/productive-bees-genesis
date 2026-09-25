package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.junit.jupiter.api.Assertions.*;

import appeng.api.stacks.AEItemKey;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class Ae2PullDecisionMinecraftTest {

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void combinedDecisionMatchesIndependentQueriesAcrossPoliciesAndKeys() {
		AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
		ItemStack comb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(ModDataComponents.BEE_TYPE, ResourceLocation.parse("productivebees:iron"));
		AEItemKey bee = AEItemKey.of(comb);
		Ae2PullDecision result = new Ae2PullDecision();
		for (Ae2InputFilter.FilterMode mode : Ae2InputFilter.FilterMode.values()) {
			for (int options = 0; options < 128; options++) {
				Ae2InputFilter filter = new Ae2InputFilter();
				filter.setFilterMode(mode);
				filter.setPreciseMode((options & 1) != 0);
				boolean ignoreNbt = (options & 2) != 0;
				boolean tags = (options & 4) != 0;
				filter.setGlobalNetworkStock((options & 8) != 0);
				filter.setGlobalReserveAmount(75);
				if ((options & 16) != 0) filter.toggleUnlimitedAllFallback();
				add(filter, 0, iron, 100, 20, false);
				add(filter, 1, iron, 200, 30, true);
				add(filter, 2, bee, 90, 10, true);
				if ((options & 64) != 0) filter.setAllDirectNetworkStock(false);
				if ((options & 32) != 0) filter.clearEntries();
				for (AEItemKey key : List.of(iron, bee, AEItemKey.of(Items.GOLD_INGOT))) {
					for (long visible : new long[]{0, 25, 100, 1_000, Long.MAX_VALUE}) {
						long limit = filter.getPullLimitIfAllowed(key, visible, ignoreNbt, tags, result);
						assertEquals(filter.getPullLimitIfAllowed(key, visible, ignoreNbt, tags), limit);
						assertEquals(limit != Ae2InputFilter.PULL_DISALLOWED, result.admitted);
						if (!result.admitted) continue;
						assertEquals(filter.isUnlimitedForKey(key, ignoreNbt), result.unlimited);
						assertEquals(filter.getReserveFloorForKey(key, ignoreNbt), result.reserveFloor);
						assertEquals(mode == Ae2InputFilter.FilterMode.WHITELIST
								&& filter.matchesAnyEntry(key, ignoreNbt), result.marked);
						if (result.reserveFloor < 0L) {
							long provisional = filter.getPullLimitIfAllowed(key, Long.MAX_VALUE, ignoreNbt, tags, result);
							assertEquals(limit < 0 ? visible : Math.min(visible, limit),
									provisional < 0 ? visible : Math.min(visible, provisional));
						}
					}
				}
			}
		}
	}

	@Test
	void scanningAnEmptyOutputReleasesItsCachedStackAndKey() {
		var cache = new AeItemKeyCache(3);
		ItemStack original = new ItemStack(Items.DIAMOND);
		AEItemKey oldKey = cache.get(0, original);
		var buffers = new Ae2PushBuffers();
		Ae2OutputCommitter.collectSlot(buffers, 0, 0, BasicInventorySlot.at(null, 0, 0), cache,
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
		assertTrue(buffers.entries.isEmpty());
		original.set(DataComponents.CUSTOM_NAME, Component.literal("changed"));
		AEItemKey newKey = cache.get(0, original);
		assertNotSame(oldKey, newKey);
		assertTrue(newKey.matches(original));
	}

	@Test
	void scratchTableKeepsDecisionsSeparateAndClearsReferences() {
		var amounts = new Ae2PullCandidateAmounts();
		var decision = new Ae2PullDecision();
		var iron = AEItemKey.of(Items.IRON_INGOT);
		var gold = AEItemKey.of(Items.GOLD_INGOT);
		decision.set(true, true, true, 1_000);
		amounts.put(iron, 40, decision);
		decision.set(true, false, false, -1);
		amounts.put(gold, 60, decision);
		var entry = new Ae2InputPuller.PullEntry(iron, amounts.get(iron));
		amounts.apply(iron, entry, true);
		assertEquals(40, entry.remaining);
		assertTrue(entry.unlimited);
		assertTrue(entry.marked);
		assertEquals(1_000, entry.reserveFloor);
		amounts.apply(gold, entry, true);
		assertFalse(entry.unlimited);
		assertFalse(entry.marked);
		assertEquals(-1, entry.reserveFloor);
		amounts.clear();
		assertEquals(0, amounts.get(iron));
	}

	@Test
	void validatorResultsStayBoundToTheirSlotWithinOnePlanningPass() {
		var entry = new Ae2InputPuller.PullEntry(AEItemKey.of(Items.IRON_INGOT), 1);
		var acceptedCalls = new AtomicInteger();
		var rejectedCalls = new AtomicInteger();
		var accepted = BasicInventorySlot.at(stack -> true, stack -> {
			acceptedCalls.incrementAndGet();
			return true;
		}, null, 0, 0);
		var rejected = BasicInventorySlot.at(stack -> true, stack -> {
			rejectedCalls.incrementAndGet();
			return false;
		}, null, 0, 0);
		var probe = new ItemStack(Items.IRON_INGOT);

		entry.beginComponentMatchCache(2);
		assertTrue(entry.acceptsProbe(0, accepted, probe));
		assertFalse(entry.acceptsProbe(1, rejected, probe));
		assertTrue(entry.acceptsProbe(0, accepted, probe));
		assertFalse(entry.acceptsProbe(1, rejected, probe));
		assertEquals(1, acceptedCalls.get());
		assertEquals(1, rejectedCalls.get());

		entry.beginComponentMatchCache(2);
		assertFalse(entry.acceptsProbe(0, rejected, probe));
		assertTrue(entry.acceptsProbe(1, accepted, probe));
		assertEquals(2, acceptedCalls.get());
		assertEquals(2, rejectedCalls.get());
		entry.clearComponentMatchCache();
	}

	@Test
	void legacyGhostlyCombEntrySurvivesLoadAndFilterModes() {
		var oldEntries = new ListTag();
		oldEntries.add(StringTag.valueOf("productivebees:ghostly"));
		var oldData = new CompoundTag();
		oldData.put("entries", oldEntries);
		var ghostly = AEItemKey.of(ModItems.HONEYCOMB_GHOSTLY.get());
		var milky = AEItemKey.of(ModItems.HONEYCOMB_MILKY.get());
		assertEquals(ResourceLocation.parse("productivebees:ghostly"), CombFuzzyMatcher.getBeeType(ghostly));

		var filter = new Ae2InputFilter();
		oldData.putByte("mode", (byte) Ae2InputFilter.FilterMode.WHITELIST.ordinal());
		filter.load(oldData);
		assertNotEquals(Ae2InputFilter.PULL_DISALLOWED,
				filter.getPullLimitIfAllowed(ghostly, 64, false));
		assertEquals(Ae2InputFilter.PULL_DISALLOWED,
				filter.getPullLimitIfAllowed(milky, 64, false));

		oldData.putByte("mode", (byte) Ae2InputFilter.FilterMode.BLACKLIST.ordinal());
		filter.load(oldData);
		assertEquals(Ae2InputFilter.PULL_DISALLOWED,
				filter.getPullLimitIfAllowed(ghostly, 64, false));
		assertNotEquals(Ae2InputFilter.PULL_DISALLOWED,
				filter.getPullLimitIfAllowed(milky, 64, false));
	}

	private static void add(Ae2InputFilter filter, int slot, AEItemKey key, long amount, long reserve,
			boolean unlimited) {
		filter.setDirectEntryFingerprintAt(slot, "test:" + slot);
		filter.resolveDirectKey(slot, key);
		filter.setDirectAmountAt(slot, amount);
		filter.setDirectReserveAmountAt(slot, reserve);
		filter.toggleDirectNetworkStockAt(slot);
		if (unlimited) filter.toggleDirectUnlimitedAt(slot);
	}
}
