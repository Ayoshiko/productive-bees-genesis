package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import cy.jdkdigital.productivebees.common.crafting.conditions.BeeExistsCondition;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.conditions.ICondition;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 生成标准文件，校验复用实际配方 codec；不另建生产执行器。 */
public final class BeeDesignerCompiler {
	public static final Set<String> RECIPE_TYPES = Set.of("productivebees:advanced_beehive",
			"productivebees:bottler",
			"productivebees:item_conversion", "productivebees:block_conversion",
			"productivebees:centrifuge", "productivebees:bee_breeding", "productivebees:bee_conversion",
			"productivebees:bee_spawning", "productivebees:bee_fishing", "minecraft:crafting_shapeless",
			"minecraft:crafting_shaped", "productivebeesgenesis:resource_production",
			"productivebeesgenesis:resource_centrifuging");

	private BeeDesignerCompiler() {
	}

	public static void validate(JsonObject document, MinecraftServer server) {
		validate(document, server, Set.of());
	}

	static void validate(JsonObject document, MinecraftServer server, Set<ResourceLocation> batchBees) {
		HolderLookup.Provider registries = server.registryAccess();
		validateStructure(document);
		ResourceLocation bee = ResourceLocation.parse(string(document, "id"));
		JsonObject definition = document.getAsJsonObject("definition");
		if (definition == null) {
			throw new IllegalArgumentException("Missing bee definition");
		}
		for (String key : new String[]{"primaryColor", "secondaryColor", "particleColor", "glowColor", "tertiaryColor"}) {
			if (definition.has(key) && !string(definition, key).matches("#[0-9a-fA-F]{6}")) {
				throw new IllegalArgumentException("designer.error.field_color|" + key);
			}
		}
		int flowers = 0;
		for (String key : new String[]{"flowerItem", "flowerBlock", "flowerTag", "flowerFluid", "flowerResource"}) {
			if (definition.has(key)) {
				flowers++;
			}
		}
		if (flowers > 1) {
			throw new IllegalArgumentException("Choose exactly one flower type");
		}
		for (String key : new String[]{"flowerItem", "breedingItem"}) {
			if (definition.has(key)) {
				String value = string(definition, key);
				if (key.equals("breedingItem") && value.isEmpty()) {
					continue;
				}
				if (key.equals("breedingItem") && value.startsWith("#")) {
					ResourceLocation.parse(value.substring(1));
					continue;
				}
				ResourceLocation item = ResourceLocation.parse(value);
				if (!BuiltInRegistries.ITEM.containsKey(item) || item.toString().equals("minecraft:air")) {
					throw new IllegalArgumentException(key + ": unknown item " + item);
				}
			}
		}
		if (definition.has("flowerBlock")) {
			ResourceLocation block = ResourceLocation.parse(string(definition, "flowerBlock"));
			if (!BuiltInRegistries.BLOCK.containsKey(block) || BuiltInRegistries.BLOCK.get(block) == Blocks.AIR
					&& (!definition.has("createComb") || definition.get("createComb").getAsBoolean())) {
				throw new IllegalArgumentException("flowerBlock: unknown block " + block);
			}
		}
		if (definition.has("flowerTag")) {
			String tag = string(definition, "flowerTag");
			ResourceLocation id = ResourceLocation.parse(tag.startsWith("!") ? tag.substring(1) : tag);
			if (BeeDesignerDocument.text(definition, "flowerType", "blocks").equals("entity_types")) {
				if (BuiltInRegistries.ENTITY_TYPE.getTag(TagKey.create(BuiltInRegistries.ENTITY_TYPE.key(), id)).isEmpty()) {
					throw new IllegalArgumentException("designer.error.flower_entity_tag|" + tag);
				}
			} else if (BuiltInRegistries.BLOCK.getTag(TagKey.create(BuiltInRegistries.BLOCK.key(), id)).isEmpty()
					&& BuiltInRegistries.ITEM.getTag(TagKey.create(BuiltInRegistries.ITEM.key(), id)).isEmpty()) {
				throw new IllegalArgumentException("flowerTag: unknown block tag " + id);
			}
		}
		if (definition.has("flowerFluid") && !ContainerFlower.legacyFluid(string(definition, "flowerFluid")).problem().isEmpty()) {
			throw new IllegalArgumentException("flowerFluid: unavailable fluid or tag");
		}
		if (definition.has("flowerResource")) {
			JsonObject flower = definition.getAsJsonObject("flowerResource");
			if (!ContainerFlower.parse(flower.toString()).problem().isEmpty()) {
				throw new IllegalArgumentException("flowerResource: invalid or unavailable container flower");
			}
			if (string(flower, "kind").equals("ae_key") && !ResourceRuntime.available(server,
					ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, flower).getOrThrow().key())) {
				throw new IllegalArgumentException("flowerResource: AE key cannot be restored");
			}
		}
		for (String key : new String[]{"selfbreed", "createComb", "fireproof", "waterproof", "coldResistant",
				"selfheal", "irradiated", "isSolitary", "isTranslucent", "isGlowing", "colorCycle", "useGlowLayer", "translucent",
				"withered", "blinding", "draconic", "slimy", "teleporting", "munchies", "redstoned", "stringy", "stingless"}) {
			if (definition.has(key) && (!definition.get(key).isJsonPrimitive()
					|| !definition.getAsJsonPrimitive(key).isBoolean())) {
				throw new IllegalArgumentException("designer.error.field_boolean|" + key);
			}
		}
		for (String key : new String[]{"size", "pollinatedSize", "speed", "attack"}) {
			if (definition.has(key)) {
				double value = number(definition.get(key), key);
				double maximum = key.equals("attack") ? Integer.MAX_VALUE : 100;
				if (!Double.isFinite(value) || value < 0 || value > maximum || (!key.equals("attack") && value == 0)) {
					throw new IllegalArgumentException("designer.error.field_range|" + key + "|" + (key.equals("attack") ? "0" : ">0") + "|" + (long) maximum);
				}
			}
		}
		if (definition.has("breedingItemCount")) {
			integer(definition.get("breedingItemCount"), "breedingItemCount", 1, 64);
		}
		if (definition.has("invulnerability")) {
			if (!definition.get("invulnerability").isJsonArray() || definition.getAsJsonArray("invulnerability").size() > 128) {
				throw new IllegalArgumentException("designer.error.invulnerability");
			}
			for (JsonElement value : definition.getAsJsonArray("invulnerability")) {
				if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank()
						|| value.getAsString().length() > 256 || value.getAsString().chars().anyMatch(Character::isISOControl)) {
					throw new IllegalArgumentException("designer.error.invulnerability");
				}
			}
		}
		conditions(definition, "conditions", server, bee, batchBees);
		BeeCreator.create(bee, definition.deepCopy());
		JsonObject recipes = document.getAsJsonObject("recipes");
		if (recipes == null || recipes.size() > 64) {
			throw new IllegalArgumentException("A bee can contain at most 64 recipes");
		}
			for (var entry : recipes.entrySet()) {
				ResourceLocation.parse(entry.getKey());
				JsonObject recipe = entry.getValue().getAsJsonObject();
				if ((!RECIPE_TYPES.contains(recipe.get("type").getAsString()) && BeeDesignerMachineRecipes.host(recipe) == null)
						|| !BeeDesignerReader.belongs(recipe, bee.toString())) {
					throw new IllegalArgumentException("Recipe is not supported or does not belong to bee: " + entry.getKey());
				}
				conditions(recipe, "neoforge:conditions", server, bee, batchBees);
				if (BeeDesignerMachineRecipes.host(recipe) != null) {
					BeeDesignerMachineRecipes.recipeIdentity(recipe, entry.getKey());
					BeeDesignerMachineRuntime.validate(recipe, server);
				}
				validateAmounts(recipe, registries, BeeDesignerLegacyOutputs.unchanged(document, entry.getKey(), recipe));
				if (BeeDesignerWorldConversions.supports(recipe.get("type").getAsString())) {
					BeeDesignerWorldConversions.validate(recipe, bee.toString());
				}
				Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), recipe).getOrThrow();
				for (String field : new String[]{"parent1", "parent2", "source", "offspring", "bee"}) {
					if (recipe.has(field) && recipe.get(field).isJsonPrimitive()) {
						ResourceLocation reference = ResourceLocation.parse(recipe.get(field).getAsString());
						if (!reference.equals(bee) && !batchBees.contains(reference) && BeeReloadListener.INSTANCE.getData(reference) == null
								&& (!BuiltInRegistries.ENTITY_TYPE.containsKey(reference)
								|| !BuiltInRegistries.ENTITY_TYPE.get(reference).is(net.minecraft.tags.EntityTypeTags.BEEHIVE_INHABITORS))) {
							throw new IllegalArgumentException("designer.error.field_reference|" + field + "|" + reference);
						}
					}
				}
				if (recipe.has("outputs") && recipe.get("type").getAsString().startsWith("productivebeesgenesis:")) {
					for (JsonElement element : recipe.getAsJsonArray("outputs")) {
						var output = ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, element).getOrThrow();
						boolean available = ResourceRuntime.available(server, output.key());
						if (!available) {
							throw new IllegalArgumentException("Resource is unavailable: " + output.key());
						}
					}
				}
			}
	}

	private static void validateNames(JsonObject names) {
		if (names == null || names.size() > 64) {
			throw new IllegalArgumentException("designer.error.names");
		}
		for (var entry : names.entrySet()) {
			if (!entry.getKey().matches("[a-z]{2,3}(?:_[a-z0-9]{2,8})?") || string(names, entry.getKey()).length() > 128
					|| entry.getValue().getAsString().contains("%")) {
				throw new IllegalArgumentException("Invalid display name");
			}
		}
	}

	static void validateStructure(JsonObject document) {
		BeeDesignerProtocol.parse(document.toString());
		integer(document.get("schema_version"), "schema_version", 1, 1);
		ResourceLocation bee = ResourceLocation.parse(string(document, "id"));
		if (!bee.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IllegalArgumentException("Bee ID must have one path segment (1..96 characters)");
		}
		ResourceLocation path = ResourceLocation.parse(string(document, "definition_path"));
		if (!path.getNamespace().equals(bee.getNamespace()) || path.getPath().contains("..")
				|| !path.getPath().substring(path.getPath().lastIndexOf('/') + 1).equals(bee.getPath())) {
			throw new IllegalArgumentException("Definition path must preserve the bee identity");
		}
		if (!document.has("definition") || !document.get("definition").isJsonObject()
				|| !document.has("recipes") || !document.get("recipes").isJsonObject()
				|| !document.has("names") || !document.get("names").isJsonObject()) {
			throw new IllegalArgumentException("Missing definition, recipes or names object");
		}
		validateNames(document.getAsJsonObject("names"));
		BeeDesignerCredits.validateDocument(document);
		BeeDesignerLifecycle.validate(document);
		if (document.has("disabled_recipes")) {
			var disabled = document.getAsJsonArray("disabled_recipes");
			if (disabled.size() > 256) {
				throw new IllegalArgumentException("Too many retired recipe IDs");
			}
			for (var value : disabled) {
				ResourceLocation.parse(value.getAsString());
			}
		}
	}

	static void validateAmounts(JsonObject recipe, HolderLookup.Provider registries) {
		validateAmounts(recipe, registries, false);
	}

	private static void validateAmounts(JsonObject recipe, HolderLookup.Provider registries, boolean preserved) {
		String type = string(recipe, "type");
		if (type.startsWith("minecraft:crafting_") && recipe.has("result") && recipe.get("result").isJsonObject()) {
			BeeDesignerEggs.validate(recipe.getAsJsonObject("result"), registries);
		}
		if (type.equals("productivebees:advanced_beehive") || type.equals("productivebees:centrifuge")) {
			String field = type.endsWith("advanced_beehive") ? "results" : "outputs";
			if (recipe.has(field)) {
				var outputs = recipe.getAsJsonArray(field);
				if (outputs.size() > 16) {
					throw new IllegalArgumentException("At most 16 item outputs per recipe");
				}
				for (var value : outputs) {
					var output = TagOutputRecipe.ChancedOutput.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), value).getOrThrow();
					if (output.min() < 1 || output.max() < 1 || output.min() > Integer.MAX_VALUE / 4
							|| output.max() < output.min() && !preserved || output.max() > Integer.MAX_VALUE / 4
							|| output.ingredient().isEmpty() || output.ingredient().getItems().length == 0) {
						throw new IllegalArgumentException("Invalid or empty item output");
					}
					JsonObject raw = value.getAsJsonObject();
					for (String count : new String[]{"min", "max"}) {
						if (raw.has(count)) {
							integer(raw.get(count), count, 1, Integer.MAX_VALUE / 4);
						}
					}
					if (raw.has("chance")) {
						outputChance(number(raw.get("chance"), "chance"), preserved);
					}
					if (raw.get("item").isJsonObject()) {
						JsonObject ingredient = raw.getAsJsonObject("item");
						if (ingredient.has("components") && !ingredient.has("type")) {
							throw new IllegalArgumentException("Item components require an explicit component ingredient type");
						}
					}
					outputChance(output.chance(), preserved);
				}
			}
			if (recipe.has("fluid")) {
				integer(recipe.getAsJsonObject("fluid").get("amount"), "fluid.amount", 1, Integer.MAX_VALUE / 4);
			}
		}
		for (String field : new String[]{"processingTime", "processing_time"}) {
			if (recipe.has(field)) {
				int minimum = type.equals("productivebees:centrifuge") && field.equals("processingTime") ? Integer.MIN_VALUE : 1;
				integer(recipe.get(field), field, minimum, Integer.MAX_VALUE / 4);
			}
		}
		if (recipe.has("chance")) {
			double chance = number(recipe.get("chance"), "chance");
			if (chance < 0 || chance > 1) {
				throw new IllegalArgumentException("designer.error.field_range|chance|0|1");
			}
		}
		if (recipe.has("parentDeathChance")) {
			double chance = number(recipe.get("parentDeathChance"), "parentDeathChance");
			if (chance < 0 || chance > 1) {
				throw new IllegalArgumentException("designer.error.field_range|parentDeathChance|0|1");
			}
		}
	}

	private static void conditions(JsonObject json, String field, MinecraftServer server, ResourceLocation draftBee, Set<ResourceLocation> batchBees) {
		if (!json.has(field)) {
			return;
		}
		if (!json.get(field).isJsonArray() || json.getAsJsonArray(field).size() > 32) {
			throw new IllegalArgumentException("At most 32 loading conditions are supported");
		}
		var context = new ICondition.IContext() {
			@Override
			public <T> Map<ResourceLocation, Collection<Holder<T>>> getAllTags(ResourceKey<? extends Registry<T>> registry) {
				return server.registryAccess().registryOrThrow(registry).getTags().collect(Collectors.toMap(
						pair -> pair.getFirst().location(), pair -> pair.getSecond().stream().toList()));
			}
		};
		JsonElement evaluated = json.get(field).deepCopy();
		resolveBatchConditions(evaluated, draftBee, batchBees);
		for (var condition : ICondition.LIST_CODEC.parse(server.registryAccess().createSerializationContext(JsonOps.INSTANCE),
				evaluated).getOrThrow()) {
			if (condition instanceof BeeExistsCondition exists && (exists.beeName().equals(draftBee) || batchBees.contains(exists.beeName()))) {
				continue;
			}
			if (!condition.test(context)) {
				throw new IllegalArgumentException("A content condition is currently false; this revision cannot be activated");
			}
		}
	}

	private static void resolveBatchConditions(JsonElement value, ResourceLocation draftBee, Set<ResourceLocation> batchBees) {
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(entry -> resolveBatchConditions(entry, draftBee, batchBees));
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (BeeDesignerDocument.text(object, "type", "").equals("productivebees:bee_exists")) {
				ResourceLocation bee = ResourceLocation.parse(string(object, "bee"));
				if (bee.equals(draftBee) || batchBees.contains(bee)) {
					object.remove("bee");
					object.addProperty("type", "neoforge:true");
				}
			}
			object.asMap().values().forEach(entry -> resolveBatchConditions(entry, draftBee, batchBees));
		}
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
			throw new IllegalArgumentException(key + ": expected string");
		}
		return value.getAsString();
	}

	private static double number(JsonElement value, String field) {
		if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			throw new IllegalArgumentException("designer.error.field_number|" + field);
		}
		double result = value.getAsDouble();
		if (!Double.isFinite(result)) {
			throw new IllegalArgumentException("designer.error.field_number|" + field);
		}
		return result;
	}

	private static void integer(JsonElement value, String field, int min, int max) {
		double result = number(value, field);
		if (result != Math.rint(result) || result < min || result > max) {
			throw new IllegalArgumentException("designer.error.field_range|" + field + "|" + min + "|" + max);
		}
	}

	private static void outputChance(double chance, boolean preserved) {
		if (preserved && Double.isFinite(chance) && Float.isFinite((float) chance) && chance >= 0) {
			return;
		}
		probability(chance);
	}

	private static void probability(double chance) {
		if (!Double.isFinite(chance) || chance < 0 || chance > 1) {
			throw new IllegalArgumentException("Chance must be finite and in [0, 1]");
		}
	}

	public static Map<String, String> files(Map<String, String> documents) {
		Map<String, String> files = new LinkedHashMap<>();
		JsonObject languages = new JsonObject();
		languages.add("zh_cn", new JsonObject());
		languages.add("en_us", new JsonObject());
		JsonObject index = new JsonObject();
		Map<String, String> names = new LinkedHashMap<>();
        Map<String, JsonObject> sharedRecipes = new LinkedHashMap<>();
        Set<String> retired = BeeDesignerLifecycle.retiredIds(documents);
		for (var entry : documents.entrySet()) {
			JsonObject document = com.google.gson.JsonParser.parseString(entry.getValue()).getAsJsonObject();
			document.remove("_editor_recovery");
			validateStructure(document);
			if (BeeDesignerMyriad.recipesOnly(document)) {
				BeeDesignerMyriad.validateRecipes(document, document);
			}
			ResourceLocation bee = ResourceLocation.parse(document.get("id").getAsString());
			if (!entry.getKey().equals(bee.toString())) {
				throw new IllegalArgumentException("Managed index identity mismatch");
			}
			if (names.putIfAbsent(BeeDesignerDocument.translationKey(bee.toString()), bee.toString()) != null) {
				throw new IllegalArgumentException("PB name key is shared by bees with the same path: " + bee.getPath());
			}
			ResourceLocation definition = ResourceLocation.parse(document.get("definition_path").getAsString());
			if (!BeeDesignerMyriad.recipesOnly(document)) {
				putFile(files, "data/" + definition.getNamespace() + "/productivebees/" + definition.getPath() + ".json",
						document.get("definition").toString());
			}
			for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
				ResourceLocation id = ResourceLocation.parse(recipe.getKey());
				BeeDesignerMachineRecipes.recipeIdentity(recipe.getValue().getAsJsonObject(), recipe.getKey());
                JsonObject source = recipe.getValue().getAsJsonObject();
                JsonObject previous = sharedRecipes.putIfAbsent(recipe.getKey(), source);
                if (previous != null) {
                    if (!previous.equals(source) || !(BeeDesignerDocument.text(source, "type", "").equals("productivebees:bee_spawning")
                            || BeeDesignerWorldConversions.supports(BeeDesignerDocument.text(source, "type", "")))
                            || !BeeDesignerReader.belongs(source, bee.toString())) {
                        throw new IllegalArgumentException("designer.error.shared_recipe_conflict|" + recipe.getKey());
                    }
                    continue;
                }
                JsonObject active = BeeDesignerLifecycle.activeRecipe(document, source, retired);
                if (active != null) {
                    active = active.deepCopy();
                    active.addProperty("productivebeesgenesis:designer_recipe", true);
                }
				putFile(files, "data/" + id.getNamespace() + "/recipe/" + id.getPath() + ".json", active == null
						? "{\"type\":\"minecraft:crafting_shapeless\",\"neoforge:conditions\":[{\"type\":\"neoforge:false\"}]}" : active.toString());
			}
			if (document.has("disabled_recipes")) {
				for (JsonElement value : document.getAsJsonArray("disabled_recipes")) {
					if (!document.getAsJsonObject("recipes").has(value.getAsString())) {
						ResourceLocation id = ResourceLocation.parse(value.getAsString());
						putFile(files, "data/" + id.getNamespace() + "/recipe/" + id.getPath() + ".json",
								"{\"type\":\"minecraft:crafting_shapeless\",\"neoforge:conditions\":[{\"type\":\"neoforge:false\"}]}");
					}
				}
			}
			for (var name : document.getAsJsonObject("names").entrySet()) {
				BeeDesignerDocument.object(languages, name.getKey()).addProperty(BeeDesignerDocument.translationKey(bee.toString()),
						name.getValue().getAsString());
			}
			index.add(entry.getKey(), document);
		}
		for (var language : languages.entrySet()) {
			files.put("assets/productivebees/lang/" + language.getKey() + ".json", language.getValue().toString());
		}
		files.put("data/productivebeesgenesis/bee_designer/index.json", index.toString());
		files.put("pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"description\":\"Bee Designer managed data\"}}");
		BeeDesignerStore.validateFiles(files);
		return Map.copyOf(files);
	}

	private static void putFile(Map<String, String> files, String path, String content) {
		if (path.contains("..") || path.startsWith("/") || path.contains("\\") || files.putIfAbsent(path, content) != null) {
			throw new IllegalArgumentException("Duplicate or unsafe generated path: " + path);
		}
	}
}
