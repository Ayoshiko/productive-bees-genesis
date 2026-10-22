package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.google.gson.JsonElement;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** 注册表与运行中配方在服务器线程取快照，源 JSON 的 IO 只读取封闭的资源引用。 */
public final class BeeDesignerReader {
	private BeeDesignerReader() {
	}

	public record Snapshot(JsonObject document, Map<String, Resource> sources, long generation) {
		public Snapshot {
			document = document.deepCopy();
			sources = Map.copyOf(sources);
		}
	}

	public static ResourceLocation definitionPath(ResourceLocation bee) {
		var definitions = BeeDesignerSources.definitions.keySet().stream().filter(path -> path.getNamespace().equals(bee.getNamespace())
				&& path.getPath().substring(path.getPath().lastIndexOf('/') + 1).equals(bee.getPath())).toList();
		if (definitions.size() > 1) {
			CompoundTag runtime = BeeReloadListener.INSTANCE.getData(bee);
			if (runtime != null && runtime.contains("group")) {
				String group = runtime.getString("group");
				definitions = definitions.stream().filter(path -> {
					int separator = path.getPath().lastIndexOf('/');
					String directory = separator < 0 ? "" : path.getPath().substring(0, separator);
					return directory.equalsIgnoreCase(group);
				}).toList();
			}
		}
		if (definitions.size() != 1) {
			throw new IllegalArgumentException((definitions.isEmpty() ? "designer.error.definition_missing|"
					: "designer.error.definition_ambiguous|") + bee);
		}
		return definitions.getFirst();
	}

	public static JsonObject beeData(MinecraftServer server, ResourceLocation bee) {
		var data = BeeReloadListener.INSTANCE.getData(bee);
		if (data == null) {
			throw new IllegalArgumentException("designer.error.bee_missing|" + bee);
		}
		ResourceLocation path = definitionPath(bee);
		JsonObject response = new JsonObject();
		response.addProperty("id", bee.toString());
		response.addProperty("definition_path", path.toString());
		response.addProperty("managed", BeeDesignerSources.managed.containsKey(bee.toString()));
		response.add("origin", BeeDesignerOrigins.describe(bee));
		response.addProperty("generation", BeeDesignerSources.generation);
		response.add("definition", JsonParser.parseString(BeeDesignerSources.definitions.get(path)));
		response.add("runtime", CompoundTag.CODEC.encodeStart(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
				data.copy()).getOrThrow());
		server.getResourceManager().getResource(path.withPath("productivebees/" + path.getPath() + ".json"))
				.ifPresent(resource -> response.addProperty("source_pack", resource.sourcePackId()));
		return response;
	}

	public static Snapshot snapshot(MinecraftServer server, ResourceLocation bee) {
		if (BeeReloadListener.INSTANCE.getData(bee) == null) {
			throw new IllegalArgumentException("designer.error.bee_missing|" + bee);
		}
		ResourceLocation definitionPath = definitionPath(bee);
		JsonObject document = BeeDesignerDocument.create(bee.toString());
		document.addProperty("definition_path", definitionPath.toString());
		document.add("definition", JsonParser.parseString(BeeDesignerSources.definitions.get(definitionPath)));
		document.add("_editor_origin", BeeDesignerOrigins.describe(bee));
		String managed = BeeDesignerSources.managed.get(bee.toString());
		if (managed != null) {
			JsonObject previous = JsonParser.parseString(managed).getAsJsonObject();
			for (var entry : previous.entrySet()) {
				if (!java.util.Set.of("id", "baseline", "definition", "definition_path", "recipes").contains(entry.getKey())) {
					document.add(entry.getKey(), entry.getValue().deepCopy());
				}
			}
		}
		JsonObject recipes = document.getAsJsonObject("recipes");
		Map<String, Resource> sources = new HashMap<>();
		if (BeeDesignerLifecycle.deleted(document) && managed != null) {
			document.add("recipes", JsonParser.parseString(managed).getAsJsonObject().get("recipes").deepCopy());
			return new Snapshot(document, sources, BeeDesignerSources.generation);
		}
		for (var holder : server.getRecipeManager().getRecipes().stream()
				.sorted(Comparator.comparing(value -> value.id().toString())).toList()) {
			String type = BuiltInRegistries.RECIPE_SERIALIZER.getKey(holder.value().getSerializer()).toString();
			if (!BeeDesignerCompiler.RECIPE_TYPES.contains(type) && !java.util.Set.of("modern_industrialization", "mbd2",
					"modular_machinery_reborn").contains(ResourceLocation.parse(type).getNamespace())) {
				continue;
			}
			if (!candidate(holder.value(), type, bee, server.registryAccess())) {
				continue;
			}
			JsonObject json = encode(holder, server.registryAccess());
			if (belongs(json, bee.toString())) {
				if (recipes.size() >= 64) {
					throw new IllegalArgumentException("designer.error.recipe_limit");
				}
				recipes.add(holder.id().toString(), json);
				if (BeeDesignerMachineRecipes.host(json) != null) {
					JsonObject info = BeeDesignerMachineRuntime.describe(json);
					JsonObject sourcesInfo = BeeDesignerDocument.object(document, "machine_sources");
					if (sourcesInfo.has(holder.id().toString())) {
						JsonObject previous = sourcesInfo.getAsJsonObject(holder.id().toString());
						previous.entrySet().forEach(value -> info.add(value.getKey(), value.getValue().deepCopy()));
					}
					sourcesInfo.add(holder.id().toString(), info);
				}
				ResourceLocation path = holder.id().withPath("recipe/" + holder.id().getPath() + ".json");
				server.getResourceManager().getResource(path).ifPresent(resource -> sources.put(holder.id().toString(), resource));
			}
		}
		return new Snapshot(document, sources, BeeDesignerSources.generation);
	}

	static boolean candidate(Recipe<?> recipe, String type, ResourceLocation bee, HolderLookup.Provider registries) {
		if (type.equals("minecraft:crafting_shaped") || type.equals("minecraft:crafting_shapeless")) {
			return bee.equals(BeeDesignerEggs.bee(recipe.getResultItem(registries)));
		}
		if (ResourceLocation.parse(type).getNamespace().equals("modern_industrialization")) {
			return BeeDesignerMachineRuntime.candidate(recipe, bee);
		}
		return true;
	}

	static JsonObject encode(RecipeHolder<?> holder, HolderLookup.Provider registries) {
		DynamicOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		try {
			if (holder.value().getClass() == ShapedRecipe.class && holder.value().getSerializer() == RecipeSerializer.SHAPED_RECIPE) {
				return shaped((ShapedRecipe) holder.value(), ops, registries);
			}
			var encoded = Recipe.CODEC.encodeStart(ops, holder.value());
			if (encoded.result().isPresent()) {
				return encoded.result().get().getAsJsonObject();
			}
			return encoded.getOrThrow().getAsJsonObject();
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException("designer.error.recipe_read|" + holder.id(), failure);
		}
	}

	private static JsonObject shaped(ShapedRecipe recipe, DynamicOps<JsonElement> ops, HolderLookup.Provider registries) {
		int width = recipe.getWidth();
		int height = recipe.getHeight();
		if (width < 1 || width > 3 || height < 1 || height > 3 || recipe.getIngredients().size() != width * height) {
			throw new IllegalArgumentException("designer.error.crafting_grid");
		}
		JsonObject json = BeeDesignerDocument.recipe("minecraft:crafting_shaped");
		json.addProperty("group", recipe.getGroup());
		json.addProperty("category", recipe.category().getSerializedName());
		json.addProperty("show_notification", recipe.showNotification());
		JsonObject key = new JsonObject();
		JsonArray pattern = new JsonArray();
		for (int row = 0; row < height; row++) {
			StringBuilder symbols = new StringBuilder();
			for (int column = 0; column < width; column++) {
				int index = row * width + column;
				Ingredient ingredient = recipe.getIngredients().get(index);
				if (ingredient.isEmpty()) {
					symbols.append(' ');
				} else {
					char symbol = (char) ('A' + index);
					symbols.append(symbol);
					key.add(Character.toString(symbol), Ingredient.CODEC_NONEMPTY.encodeStart(ops, ingredient).getOrThrow());
				}
			}
			pattern.add(symbols.toString());
		}
		json.add("pattern", pattern);
		json.add("key", key);
		json.add("result", ItemStack.STRICT_CODEC.encodeStart(ops, recipe.getResultItem(registries)).getOrThrow());
		return json;
	}

	public static JsonObject readSources(Snapshot snapshot) {
		JsonObject document = snapshot.document.deepCopy();
		JsonObject recipes = document.getAsJsonObject("recipes");
		for (var entry : snapshot.sources.entrySet()) {
			try (Reader reader = entry.getValue().openAsReader()) {
				char[] buffer = new char[BeeDesignerProtocol.MAX_BYTES + 1];
				int count = 0;
				int read;
				while (count < buffer.length && (read = reader.read(buffer, count, buffer.length - count)) > 0) {
					count += read;
				}
				if (count == buffer.length) {
					throw new IllegalArgumentException("Recipe source exceeds designer budget");
				}
				JsonObject source = BeeDesignerProtocol.parse(new String(buffer, 0, count));
				boolean machine = BeeDesignerMachineRecipes.host(source) != null;
				if (!machine) {
					recipes.getAsJsonObject(entry.getKey()).entrySet().forEach(value -> source.add(value.getKey(), value.getValue()));
				}
				recipes.add(entry.getKey(), source);
				if (BeeDesignerMachineRecipes.host(source) != null) {
					JsonObject info = document.getAsJsonObject("machine_sources").getAsJsonObject(entry.getKey());
					info.addProperty("source_pack", entry.getValue().sourcePackId());
					info.addProperty("source_sha256", BeeDesignerMachineScripts.hash(new String(buffer, 0, count)));
				}
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}
		document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
		BeeDesignerProtocol.checkSize(document.toString());
		return document;
	}

	public static boolean belongs(JsonObject recipe, String bee) {
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			return BeeDesignerMachineRecipes.belongs(recipe, bee);
		}
		String type = recipe.get("type").getAsString();
		String field = switch (type) {
			case "productivebees:advanced_beehive" -> "ingredient";
			case "productivebees:bee_breeding" -> "offspring";
			case "productivebees:bee_conversion" -> "result";
			case "productivebees:bee_fishing", "productivebeesgenesis:resource_production",
					"productivebeesgenesis:resource_centrifuging" -> "bee";
			case "productivebees:bee_spawning" -> "results";
			case "productivebees:centrifuge" -> "ingredient";
			default -> "result";
		};
		if (!recipe.has(field)) {
			return false;
		}
		if (type.equals("productivebees:centrifuge")) {
			var ingredient = recipe.get(field);
			if (!ingredient.isJsonObject()) {
				return false;
			}
			JsonObject components = ingredient.getAsJsonObject().getAsJsonObject("components");
			return components != null && bee.equals(BeeDesignerDocument.text(components, "productivebees:bee_type", ""));
		}
		if (type.startsWith("minecraft:crafting_")) {
			var result = recipe.get(field);
			if (!result.isJsonObject()) {
				return false;
			}
			return ResourceLocation.parse(bee).equals(BeeDesignerEggs.bee(result.getAsJsonObject()));
		}
		return BeeDesignerDocument.containsBee(recipe.get(field), bee);
	}

	public static boolean unchanged(MinecraftServer server, JsonObject original) {
		ResourceLocation path = ResourceLocation.parse(original.get("definition_path").getAsString());
		String raw = BeeDesignerSources.definitions.get(path);
		if (raw == null || !JsonParser.parseString(raw).equals(original.get("definition"))) {
			return false;
		}
		String id = original.get("id").getAsString();
		String managed = BeeDesignerSources.managed.get(id);
		if (managed != null && !JsonParser.parseString(managed).getAsJsonObject().get("names").equals(original.get("names"))) {
			return false;
		}
		var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		JsonObject current = snapshot(server, ResourceLocation.parse(id)).document.getAsJsonObject("recipes");
		JsonObject expected = new JsonObject();
		for (var entry : original.getAsJsonObject("recipes").entrySet()) {
			var recipe = Recipe.CODEC.parse(ops, entry.getValue()).getOrThrow();
			expected.add(entry.getKey(), encode(new RecipeHolder<>(ResourceLocation.parse(entry.getKey()), recipe), server.registryAccess()));
		}
		return expected.equals(current);
	}
}
