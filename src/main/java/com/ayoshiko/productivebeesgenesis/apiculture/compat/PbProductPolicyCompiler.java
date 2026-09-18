package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.AllowedProductDescriptor;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.util.BeeHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** 显式重载边界编译：蜂产物→支持的蜜脾块→一次离心，绝不递归扩张普通合成目录。 */
public final class PbProductPolicyCompiler {
	public record Result(ProductPolicySnapshot snapshot, List<String> diagnostics) {
		public Result { diagnostics = List.copyOf(diagnostics); }
	}
	private PbProductPolicyCompiler() { }

	public static Result compile(ServerLevel level, long revision) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Compile PB policy on the server thread");
		List<AllowedProductDescriptor> descriptors = new ArrayList<>();
		List<String> diagnostics = new ArrayList<>();
		Map<ProductKey, ItemStack> direct = new ConcurrentHashMap<>();
		var seenBees = ConcurrentHashMap.newKeySet();
		for (var holder : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get())) {
			try {
				var bee = holder.value().ingredient.get();
				if (bee == null || (bee.isConfigurable() ? !BeeInfoHelper.isBeeTypeExists(bee.getBeeType())
						: !BuiltInRegistries.ENTITY_TYPE.containsKey(bee.getBeeType()))) continue;
				// 与现有 BeeProduceQueries 一致，同蜂种仅选第一个有效配方。
				if (!seenBees.add(bee.getBeeType())) continue;
				for (var output : holder.value().getRecipeOutputs().entrySet()) {
					if (!possible(output.getValue(), false)) continue;
					ItemStack stack = output.getKey().copy();
					addItem(level, descriptors, stack, holder.id().toString());
					direct.put(ProductKeyCodec.item(stack, level.registryAccess()), stack);
					if (stack.getItem() instanceof HoneycombItem) {
						ItemStack block = BeeHelper.getCombBlockFromHoneyComb(stack);
						if (!block.isEmpty()) {
							addItem(level, descriptors, block, holder.id() + "/comb_block");
							direct.put(ProductKeyCodec.item(block, level.registryAccess()), block.copy());
						}
					}
				}
			} catch (RuntimeException failure) { diagnostics.add(holder.id() + ": " + failure); }
		}

		Map<ProductKey, RecipeHolder<CentrifugeRecipe>> recipes = new ConcurrentHashMap<>();
		for (var holder : level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get())) {
			try {
				for (ItemStack input : holder.value().ingredient.getItems()) {
					if (!input.isEmpty()) recipes.putIfAbsent(ProductKeyCodec.item(input, level.registryAccess()), holder);
				}
			} catch (RuntimeException failure) { diagnostics.add(holder.id() + ": " + failure); }
		}
		var seenRecipes = ConcurrentHashMap.newKeySet();
		for (var seed : direct.entrySet()) {
			ItemStack input = seed.getValue();
			var bee = input.get(ModDataComponents.BEE_TYPE.get());
			RecipeHolder<CentrifugeRecipe> recipe = null;
			if (input.is(ModItems.CONFIGURABLE_COMB_BLOCK.get()) && bee != null) recipe = CentrifugeRecipeIndex.getCombBlock(bee);
			else if (input.is(ModItems.CONFIGURABLE_HONEYCOMB.get()) && bee != null) recipe = CentrifugeRecipeIndex.get(bee);
			else recipe = CentrifugeRecipeIndex.getSpecialCombBlock(input);
			if (recipe == null) recipe = recipes.get(seed.getKey());
			if (recipe == null || !seenRecipes.add(recipe.id())) continue;
			try {
				for (var output : recipe.value().getRecipeOutputs().entrySet()) {
					if (possible(output.getValue(), true)) addItem(level, descriptors, output.getKey(), recipe.id().toString());
				}
				var fluid = recipe.value().getFluidOutputs();
				if (!fluid.isEmpty()) descriptors.add(new AllowedProductDescriptor(ProductKeyCodec.fluid(fluid, level.registryAccess()),
						"pb_static", recipe.id().toString()));
			} catch (RuntimeException failure) { diagnostics.add(recipe.id() + ": " + failure); }
		}
		return new Result(new ProductPolicySnapshot(revision, descriptors, List.of()), diagnostics);
	}
	private static boolean possible(ChancedOutput output, boolean stabilitySupported) {
		return Math.max(output.min(), output.max()) > 0 && Float.isFinite(output.chance())
				&& (stabilitySupported || output.chance() > 0);
	}
	private static void addItem(ServerLevel level, List<AllowedProductDescriptor> target, ItemStack stack, String recipe) {
		if (!stack.isEmpty()) target.add(new AllowedProductDescriptor(ProductKeyCodec.item(stack, level.registryAccess()), "pb_static", recipe));
	}
}
