package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 先锁定整批身份再改引用，复制冲突蜂不会让繁殖链悄悄指向目标包的旧蜂。 */
public final class BeeDesignerMigration {
	public enum Policy {
		COPY_CONFLICTS, KEEP_IDENTITIES, SKIP_CONFLICTS, COPY_ALL
	}

	public record Plan(Map<String, String> documents, Map<String, String> identities, List<String> skipped) {
		public Plan {
			documents = Map.copyOf(documents);
			identities = Map.copyOf(identities);
			skipped = List.copyOf(skipped);
		}
	}

	private BeeDesignerMigration() {
	}

	public static Plan plan(Map<String, String> imported, Collection<String> world, Collection<String> drafts,
			Collection<String> targetRecipes, Policy policy) {
		if (imported.isEmpty() || imported.size() > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		Map<String, JsonObject> sources = new LinkedHashMap<>();
		imported.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			JsonObject source = BeeDesignerProtocol.parse(entry.getValue());
			BeeDesignerCompiler.validateStructure(source);
			if (!entry.getKey().equals(source.get("id").getAsString())) {
				throw new IllegalArgumentException("designer.error.collection_identity");
			}
			sources.put(entry.getKey(), source);
		});
		Set<String> reserved = new LinkedHashSet<>(world);
		reserved.addAll(drafts);
		reserved.addAll(sources.keySet());
		Set<String> names = new LinkedHashSet<>();
		world.forEach(id -> names.add(BeeDesignerDocument.translationKey(id)));
		drafts.forEach(id -> names.add(BeeDesignerDocument.translationKey(id)));
		Map<String, String> identities = new LinkedHashMap<>();
		List<String> skipped = new ArrayList<>();
		for (var entry : sources.entrySet()) {
			String id = entry.getKey();
			JsonObject source = entry.getValue();
			var conflicts = BeeDesignerImportConflicts.inspect(id, world, drafts);
			boolean nameCollision = names.contains(BeeDesignerDocument.translationKey(id)) && !world.contains(id) && !drafts.contains(id)
					|| !conflicts.canKeepIdentity();
			boolean identityCollision = conflicts.worldIdentity() || conflicts.localDraft();
			boolean recipeCollision = source.getAsJsonObject("recipes").keySet().stream().anyMatch(targetRecipes::contains);
			boolean conflict = nameCollision || identityCollision || recipeCollision;
			if (policy == Policy.SKIP_CONFLICTS && conflict) {
				skipped.add(id);
				continue;
			}
			boolean copy = policy == Policy.COPY_ALL || policy == Policy.COPY_CONFLICTS && conflict || nameCollision;
			if (BeeDesignerMyriad.recipesOnly(source)) {
				copy = false;
				if (!world.contains(id)) {
					throw new IllegalArgumentException("designer.error.myriad_import");
				}
			}
			String target = copy ? BeeDesignerDocument.nextCopyId(source, reserved, targetRecipes) : id;
			reserved.add(target);
			names.add(BeeDesignerDocument.translationKey(target));
			identities.put(id, target);
		}
		Map<String, JsonObject> recipeSources = new LinkedHashMap<>();
		Map<String, List<String>> recipeOwners = new LinkedHashMap<>();
		Set<String> reservedRecipes = new LinkedHashSet<>(targetRecipes);
		sources.values().forEach(source -> reservedRecipes.addAll(source.getAsJsonObject("recipes").keySet()));
		for (var entry : sources.entrySet()) {
			if (!identities.containsKey(entry.getKey())) {
				continue;
			}
			for (var recipe : entry.getValue().getAsJsonObject("recipes").entrySet()) {
				JsonObject value = recipe.getValue().getAsJsonObject();
				JsonObject previous = recipeSources.putIfAbsent(recipe.getKey(), value);
				if (previous != null && !previous.equals(value)) {
					throw new IllegalArgumentException("designer.error.migration_recipe_conflict|" + recipe.getKey());
				}
				recipeOwners.computeIfAbsent(recipe.getKey(), key -> new ArrayList<>()).add(entry.getKey());
			}
		}
		Map<String, String> mappedRecipes = new LinkedHashMap<>();
		Map<String, Integer> recipeIndexes = new LinkedHashMap<>();
		for (var entry : recipeOwners.entrySet()) {
			String next = entry.getKey();
			String copyingOwner = entry.getValue().stream().filter(owner -> !owner.equals(identities.get(owner))
					|| targetRecipes.contains(entry.getKey()) && !world.contains(owner)).findFirst().orElse(null);
			if (copyingOwner != null) {
				ResourceLocation oldBee = ResourceLocation.parse(copyingOwner);
				ResourceLocation newBee = ResourceLocation.parse(identities.get(copyingOwner));
				String oldBase = oldBee.getNamespace() + ":bee_designer/" + oldBee.getPath();
				String newBase = newBee.getNamespace() + ":bee_designer/" + newBee.getPath();
				if (next.startsWith(oldBase + "/production/")) {
					next = newBase + next.substring(oldBase.length());
				} else {
					next = newBase + "/migrated/" + recipeIndexes.merge(copyingOwner, 1, Integer::sum);
				}
				String candidate = next;
				int suffix = 2;
				while (reservedRecipes.contains(next)) {
					next = candidate + "_" + suffix;
					suffix++;
				}
				reservedRecipes.add(next);
			}
			mappedRecipes.put(entry.getKey(), next);
		}
		Map<String, String> migrated = new LinkedHashMap<>();
		for (var entry : sources.entrySet()) {
			String target = identities.get(entry.getKey());
			if (target == null) {
				continue;
			}
			JsonObject source = entry.getValue();
			JsonObject document = target.equals(entry.getKey()) ? source.deepCopy() : BeeDesignerDocument.copyAs(source, target);
			Map<String, String> recipeIds = new LinkedHashMap<>();
			JsonObject recipes = new JsonObject();
			for (var recipe : source.getAsJsonObject("recipes").entrySet()) {
				String next = mappedRecipes.get(recipe.getKey());
				JsonObject recipeSource = recipe.getValue().getAsJsonObject();
				JsonObject value = recipeSource.deepCopy();
				rewriteRecipe(value, identities);
				if (!recipe.getKey().equals(next)) {
					if (BeeDesignerWorldConversions.shared(recipeSource)) {
						JsonArray owners = new JsonArray();
						BeeDesignerWorldConversions.owners(recipeSource).stream().filter(identities::containsKey)
								.map(identities::get).sorted().forEach(owners::add);
						value.add("bees", owners);
					}
					value.remove(BeeDesignerMyriad.CUSTOM_RECIPE);
				}
				if (BeeDesignerMachineRecipes.host(value) == BeeDesignerMachineRecipes.Host.MBD2) {
					value.addProperty("id", next);
				}
				if (recipes.has(next) && !recipes.get(next).equals(value)) {
					throw new IllegalArgumentException("designer.error.migration_recipe_conflict|" + next);
				}
				recipeIds.put(recipe.getKey(), next);
				recipes.add(next, value);
			}
			document.add("recipes", recipes);
			BeeDesignerLegacyOutputs.rebase(source, document, recipeIds);
			remapRecipeMetadata(document, source, recipeIds);
			rewriteConditions(document.getAsJsonObject("definition"), identities);
			document.remove("disabled_recipes");
			document.addProperty("baseline", "new");
			JsonObject migration = new JsonObject();
			migration.addProperty("source_id", entry.getKey());
			migration.addProperty("target_id", target);
			document.add("_editor_migration", migration);
			BeeDesignerCompiler.validateStructure(document);
			migrated.put(target, document.toString());
		}
		return new Plan(migrated, identities, skipped);
	}

	private static void remapRecipeMetadata(JsonObject document, JsonObject source, Map<String, String> recipes) {
		if (source.has("production_nodes")) {
			JsonObject nodes = source.getAsJsonObject("production_nodes").deepCopy();
			nodes.asMap().values().forEach(value -> {
				JsonArray ids = new JsonArray();
				value.getAsJsonObject().getAsJsonArray("recipe_ids").forEach(id -> {
					String mapped = recipes.get(id.getAsString());
					if (mapped == null) {
						throw new IllegalArgumentException("designer.error.migration_recipe_conflict|" + id.getAsString());
					}
					ids.add(mapped);
				});
				value.getAsJsonObject().add("recipe_ids", ids);
			});
			document.add("production_nodes", nodes);
		}
		if (source.has("machine_sources")) {
			JsonObject info = new JsonObject();
			source.getAsJsonObject("machine_sources").entrySet().forEach(entry -> {
				if (recipes.containsKey(entry.getKey())) {
					info.add(recipes.get(entry.getKey()), entry.getValue().deepCopy());
				}
			});
			document.add("machine_sources", info);
		}
	}

	private static void rewriteRecipe(JsonObject recipe, Map<String, String> identities) {
		String type = BeeDesignerDocument.text(recipe, "type", "");
		switch (type) {
			case "productivebees:advanced_beehive" -> rewriteField(recipe, "ingredient", identities);
			case "productivebees:bee_breeding" -> {
				rewriteField(recipe, "parent1", identities);
				rewriteField(recipe, "parent2", identities);
				rewriteField(recipe, "offspring", identities);
			}
			case "productivebees:bee_conversion" -> {
				rewriteField(recipe, "source", identities);
				rewriteField(recipe, "result", identities);
			}
			case "productivebees:bee_fishing", "productivebeesgenesis:resource_production",
					"productivebeesgenesis:resource_centrifuging" -> rewriteField(recipe, "bee", identities);
			case "productivebees:bee_spawning" -> rewriteArray(recipe, "results", identities);
			case "productivebees:item_conversion", "productivebees:block_conversion" -> rewriteArray(recipe, "bees", identities);
			default -> {
				// 机器与其它配方的蜂种身份在组件内处理，不重写任意物品 ID。
			}
		}
		rewriteConditions(recipe, identities);
		rewriteComponents(recipe, identities);
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			for (var entry : BeeDesignerMachineRecipes.entries(recipe)) {
				if (entry.kind().equals("item")) {
					JsonObject item = BeeDesignerMachineRecipes.itemIdentity(entry);
					ResourceLocation bee = BeeDesignerEggs.bee(item);
					if (bee != null && identities.containsKey(bee.toString()) && !identities.get(bee.toString()).equals(bee.toString())) {
						BeeDesignerMachineRecipes.replaceMaterial(entry, BeeDesignerMachineRecipes.itemMaterial(BeeDesignerEggs.retarget(item, identities.get(bee.toString()))));
					}
				}
			}
		}
	}

	private static void rewriteArray(JsonObject object, String key, Map<String, String> identities) {
		if (object.has(key) && object.get(key).isJsonArray()) {
			JsonArray values = object.getAsJsonArray(key);
			for (int index = 0; index < values.size(); index++) {
				JsonElement value = values.get(index);
				if (value.isJsonPrimitive() && identities.containsKey(value.getAsString())) {
					values.set(index, new JsonPrimitive(identities.get(value.getAsString())));
				}
			}
		}
	}

	private static void rewriteField(JsonObject object, String key, Map<String, String> identities) {
		if (object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isString()) {
			String mapped = identities.get(object.get(key).getAsString());
			if (mapped != null) {
				object.addProperty(key, mapped);
			}
		}
	}

	private static void rewriteConditions(JsonElement value, Map<String, String> identities) {
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(entry -> rewriteConditions(entry, identities));
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (BeeDesignerDocument.text(object, "type", "").equals("productivebees:bee_exists")) {
				rewriteField(object, "bee", identities);
			}
			object.asMap().values().forEach(entry -> rewriteConditions(entry, identities));
		}
	}

	private static void rewriteComponents(JsonElement value, Map<String, String> identities) {
		if (value.isJsonArray()) {
			value.getAsJsonArray().forEach(entry -> rewriteComponents(entry, identities));
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			rewriteField(object, "productivebees:bee_type", identities);
			if (object.has("minecraft:entity_data") && object.get("minecraft:entity_data").isJsonObject()) {
				rewriteField(object.getAsJsonObject("minecraft:entity_data"), "type", identities);
			}
			object.asMap().values().forEach(entry -> rewriteComponents(entry, identities));
		}
	}

	public static List<String> saveDrafts(Path workspace, Plan plan) throws IOException {
		List<String> completed = new ArrayList<>();
		for (var entry : plan.documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			try {
				JsonObject document = BeeDesignerProtocol.parse(entry.getValue());
				BeeDesignerDrafts.preserveBeforeImport(workspace, document);
				BeeDesignerDrafts.save(workspace, document);
				completed.add(entry.getKey());
			} catch (IOException | RuntimeException failure) {
				throw new IOException("designer.error.migration_save|" + entry.getKey() + "|" + completed.size(), failure);
			}
		}
		return List.copyOf(completed);
	}
}
