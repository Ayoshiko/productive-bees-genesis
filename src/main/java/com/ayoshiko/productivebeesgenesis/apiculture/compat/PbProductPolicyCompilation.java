package com.ayoshiko.productivebeesgenesis.apiculture.compat;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.util.BeeHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.HoneycombItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

/** 每步只查看一条配方、一个输入或一个输出；半成品和失效代际永不发布。 */
public final class PbProductPolicyCompilation {
	public enum Phase { RECIPES, INPUTS, BEE_OUTPUT, COMB_BLOCK, SEEDS, CENTRIFUGE_OUTPUT, FLUID, PUBLISH, COMPLETE }
	private final ServerLevel level;
	private final long revision, epoch;
	private final Collection<RecipeHolder<?>> sourceRecipes;
	private final Iterator<RecipeHolder<?>> recipes;
	private final ProductPolicySnapshot.Builder builder = new ProductPolicySnapshot.Builder();
	private final List<String> diagnostics = new ArrayList<>();
	private final Map<ProductKey, ItemStack> direct = new ConcurrentHashMap<>();
	private final Map<ProductKey, RecipeHolder<CentrifugeRecipe>> inputRecipes = new ConcurrentHashMap<>();
	private final Set<ResourceLocation> seenBees = ConcurrentHashMap.newKeySet(), seenRecipes = ConcurrentHashMap.newKeySet();
	private Iterator<Map.Entry<ProductKey, ItemStack>> seeds;
	private Iterator<Map.Entry<ItemStack, ChancedOutput>> outputs;
	private ItemStack[] inputs;
	private int inputOffset;
	private ItemStack blockSource;
	private RecipeHolder<CentrifugeRecipe> centrifuge;
	private String source = "";
	private Phase phase = Phase.RECIPES;
	private PbProductPolicyCompiler.Result result;
	public PbProductPolicyCompilation(ServerLevel level, long revision) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Compile PB policy on the server thread");
		if (revision < 0) throw new IllegalArgumentException("Negative policy revision");
		this.level = level; this.revision = revision; epoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
		// 1.21.1 的 byName 与 byType 使用同一插入顺序；values 是不可变视图，不复制全部配方。
		sourceRecipes = level.getRecipeManager().getRecipes(); recipes = sourceRecipes.iterator();
	}
	public boolean current() { return epoch == ProductiveBeesGenesis.RECIPE_VERSION.get() && sourceRecipes == level.getRecipeManager().getRecipes(); }
	public Phase phase() { return phase; }
	public boolean step() {
		checkCurrent();
		try {
			switch (phase) {
				case RECIPES -> recipe();
				case INPUTS -> {
					if (inputOffset == inputs.length) { inputs = null; centrifuge = null; phase = Phase.RECIPES; }
					else { var input = inputs[inputOffset++]; if (!input.isEmpty()) inputRecipes.putIfAbsent(ProductKeyCodec.item(input, level.registryAccess()), centrifuge); }
				}
				case BEE_OUTPUT, CENTRIFUGE_OUTPUT -> output();
				case COMB_BLOCK -> {
					var block = BeeHelper.getCombBlockFromHoneyComb(blockSource); blockSource = null;
					if (!block.isEmpty()) addDirect(block, source + "/comb_block");
					phase = Phase.BEE_OUTPUT;
				}
				case SEEDS -> seed();
				case FLUID -> {
					var fluid = centrifuge.value().getFluidOutputs();
					if (!fluid.isEmpty()) builder.add(new AllowedProductDescriptor(ProductKeyCodec.fluid(fluid, level.registryAccess()), "pb_static", source));
					centrifuge = null; phase = Phase.SEEDS;
				}
				case PUBLISH -> { result = new PbProductPolicyCompiler.Result(builder.build(revision), diagnostics); phase = Phase.COMPLETE; }
				case COMPLETE -> { }
			}
		} catch (RuntimeException failure) {
			if (phase == Phase.PUBLISH || phase == Phase.COMPLETE) throw failure;
			diagnostics.add(source + ": " + failure);
			phase = seeds == null ? Phase.RECIPES : Phase.SEEDS;
			inputs = null; outputs = null; blockSource = null; centrifuge = null;
		}
		return phase == Phase.COMPLETE;
	}
	public PbProductPolicyCompiler.Result result() {
		checkCurrent();
		if (result == null) throw new IllegalStateException("Product policy is still compiling");
		return result;
	}
	private void recipe() {
		if (!recipes.hasNext()) { seeds = direct.entrySet().iterator(); phase = Phase.SEEDS; return; }
		var holder = recipes.next(); source = holder.id().toString();
		if (holder.value().getType() == ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get()) {
			var recipe = (AdvancedBeehiveRecipe) holder.value(); var bee = recipe.ingredient.get();
			if (bee == null || (bee.isConfigurable() ? !BeeInfoHelper.isBeeTypeExists(bee.getBeeType())
					: !BuiltInRegistries.ENTITY_TYPE.containsKey(bee.getBeeType())) || !seenBees.add(bee.getBeeType())) return;
			outputs = recipe.getRecipeOutputs().entrySet().iterator(); phase = Phase.BEE_OUTPUT;
		} else if (holder.value().getType() == ModRecipeTypes.CENTRIFUGE_TYPE.get()) {
			centrifuge = new RecipeHolder<>(holder.id(), (CentrifugeRecipe) holder.value());
			inputs = centrifuge.value().ingredient.getItems(); inputOffset = 0; phase = Phase.INPUTS;
		}
	}
	private void output() {
		boolean bee = phase == Phase.BEE_OUTPUT;
		if (!outputs.hasNext()) { outputs = null; phase = bee ? Phase.RECIPES : Phase.FLUID; return; }
		var output = outputs.next(); var chance = output.getValue();
		if (Math.max(chance.min(), chance.max()) <= 0 || !Float.isFinite(chance.chance()) || (bee && chance.chance() <= 0)) return;
		var stack = output.getKey(); if (stack.isEmpty()) return;
		if (bee) {
			addDirect(stack, source);
			if (stack.getItem() instanceof HoneycombItem) { blockSource = stack; phase = Phase.COMB_BLOCK; }
		} else builder.add(new AllowedProductDescriptor(ProductKeyCodec.item(stack, level.registryAccess()), "pb_static", source));
	}
	private void addDirect(ItemStack stack, String recipe) {
		var key = ProductKeyCodec.item(stack, level.registryAccess());
		builder.add(new AllowedProductDescriptor(key, "pb_static", recipe)); direct.put(key, stack.copy());
	}
	private void seed() {
		if (!seeds.hasNext()) { phase = Phase.PUBLISH; return; }
		var seed = seeds.next(); var input = seed.getValue(); var bee = input.get(ModDataComponents.BEE_TYPE.get());
		centrifuge = input.is(ModItems.CONFIGURABLE_COMB_BLOCK.get()) && bee != null ? CentrifugeRecipeIndex.getCombBlock(bee)
				: input.is(ModItems.CONFIGURABLE_HONEYCOMB.get()) && bee != null ? CentrifugeRecipeIndex.get(bee) : CentrifugeRecipeIndex.getSpecialCombBlock(input);
		if (centrifuge == null) centrifuge = inputRecipes.get(seed.getKey());
		if (centrifuge == null || !seenRecipes.add(centrifuge.id())) { centrifuge = null; return; }
		source = centrifuge.id().toString(); outputs = centrifuge.value().getRecipeOutputs().entrySet().iterator(); phase = Phase.CENTRIFUGE_OUTPUT;
	}
	private void checkCurrent() {
		if (!level.getServer().isSameThread() || !current()) throw new IllegalStateException("Product compilation belongs to another thread or recipe generation");
	}
}
