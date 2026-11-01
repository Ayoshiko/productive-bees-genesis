package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.client.resource.ResourceClientCatalog;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeView;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** 独立 JEI 插件使可选查看器及其生命周期与资源生产保持隔离。 */
@JeiPlugin
public final class ResourceJeiPlugin implements IModPlugin {
	public static final RecipeType<ResourceJeiRecipe> PRODUCTION = RecipeType.create("productivebeesgenesis", "resource_production", ResourceJeiRecipe.class);
	public static final RecipeType<ResourceJeiRecipe> CENTRIFUGING = RecipeType.create("productivebeesgenesis", "resource_centrifuging", ResourceJeiRecipe.class);
	private static IJeiRuntime runtime;
	private static List<ResourceRecipeView> shown = List.of();
	private static List<ResourceJeiRecipe> shownProduction = List.of();
	private static List<ResourceJeiRecipe> shownCentrifuging = List.of();
	private static List<ResourceIngredient> ingredients = List.of();
	@Override public ResourceLocation getPluginUid() { return new ResourceLocation("productivebeesgenesis:resource_viewer"); }
	@Override public void registerCategories(IRecipeCategoryRegistration registration) {
		var helper = registration.getJeiHelpers().getGuiHelper();
		registration.addRecipeCategories(new ResourceRecipeCategory(helper, false, new ItemStack(ModBlocks.MEK_APIARY.get())),
				new ResourceRecipeCategory(helper, true, new ItemStack(ModBlocks.MEK_CENTRIFUGE.get())));
	}
	@Override public void registerIngredients(IModIngredientRegistration registration) {
		ingredients = ingredients(ResourceClientCatalog.recipes());
		registration.register(ResourceIngredient.TYPE, ingredients, new ResourceIngredient.Helper(), new ResourceIngredient.Renderer());
	}
	@Override public void registerRecipes(IRecipeRegistration registration) {
		shown = ResourceClientCatalog.recipes();
		shownProduction = recipes(shown, false);
		shownCentrifuging = recipes(shown, true);
		registration.addRecipes(PRODUCTION, shownProduction);
		registration.addRecipes(CENTRIFUGING, shownCentrifuging);
	}
	@Override public void onRuntimeAvailable(IJeiRuntime value) { runtime = value; refresh(); }
	@Override public void onRuntimeUnavailable() { discard(); }

	public static List<ResourceJeiRecipe> recipes(List<ResourceRecipeView> catalog, boolean centrifuging) {
		var result = new java.util.ArrayList<ResourceJeiRecipe>();
		for (var recipe : catalog) if (recipe.centrifuging() == centrifuging) {
			result.add(new ResourceJeiRecipe(recipe, false));
			if (centrifuging) result.add(new ResourceJeiRecipe(recipe, true));
		}
		return List.copyOf(result);
	}
	private static List<ResourceIngredient> ingredients(List<ResourceRecipeView> catalog) {
		return catalog.stream().flatMap(recipe -> recipe.outputs().stream())
				.map(out -> new ResourceIngredient(out.key(), 1, 1)).distinct().toList();
	}
	public static void refresh() {
		if (runtime == null) return;
		var next = ResourceClientCatalog.recipes();
		var nextIngredients = ingredients(next);
		if (shown.equals(next) && ingredients.equals(nextIngredients)) return;
		var manager = runtime.getRecipeManager();
		// JEI 按注册实例管理隐藏状态，不能临时重建相等的配方对象来撤销旧目录。
		manager.hideRecipes(PRODUCTION, shownProduction);
		manager.hideRecipes(CENTRIFUGING, shownCentrifuging);
		var ingredientManager = runtime.getIngredientManager();
		var oldSet = new java.util.HashSet<>(ingredients);
		var nextSet = new java.util.HashSet<>(nextIngredients);
		var removed = ingredients.stream().filter(value -> !nextSet.contains(value)).toList();
		var added = nextIngredients.stream().filter(value -> !oldSet.contains(value)).toList();
		if (!removed.isEmpty()) ingredientManager.removeIngredientsAtRuntime(ResourceIngredient.TYPE, removed);
		if (!added.isEmpty()) ingredientManager.addIngredientsAtRuntime(ResourceIngredient.TYPE, added);
		shownProduction = recipes(next, false);
		shownCentrifuging = recipes(next, true);
		manager.addRecipes(PRODUCTION, shownProduction);
		manager.addRecipes(CENTRIFUGING, shownCentrifuging);
		shown = next; ingredients = nextIngredients;
	}
	public static void clear() { refresh(); discard(); }
	private static void discard() {
		runtime = null; shown = List.of(); ingredients = List.of();
		shownProduction = List.of(); shownCentrifuging = List.of(); ResourceVisuals.clear();
	}

	@Override public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
		for (var block : BuiltInRegistries.BLOCK) {
			if (block instanceof com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock) {
				registration.addRecipeCatalyst(new ItemStack(block), PRODUCTION);
			} else if (block instanceof com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock) {
				registration.addRecipeCatalyst(new ItemStack(block), CENTRIFUGING);
			}
		}
		for (String id : List.of("advanced_oak_beehive")) catalyst(registration, id, PRODUCTION);
		for (String id : List.of("centrifuge", "powered_centrifuge", "heated_centrifuge")) catalyst(registration, id, CENTRIFUGING);
	}
	private static void catalyst(IRecipeCatalystRegistration registration, String id, RecipeType<ResourceJeiRecipe> type) {
		var item = BuiltInRegistries.ITEM.get(new ResourceLocation("productivebees", id));
		if (item != net.minecraft.world.item.Items.AIR) registration.addRecipeCatalyst(new ItemStack(item), type);
	}
}
