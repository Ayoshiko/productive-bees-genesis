package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.resource.PbLocalOutputPlan;
import com.ayoshiko.productivebeesgenesis.resource.PbMixedProduction;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceCatalog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class EssenceConversionPlanMinecraftTest {
	private static EssenceConversionRecipeIndex.StackKey key(ItemStack stack) {
		return new EssenceConversionRecipeIndex.StackKey(stack.getItem(), stack.getComponentsPatch());
	}
	private static EssenceConversionUpgradeHelper.BatchPlan plan(boolean chain) {
		var nugget = new ItemStack(Items.IRON_NUGGET);
		var ingot = new ItemStack(Items.IRON_INGOT);
		var block = new ItemStack(Items.IRON_BLOCK);
		var first = new EssenceConversionRecipeIndex.Conversion(9, ingot, key(ingot), chain);
		var second = new EssenceConversionRecipeIndex.Conversion(3, block, key(block), false);
		return new EssenceConversionUpgradeHelper.BatchPlan(new EssenceConversionRecipeIndex.ConversionSnapshot(
				Map.of(key(nugget), first, key(ingot), second), Map.of(Items.IRON_NUGGET, first, Items.IRON_INGOT, second)));
	}
	private static long count(List<ItemStack> stacks, net.minecraft.world.item.Item item) {
		return stacks.stream().filter(s -> s.is(item)).mapToLong(ItemStack::getCount).sum();
	}
	private static List<ItemStack> nuggets(int amount) { return List.of(new ItemStack(Items.IRON_NUGGET, amount)); }

	@Test void divisibleMaximumStillReservesRemainderIdentity() {
		var plan = plan(false);
		var upper = plan.maximum(nuggets(18));
		assertEquals(8, count(upper, Items.IRON_NUGGET));
		assertEquals(2, count(upper, Items.IRON_INGOT));
		assertEquals(0, count(plan.convert(nuggets(18)), Items.IRON_NUGGET));
		assertNull(PbLocalOutputPlan.prepare(new ItemStackHandler(1), () -> new int[]{0}, upper));
		assertNotNull(PbLocalOutputPlan.prepare(new ItemStackHandler(1), () -> new int[]{0}, plan.convert(nuggets(18))));
	}

	@Test void boundsCoverEverySampleAndRemainMonotoneAcrossBothRemainders() {
		for (boolean chain : new boolean[]{false, true}) {
			var plan = plan(chain);
			List<ItemStack> previous = List.of();
			for (int maximum = 1; maximum <= 150; maximum++) {
				var upper = plan.maximum(nuggets(maximum));
				for (var item : List.of(Items.IRON_NUGGET, Items.IRON_INGOT, Items.IRON_BLOCK)) {
					assertTrue(count(upper, item) >= count(previous, item));
					for (int sample = 0; sample <= maximum; sample++) {
						assertTrue(count(plan.convert(nuggets(sample)), item) <= count(upper, item));
					}
				}
				previous = upper;
			}
		}
	}

	@Test void duplicateComponentsMergeBeforeConversionAcrossIntLimit() {
		var result = plan(false).convert(List.of(new ItemStack(Items.IRON_NUGGET, Integer.MAX_VALUE),
				new ItemStack(Items.IRON_NUGGET, 8)));
		long amount = (long) Integer.MAX_VALUE + 8;
		assertEquals(amount / 9, count(result, Items.IRON_INGOT));
		assertEquals(amount % 9, count(result, Items.IRON_NUGGET));
		var named = new ItemStack(Items.IRON_NUGGET, 8);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("保留组件"));
		result = plan(false).convert(List.of(named, new ItemStack(Items.IRON_NUGGET, 1)));
		assertEquals(0, count(result, Items.IRON_INGOT));
		assertTrue(result.stream().anyMatch(s -> ItemStack.matches(s, named)));
	}

	@Test void secondStepStopsAndFrozenPlanSurvivesCacheInvalidation() {
		var plan = plan(true);
		EssenceConversionUpgradeHelper.invalidateCache();
		var result = plan.convert(nuggets(44));
		assertEquals(8, count(result, Items.IRON_NUGGET));
		assertEquals(1, count(result, Items.IRON_INGOT));
		assertEquals(1, count(result, Items.IRON_BLOCK));
		// 第一跳未获准续链时，不能因索引里存在第二步就继续。
		assertEquals(4, count(plan(false).convert(nuggets(36)), Items.IRON_INGOT));
	}

	@Test void storedRemainderAndNewBatchCommitTogetherWithoutTouchingInputSlots() {
		var inv = new ItemStackHandler(4);
		inv.setStackInSlot(0, new ItemStack(Items.GOLD_INGOT));
		inv.setStackInSlot(1, new ItemStack(Items.IRON_NUGGET, 8));
		var conversion = plan(false);
		assertNotNull(PbLocalOutputPlan.prepare(inv, () -> new int[]{1, 2, 3}, nuggets(1), conversion, true));
		var local = PbLocalOutputPlan.prepare(inv, () -> new int[]{1, 2, 3}, nuggets(1), conversion, false);
		assertEquals(8, inv.getStackInSlot(1).getCount());
		local.commit(() -> {});
		assertTrue(inv.getStackInSlot(0).is(Items.GOLD_INGOT));
		assertTrue(inv.getStackInSlot(1).is(Items.IRON_INGOT));
		assertEquals(1, inv.getStackInSlot(1).getCount());
		assertThrows(IllegalStateException.class, () -> local.commit(() -> {}));
	}

	@Test void conversionFailureKeepsObservedWriteAndCannotReplay() {
		var inv = new ItemStackHandler(3) {
			@Override protected void onContentsChanged(int slot) {
				if (getStackInSlot(slot).is(Items.IRON_INGOT)) throw new IllegalStateException("written then failed");
			}
		};
		inv.setStackInSlot(0, new ItemStack(Items.IRON_NUGGET, 8));
		var local = PbLocalOutputPlan.prepare(inv, () -> new int[]{0, 1, 2}, nuggets(2), plan(false), false);
		assertThrows(IllegalStateException.class, () -> local.commit(() -> {}));
		assertEquals(1, inv.getStackInSlot(0).getCount());
		assertTrue(inv.getStackInSlot(1).is(Items.IRON_INGOT));
		assertThrows(IllegalStateException.class, () -> local.commit(() -> {}));
	}

	@Test void productivityAppliesToRawStacksBeforeConversion() {
		var mixed = new PbMixedProduction(List.of(new PbResourceCatalog.ItemOutput(
				new ItemStack(Items.IRON_NUGGET), 2, 2, 1)), 2, 3, false, false);
		var result = plan(false).convert(mixed.sample(2, 1));
		assertEquals(1, count(result, Items.IRON_INGOT));
		assertEquals(3, count(result, Items.IRON_NUGGET));
	}

	@Test void checkedConversionRejectsLongOverflow() {
		assertThrows(ArithmeticException.class, () -> EssenceConversionUpgradeHelper.calculateAmounts(Long.MAX_VALUE, 1, 12));
	}
}
