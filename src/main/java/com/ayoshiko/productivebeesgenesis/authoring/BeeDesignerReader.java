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
import com.ayoshiko.productivebeesgenesis.port.RecipeHolder;
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
	private static Map<ResourceLocation, String> indexedDefinitions = Map.of();
	private static Map<ResourceLocation, java.util.List<ResourceLocation>> definitionPaths = Map.of();
	private BeeDesignerReader() {
	}

	public record Snapshot(JsonObject document, Map<String, Resource> sources, long generation) {
		public Snapshot {
			document = document.deepCopy();
			sources = Map.copyOf(sources);
		}
	}

	public static synchronized ResourceLocation definitionPath(ResourceLocation bee) {
		var current = BeeDesignerSources.definitions;
		if (indexedDefinitions != current) {
			Map<ResourceLocation, java.util.List<ResourceLocation>> paths = new HashMap<>();
			for (ResourceLocation path : current.keySet()) {
				ResourceLocation id = path.withPath(path.getPath().substring(path.getPath().lastIndexOf('/') + 1));
				paths.computeIfAbsent(id, unused -> new java.util.ArrayList<>()).add(path);
			}
			paths.replaceAll((id, values) -> java.util.List.copyOf(values));
			definitionPaths = Map.copyOf(paths);
			indexedDefinitions = current;
		}
		var definitions = definitionPaths.getOrDefault(bee, java.util.List.of());
		if (definitions.size() > 1) {
			CompoundTag runtime = BeeReloadListener.INSTANCE.getData(bee.toString());
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
		return definitions.get(0);
	}

	static synchronized void clear() {
		indexedDefinitions = Map.of();
		definitionPaths = Map.of();
	}

	public static JsonObject beeData(MinecraftServer server, ResourceLocation bee) {
		var data = BeeReloadListener.INSTANCE.getData(bee.toString());
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
		response.add("definition", com.ayoshiko.productivebeesgenesis.port.ForgeBeeDefinition.portableDefinition(JsonParser.parseString(BeeDesignerSources.definitions.get(path)).getAsJsonObject()));
		response.add("runtime", CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE,
				data.copy()).getOrThrow(false, message -> {}));
		server.getResourceManager().getResource(path.withPath("productivebees/" + path.getPath() + ".json"))
				.ifPresent(resource -> response.addProperty("source_pack", resource.sourcePackId()));
		return response;
	}

	public static Snapshot snapshot(MinecraftServer server, ResourceLocation bee) {
		if (com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime.reloadBlocked(server)) {
			throw new IllegalStateException("designer.error.reload_incomplete");
		}
		if (BeeReloadListener.INSTANCE.getData(bee.toString()) == null) {
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
		BeeDesignerRecipeIndex index = BeeDesignerRecipeIndex.forServer(server);
		for (var holder : index.recipes(bee)) {
			JsonObject json = index.encode(holder, server);
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
				ResourceLocation path = holder.id().withPath("recipes/" + holder.id().getPath() + ".json");
				server.getResourceManager().getResource(path).ifPresent(resource -> sources.put(holder.id().toString(), resource));
			}
		}
		return new Snapshot(document, sources, BeeDesignerSources.generation);
	}

	static boolean candidate(Recipe<?> recipe, String type, ResourceLocation bee, HolderLookup.Provider registries) {
		if (type.equals("minecraft:crafting_shaped") || type.equals("minecraft:crafting_shapeless")) {
			return bee.equals(BeeDesignerEggs.bee(recipe.getResultItem((net.minecraft.core.RegistryAccess)registries)));
		}
		if (new ResourceLocation(type).getNamespace().equals("modern_industrialization")) {
			return BeeDesignerMachineRuntime.candidate(recipe, bee);
		}
		return true;
	}

	static JsonObject encode(RecipeHolder<?> holder, HolderLookup.Provider registries) {
		DynamicOps<JsonElement> ops = JsonOps.INSTANCE;
		try {
			if (holder.value().getClass() == ShapedRecipe.class && holder.value().getSerializer() == RecipeSerializer.SHAPED_RECIPE) {
				return shaped((ShapedRecipe) holder.value(), ops, registries);
			}
			var encoded = com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.CODEC.encodeStart(ops, holder.value());
			if (encoded.result().isPresent()) {
				return encoded.result().get().getAsJsonObject();
			}
			return encoded.getOrThrow(false, message -> {}).getAsJsonObject();
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
					key.add(Character.toString(symbol), com.ayoshiko.productivebeesgenesis.port.ForgeIngredientCodecs.CODEC.encodeStart(ops, ingredient).getOrThrow(false, message -> {}));
				}
			}
			pattern.add(symbols.toString());
		}
		json.add("pattern", pattern);
		json.add("key", key);
		json.add("result", com.ayoshiko.productivebeesgenesis.port.ForgeItemStackCodec.CODEC.encodeStart(ops, recipe.getResultItem((net.minecraft.core.RegistryAccess)registries)).getOrThrow(false, message -> {}));
		return json;
	}

	public static JsonObject readSources(Snapshot snapshot) {
		JsonObject document = snapshot.document.deepCopy();
		JsonObject recipes = document.getAsJsonObject("recipes");
		char[] buffer = new char[8192];
		for (var entry : snapshot.sources.entrySet()) {
			try (Reader reader = entry.getValue().openAsReader()) {
				StringBuilder text = new StringBuilder();
				int read;
				while ((read = reader.read(buffer)) != -1) {
					if (text.length() + read > BeeDesignerProtocol.MAX_BYTES) {
						throw new IllegalArgumentException("Recipe source exceeds designer budget");
					}
					text.append(buffer, 0, read);
				}
				String raw = text.toString();
				JsonObject source = BeeDesignerProtocol.parse(raw);
				source.remove("productivebeesgenesis:designer_recipe");
				var host = BeeDesignerMachineRecipes.host(source);
				JsonObject runtime = recipes.getAsJsonObject(entry.getKey());
				if (host == BeeDesignerMachineRecipes.Host.MI) {
					source = machineRuntimeSource(source, runtime).getAsJsonObject();
				} else if (host == null) {
					JsonObject retained = source;
					runtime.entrySet().forEach(value -> retained.add(value.getKey(), value.getValue().deepCopy()));
				}
				recipes.add(entry.getKey(), source);
				if (BeeDesignerMachineRecipes.host(source) != null) {
					JsonObject info = document.getAsJsonObject("machine_sources").getAsJsonObject(entry.getKey());
					info.addProperty("source_pack", entry.getValue().sourcePackId());
					info.addProperty("source_sha256", BeeDesignerMachineScripts.hash(raw));
				}
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}
		BeeDesignerLegacyOutputs.capture(document);
		document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
		BeeDesignerProtocol.checkSize(document.toString());
		return document;
	}

	/** MI 的源文件可能尚未统一材料，实际配方为准；匹配原节点后保留宿主未编码的扩展字段。 */
	private static JsonElement machineRuntimeSource(JsonElement source, JsonElement runtime) {
		if (source.isJsonObject() && runtime.isJsonObject()) {
			JsonObject result = source.getAsJsonObject().deepCopy();
			JsonObject actual = runtime.getAsJsonObject();
			if (java.util.Set.of("item", "items", "tag", "fluid", "fluids").stream().anyMatch(actual::has)) {
				for (String field : java.util.Set.of("item", "items", "tag", "fluid", "fluids", "components", "strict", "type")) {
					result.remove(field);
				}
			}
			for (var entry : actual.entrySet()) {
				JsonElement previous = result.get(entry.getKey());
				result.add(entry.getKey(), previous == null ? entry.getValue().deepCopy()
						: machineRuntimeSource(previous, entry.getValue()));
			}
			return result;
		}
		if (source.isJsonArray() && runtime.isJsonArray()) {
			JsonArray result = new JsonArray();
			var remaining = new java.util.ArrayList<JsonElement>();
			source.getAsJsonArray().forEach(remaining::add);
			for (JsonElement actual : runtime.getAsJsonArray()) {
				JsonElement previous = remaining.stream().filter(value -> materialIdentity(value).equals(materialIdentity(actual)))
						.findFirst().orElse(null);
				if (previous != null) {
					remaining.remove(previous);
					result.add(machineRuntimeSource(previous, actual));
				} else {
					result.add(actual.deepCopy());
				}
			}
			for (JsonElement unmatched : remaining) {
				if (unmatched.isJsonObject() && unmatched.getAsJsonObject().keySet().stream()
						.anyMatch(field -> !java.util.Set.of("item", "items", "tag", "fluid", "fluids", "components",
								"strict", "type", "amount", "probability").contains(field))) {
					throw new IllegalArgumentException("Machine runtime changed an ingredient with unknown fields");
				}
			}
			return result;
		}
		return runtime.deepCopy();
	}

	private static JsonObject materialIdentity(JsonElement value) {
		JsonObject identity = new JsonObject();
		if (value.isJsonObject()) {
			for (String field : java.util.Set.of("item", "items", "tag", "fluid", "fluids", "components", "type")) {
				if (value.getAsJsonObject().has(field)) {
					identity.add(field, value.getAsJsonObject().get(field));
				}
			}
		}
		return identity;
	}

	public static boolean belongs(JsonObject recipe, String bee) {
		if (BeeDesignerWorldConversions.supports(BeeDesignerDocument.text(recipe, "type", ""))) {
			return BeeDesignerWorldConversions.owners(recipe).contains(bee);
		}
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
		if (type.startsWith("minecraft:crafting_") || type.equals("productivebees:bottler")) {
			var result = recipe.get(field);
			if (!result.isJsonObject()) {
				return false;
			}
			return new ResourceLocation(bee).equals(BeeDesignerEggs.bee(result.getAsJsonObject()));
		}
		return BeeDesignerDocument.containsBee(recipe.get(field), bee);
	}

	public static boolean unchanged(MinecraftServer server, JsonObject original) {
		ResourceLocation path = new ResourceLocation(original.get("definition_path").getAsString());
		String raw = BeeDesignerSources.definitions.get(path);
		if (raw == null || !JsonParser.parseString(raw).equals(original.get("definition"))) {
			return false;
		}
		String id = original.get("id").getAsString();
		String managed = BeeDesignerSources.managed.get(id);
		if (managed != null && !JsonParser.parseString(managed).getAsJsonObject().get("names").equals(original.get("names"))) {
			return false;
		}
		var ops = JsonOps.INSTANCE;
		JsonObject current = snapshot(server, new ResourceLocation(id)).document.getAsJsonObject("recipes");
		if (BeeDesignerMyriad.recipesOnly(original)) {
			JsonObject retained = managed == null ? new JsonObject() : JsonParser.parseString(managed).getAsJsonObject().getAsJsonObject("recipes");
			JsonObject imported = original.getAsJsonObject("recipes");
			current.entrySet().removeIf(entry -> !BeeDesignerMyriad.additionalType(entry.getValue().getAsJsonObject())
					&& !(retained.has(entry.getKey()) && BeeDesignerMyriad.customRecipe(retained.getAsJsonObject(entry.getKey())))
					&& !(imported.has(entry.getKey()) && BeeDesignerMyriad.customRecipe(imported.getAsJsonObject(entry.getKey()))));
		}
		JsonObject expected = new JsonObject();
		for (var entry : original.getAsJsonObject("recipes").entrySet()) {
			var recipe = com.ayoshiko.productivebeesgenesis.port.ForgeRecipeCodecs.CODEC.parse(ops, entry.getValue()).getOrThrow(false, message -> {});
			expected.add(entry.getKey(), encode(new RecipeHolder<>(new ResourceLocation(entry.getKey()), recipe), server.registryAccess()));
		}
		return expected.equals(current);
	}
}
