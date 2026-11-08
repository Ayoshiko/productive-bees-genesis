package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.client.resource.ResourceClientCatalog;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRecipeView;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.*;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
	@Override public ResourceLocation getPluginUid() { return ResourceLocation.parse("productivebeesgenesis:resource_viewer"); }
	@Override public void registerCategories(IRecipeCategoryRegistration registration) {
		var helper = registration.getJeiHelpers().getGuiHelper();
		registration.addRecipeCategories(new ResourceRecipeCategory(helper, false, new ItemStack(ModBlocks.MEK_APIARY.get())),
				new ResourceRecipeCategory(helper, true, new ItemStack(ModBlocks.MEK_CENTRIFUGE.get())));
	}
	@Override public void registerIngredients(IModIngredientRegistration registration) {
		ingredients = ingredients(ResourceClientCatalog.recipes());
		registration.register(ResourceIngredient.TYPE, ingredients, new ResourceIngredient.Helper(), new ResourceIngredient.Renderer(), ResourceIngredient.CODEC);
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
		var ingredientManager = runtime.getIngredientManager();
		var oldSet = new java.util.HashSet<>(ingredients);
		var nextSet = new java.util.HashSet<>(nextIngredients);
		var removed = ingredients.stream().filter(value -> !nextSet.contains(value)).toList();
		var added = nextIngredients.stream().filter(value -> !oldSet.contains(value)).toList();
		if (!removed.isEmpty()) ingredientManager.removeIngredientsAtRuntime(ResourceIngredient.TYPE, removed);
		if (!added.isEmpty()) ingredientManager.addIngredientsAtRuntime(ResourceIngredient.TYPE, added);
		shownProduction = updateRecipes(manager, PRODUCTION, shownProduction, recipes(next, false));
		shownCentrifuging = updateRecipes(manager, CENTRIFUGING, shownCentrifuging, recipes(next, true));
		shown = next;
		ingredients = nextIngredients;
	}
	/** JEI 无公开删除接口；完整内容相同的旧对象从 JEI 自身索引取回，避免再次注册。 */
	static List<ResourceJeiRecipe> updateRecipes(IRecipeManager manager, RecipeType<ResourceJeiRecipe> type,
			List<ResourceJeiRecipe> previous, List<ResourceJeiRecipe> next) {
		if (previous.equals(next)) {
			return previous;
		}
		if (next.isEmpty()) {
			if (!previous.isEmpty()) {
				manager.hideRecipes(type, previous);
			}
			return List.of();
		}

		Set<ResourceJeiRecipe> nextSet = new HashSet<>(next);
		Map<ResourceJeiRecipe, ResourceJeiRecipe> registered = new HashMap<>();
		for (ResourceJeiRecipe recipe : previous) {
			if (nextSet.contains(recipe)) {
				registered.putIfAbsent(recipe, recipe);
			}
		}
		if (registered.size() < nextSet.size()) {
			try (var existing = manager.createRecipeLookup(type).includeHidden().get()) {
				existing.filter(nextSet::contains).forEach(recipe -> registered.putIfAbsent(recipe, recipe));
			}
		}

		Set<ResourceJeiRecipe> previousSet = new HashSet<>(previous);
		List<ResourceJeiRecipe> removed = previous.stream().filter(recipe -> !nextSet.contains(recipe)).toList();
		List<ResourceJeiRecipe> visible = new ArrayList<>(next.size());
		List<ResourceJeiRecipe> reactivated = new ArrayList<>();
		List<ResourceJeiRecipe> added = new ArrayList<>();
		for (ResourceJeiRecipe recipe : next) {
			ResourceJeiRecipe original = registered.get(recipe);
			if (original == null) {
				original = recipe;
				registered.put(original, original);
				added.add(original);
			} else if (!previousSet.contains(original)) {
				reactivated.add(original);
			}
			visible.add(original);
		}

		if (!removed.isEmpty()) {
			manager.hideRecipes(type, removed);
		}
		if (!reactivated.isEmpty()) {
			manager.unhideRecipes(type, reactivated);
		}
		if (!added.isEmpty()) {
			manager.addRecipes(type, added);
		}
		return List.copyOf(visible);
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
	}
}
