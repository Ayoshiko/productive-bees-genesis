package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.PagedProductAmounts;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/** 隔离专服上的数量适配与真实物理处理器验证；配方为确定性测试夹具。 */
public final class CentrifugeKernelProbe {
	public static void verify(ServerLevel level, JsonObject report) {
		quantityBoundary(level, report);
		var pos = new BlockPos(14, 100, 12);
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		try {
			var tile = (TileEntityMekCentrifuge) level.getBlockEntity(pos);
			physicalAccumulation(tile, report);
			physicalProgress(tile, level, report);
		} finally { level.removeBlock(pos, false); }
	}

	private static void quantityBoundary(ServerLevel level, JsonObject report) {
		var item = new ItemStack(Items.IRON_INGOT);
		item.set(DataComponents.CUSTOM_NAME, Component.literal("d15 component identity"));
		var fluid = new FluidStack(Fluids.WATER, Integer.MAX_VALUE);
		fluid.set(DataComponents.CUSTOM_NAME, Component.literal("d15 fluid identity"));
		var itemKey = ProductKeyCodec.item(item, level.registryAccess());
		var fluidKey = ProductKeyCodec.fluid(fluid, level.registryAccess());
		var amounts = new PagedProductAmounts();
		var output = new PbRecipeOutputSampler.QuantityOutput() {
			@Override
			public void item(ItemStack template, long base, int multiplier) {
				var key = ProductKeyCodec.item(template, level.registryAccess());
				amounts.set(key, amounts.amount(key).add(ProductAmount.of(base).multiply(multiplier)));
			}
			@Override
			public void fluid(FluidStack template, long base, int multiplier) {
				var key = ProductKeyCodec.fluid(template, level.registryAccess());
				amounts.set(key, amounts.amount(key).add(ProductAmount.of(base).multiply(multiplier)));
			}
		};
		PbRecipeOutputSampler.sampleAmounts(output, Map.of(item, new ChancedOutput(Ingredient.of(Items.IRON_INGOT),
				Integer.MAX_VALUE, Integer.MAX_VALUE, 1)), fluid, Integer.MAX_VALUE, Integer.MAX_VALUE, 0, false, new Random(15));
		var expected = BigInteger.valueOf(Integer.MAX_VALUE).pow(3);
		require(amounts.size() == 2 && amounts.amount(itemKey).exact().equals(expected)
				&& amounts.amount(fluidKey).exact().equals(expected), "Item/fluid amount truncated before physical boundary");
		require(item.getCount() == 1 && fluid.getAmount() == Integer.MAX_VALUE
				&& itemKey.equals(ProductKeyCodec.item(item, level.registryAccess()))
				&& fluidKey.equals(ProductKeyCodec.fluid(fluid, level.registryAccess())), "Recipe template changed");
		var wax = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("productivebees:wax")));
		var honey = new FluidStack(BuiltInRegistries.FLUID.get(ResourceLocation.parse("productivebees:honey")), 250);
		require(isSuppressedFixture(wax, honey), "Wax/honey fixture invalid");
		var filteredRandom = new Random(15);
		PbRecipeOutputSampler.sampleAmounts(output, Map.of(wax,
				new ChancedOutput(Ingredient.of(wax.getItem()), 1, 4, 0.5F)), honey, 1, 4, 0, true, filteredRandom);
		require(amounts.size() == 2 && filteredRandom.nextLong() == new Random(15).nextLong(), "Filtered output consumed randomness or produced assets");
		report.addProperty("centrifugeKernelExactItemAndFluid", expected.toString());
		report.addProperty("centrifugeKernelTemplatesAndPreSampleFiltering", true);
	}

	private static boolean isSuppressedFixture(ItemStack wax, FluidStack honey) {
		return !wax.isEmpty() && !honey.isEmpty()
				&& com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper.isWax(wax)
				&& com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper.isHoney(honey);
	}

	private static void physicalAccumulation(TileEntityMekCentrifuge tile, JsonObject report) {
		var completer = new PbRecipeCompleter(tile);
		var comb = recipe(Items.HONEYCOMB, 2, 25);
		tile.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 3));
		completer.accumulatePbRecipeOutputsBatch(comb, 0, 4, 2);
		require(completer.pendingItemCount() == 16 && completer.getPendingFluidAmount() == 200
				&& completer.pendingInputShrink() == 2, "Productivity changed input debit");
		require(completer.flushPendingPbOutputs(0), "Physical comb flush failed");
		require(tile.inputSlot(0).getCount() == 1 && tile.primaryOutputSlot(0).getCount() == 16
				&& tile.fluidOutputTank().getFluidAmount() == 200, "Physical comb totals differ");
		tile.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB_BLOCK));
		completer.accumulatePbRecipeOutputs(recipe(Items.HONEYCOMB_BLOCK, 18, 225), 0, 1);
		require(completer.flushPendingPbOutputs(0) && tile.inputSlot(0).isEmpty()
				&& tile.primaryOutputSlot(0).getCount() == 34 && tile.fluidOutputTank().getFluidAmount() == 425,
				"Recipe switch retained previous comb outputs");
		require(!completer.hasPendingOutputs() && completer.pendingInputShrink() == 0, "Completed physical outputs remained pending");
		completer.accumulatePbRecipeOutputsBatch(recipe(Items.HONEYCOMB, Integer.MAX_VALUE, Integer.MAX_VALUE),
				0, Integer.MAX_VALUE, Integer.MAX_VALUE);
		require(completer.pendingItemCount() == Integer.MAX_VALUE && completer.getPendingFluidAmount() == Long.MAX_VALUE,
				"Legacy physical projection changed");
		completer.resetPendingRecipe();
		tile.primaryOutputSlot(0).setStack(ItemStack.EMPTY);
		tile.fluidOutputTank().setStack(FluidStack.EMPTY);
		report.addProperty("centrifugeKernelPhysicalProjectionAndRecipeSwitch", true);
	}

	private static void physicalProgress(TileEntityMekCentrifuge tile, ServerLevel level, JsonObject report) {
		var recipe = recipe(Items.HONEYCOMB, 2, 25);
		var holder = new RecipeHolder<>(ResourceLocation.parse("productivebeesgenesis:d15_probe"), recipe);
		int period = new PbRecipeEnergyCache(tile).getPbProcessingTime(recipe);
		require(period > 1 && tile.productivityModifier() == 1 && tile.operationsPerTick() == 1, "Unexpected physical base capacity");
		var processor = new PbRecipeProcessor(tile, "D15 probe");
		processor.refreshEnergyAndOpsCache(tile);
		processor.refreshFluidTankFullCache(tile);
		tile.inputSlot(0).setStack(new ItemStack(Items.HONEYCOMB, 3));
		tile.energyContainer().setEnergy(tile.energyContainer().getMaxEnergy());
		long before = tile.energyContainer().getEnergy(), unitCost = tile.energyContainer().getEnergyPerTick();
		int ticks = 2 * period + period / 2;
		processor.setTickMultiplier(ticks);
		require(processor.tryProcessPbRecipe(0, holder), "Physical cycle processing failed");
		require(tile.inputSlot(0).getCount() == 1 && tile.primaryOutputSlot(0).getCount() == 4
				&& tile.fluidOutputTank().getFluidAmount() == 50, "Physical cycle totals differ");
		require(before - tile.energyContainer().getEnergy() == unitCost * ticks, "Physical paid ticks differ");
		var state = new CompoundTag(); processor.saveAdditional(state, level.registryAccess());
		require(state.getIntArray("productivebeesgenesis_pb_progress")[0] == period / 2, "Cycle remainder missing");
		tile.energyContainer().setEnergy(0);
		processor.setTickMultiplier(256);
		require(!processor.tryProcessPbRecipe(0, holder), "Unpowered machine progressed");
		var paused = new CompoundTag(); processor.saveAdditional(paused, level.registryAccess());
		require(paused.getIntArray("productivebeesgenesis_pb_progress")[0] == period / 2
				&& tile.inputSlot(0).getCount() == 1 && tile.primaryOutputSlot(0).getCount() == 4, "Unpowered pause changed assets");
		tile.energyContainer().setEnergy(tile.energyContainer().getMaxEnergy());
		long resumeEnergy = tile.energyContainer().getEnergy();
		processor.setTickMultiplier(period - period / 2);
		require(processor.tryProcessPbRecipe(0, holder) && tile.inputSlot(0).isEmpty()
				&& tile.primaryOutputSlot(0).getCount() == 6 && tile.fluidOutputTank().getFluidAmount() == 75,
				"Resume did not finish the existing cycle");
		require(resumeEnergy - tile.energyContainer().getEnergy() == unitCost * (period - period / 2), "Resume paid the wrong remainder");
		report.addProperty("centrifugeKernelPhysicalCyclesPauseAndResume", true);
		report.addProperty("centrifugeKernelPhysicalPaidEnergy", unitCost * period * 3);
	}

	private static CentrifugeRecipe recipe(Item input, int count, int fluid) {
		return new CentrifugeRecipe(Ingredient.of(input),
				List.of(new ChancedOutput(Ingredient.of(Items.IRON_INGOT), count, count, 1)),
				SizedFluidIngredient.of(Fluids.WATER, fluid), 10);
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private CentrifugeKernelProbe() { }
}
