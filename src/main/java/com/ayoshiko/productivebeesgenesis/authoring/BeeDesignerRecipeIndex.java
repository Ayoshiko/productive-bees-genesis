package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BottlerRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 服务器线程持有按蜂种归属的代际索引；只编码相关声明，重载和停服释放引用。 */
final class BeeDesignerRecipeIndex {
	private static final Map<MinecraftServer, BeeDesignerRecipeIndex> SERVERS = new HashMap<>();
	private final RecipeManager manager;
	private final long generation;
	private final long recipeVersion;
	private final Map<ResourceLocation, List<RecipeHolder<?>>> byBee = new HashMap<>();
	private final Map<ResourceLocation, Encoded> encoded = new LinkedHashMap<>(64, 0.75F, true);
	private int bytes;

	private BeeDesignerRecipeIndex(MinecraftServer server) {
		manager = server.getRecipeManager();
		generation = BeeDesignerSources.generation;
		recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		for (var holder : manager.getRecipes()) {
			String type = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()).toString();
			if (!BeeDesignerCompiler.RECIPE_TYPES.contains(type) && !Set.of("modern_industrialization", "mbd2",
					"modular_machinery_reborn").contains(ResourceLocation.parse(type).getNamespace())) {
				continue;
			}
			try {
				for (ResourceLocation bee : owners(holder, type, server)) {
					byBee.computeIfAbsent(bee, unused -> new ArrayList<>()).add(holder);
				}
			} catch (RuntimeException failure) {
				throw new IllegalArgumentException("designer.error.recipe_read|" + holder.id(), failure);
			}
		}
		byBee.replaceAll((bee, recipes) -> recipes.stream().sorted(Comparator.comparing(holder -> holder.id().toString())).toList());
	}

	static BeeDesignerRecipeIndex forServer(MinecraftServer server) {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Bee recipe indexing requires the server thread");
		}
		BeeDesignerRecipeIndex index = SERVERS.get(server);
		if (index == null || index.manager != server.getRecipeManager() || index.generation != BeeDesignerSources.generation
				|| index.recipeVersion != ProductiveBeesGenesis.RECIPE_VERSION.get()) {
			index = new BeeDesignerRecipeIndex(server);
			SERVERS.put(server, index);
		}
		return index;
	}

	List<RecipeHolder<?>> recipes(ResourceLocation bee) {
		return byBee.getOrDefault(bee, List.of());
	}

	private Set<ResourceLocation> owners(RecipeHolder<?> holder, String type, MinecraftServer server) {
		var recipe = holder.value();
		if (recipe instanceof AdvancedBeehiveRecipe beehive) {
			return Set.of(owner(beehive.ingredient));
		}
		if (recipe instanceof BeeBreedingRecipe breeding) {
			return Set.of(owner(breeding.offspring));
		}
		if (recipe instanceof BeeConversionRecipe conversion) {
			return Set.of(owner(conversion.result));
		}
		if (recipe instanceof BeeFishingRecipe fishing) {
			return Set.of(owner(fishing.output));
		}
		if (recipe instanceof BeeSpawningRecipe spawning) {
			return owners(spawning.output);
		}
		if (recipe instanceof BlockConversionRecipe conversion) {
			return owners(conversion.bees);
		}
		if (recipe instanceof ItemConversionRecipe conversion) {
			return owners(conversion.bees);
		}
		if (recipe instanceof ResourceProductionRecipe production) {
			return Set.of(production.bee());
		}
		if (recipe instanceof BottlerRecipe bottler) {
			ResourceLocation bee = BeeDesignerEggs.bee(bottler.result);
			return bee == null ? Set.of() : Set.of(bee);
		}
		if (recipe instanceof ResourceCentrifugeRecipe production) {
			return Set.of(production.bee());
		}
		if (type.equals("minecraft:crafting_shaped") || type.equals("minecraft:crafting_shapeless")) {
			ResourceLocation bee = BeeDesignerEggs.bee(recipe.getResultItem(server.registryAccess()));
			return bee == null ? Set.of() : Set.of(bee);
		}
		if (ResourceLocation.parse(type).getNamespace().equals("modern_industrialization")) {
			Set<ResourceLocation> owners = BeeDesignerMachineRuntime.outputBees(recipe);
			if (owners != null) {
				return owners;
			}
		}
		JsonObject source = encode(holder, server);
		Set<ResourceLocation> owners = new HashSet<>();
		if (BeeDesignerMachineRecipes.host(source) != null) {
			for (var entry : BeeDesignerMachineRecipes.entries(source)) {
				if (!entry.input() && entry.kind().equals("item")) {
					ResourceLocation bee = BeeDesignerEggs.bee(BeeDesignerMachineRecipes.itemIdentity(entry));
					if (bee != null) {
						owners.add(bee);
					}
				}
			}
		} else if (type.equals("productivebees:centrifuge") && source.get("ingredient") instanceof JsonObject ingredient) {
			JsonObject components = ingredient.getAsJsonObject("components");
			if (components != null) {
				ResourceLocation bee = ResourceLocation.tryParse(BeeDesignerDocument.text(components, "productivebees:bee_type", ""));
				if (bee != null) {
					owners.add(bee);
				}
			}
		}
		return Set.copyOf(owners);
	}

	private static Set<ResourceLocation> owners(List<Supplier<BeeIngredient>> bees) {
		Set<ResourceLocation> result = new HashSet<>();
		for (var bee : bees) {
			result.add(owner(bee));
		}
		return Set.copyOf(result);
	}

	private static ResourceLocation owner(Supplier<BeeIngredient> supplier) {
		BeeIngredient ingredient = supplier.get();
		if (ingredient == null) {
			throw new IllegalArgumentException("Bee ingredient is unavailable in the current resource generation");
		}
		return ingredient.getBeeType();
	}

	JsonObject encode(RecipeHolder<?> recipe, MinecraftServer server) {
		Encoded cached = encoded.get(recipe.id());
		if (cached != null) {
			return cached.source.deepCopy();
		}
		JsonObject source = BeeDesignerReader.encode(recipe, server.registryAccess());
		int size = source.toString().getBytes(StandardCharsets.UTF_8).length;
		if (size <= BeeDesignerProtocol.MAX_BYTES) {
			while (!encoded.isEmpty() && (encoded.size() >= 4096 || bytes + size > 2 * 1024 * 1024)) {
				var key = encoded.keySet().iterator().next();
				bytes -= encoded.remove(key).bytes;
			}
			encoded.put(recipe.id(), new Encoded(source.deepCopy(), size));
			bytes += size;
		}
		return source;
	}

	static void clear(MinecraftServer server) {
		SERVERS.remove(server);
	}

	private record Encoded(JsonObject source, int bytes) {
	}
}
