package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.component.CustomData;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** 旧身份随定义发布；解析和正常保存迁移身份，不重新生成任何生产资产。 */
public final class BeeDesignerIdentity {
	public static final String ALIASES = "identity_aliases";
	public static final String MIGRATION = "identity_migration";
	public static final String TARGET = "pbg_migrated_to";
	private static volatile Map<ResourceLocation, CompoundTag> source;
	private static volatile Map<String, String> aliases = Map.of();
	private static volatile Map<String, String> invalid = Map.of();
	private static long generation;

	private BeeDesignerIdentity() {
	}

	private static Map<String, String> index() {
		Map<ResourceLocation, CompoundTag> current = BeeReloadListener.INSTANCE.getData();
		if (source == current) {
			return aliases;
		}
		return rebuild(current);
	}

	private static synchronized Map<String, String> rebuild(Map<ResourceLocation, CompoundTag> current) {
		if (source == current) {
			return aliases;
		}
		Map<String, String> next = new HashMap<>();
		current.forEach((id, data) -> {
			if (data.contains(TARGET, Tag.TAG_STRING)) {
				next.put(id.toString(), data.getString(TARGET));
			}
		});
		Map<String, String> resolved = new HashMap<>();
		Map<String, String> failures = new HashMap<>();
		for (String id : next.keySet()) {
			if (resolved.containsKey(id) || failures.containsKey(id)) {
				continue;
			}
			Set<String> path = new LinkedHashSet<>();
			String target = id;
			String failure = null;
			while (next.containsKey(target)) {
				if (resolved.containsKey(target)) {
					target = resolved.get(target);
					break;
				}
				if (failures.containsKey(target)) {
					failure = failures.get(target);
					break;
				}
				if (!path.add(target)) {
					failure = "Cyclic bee identity migration: " + target;
					break;
				}
				target = next.get(target);
			}
			ResourceLocation parsed = ResourceLocation.tryParse(target);
			if (failure == null && (parsed == null || !current.containsKey(parsed))) {
				failure = "Missing bee identity migration target: " + target;
			}
			for (String entry : path) {
				if (failure == null) {
					resolved.put(entry, target);
				} else {
					failures.put(entry, failure);
				}
			}
		}
		failures.forEach((id, failure) -> ProductiveBeesGenesis.LOGGER.error("蜂身份迁移无效 {}：{}", id, failure));
		aliases = Map.copyOf(resolved);
		invalid = Map.copyOf(failures);
		generation++;
		source = current;
		return aliases;
	}

	public static String resolve(String id) {
		Map<String, String> current = index();
		String failure = invalid.get(id);
		if (failure != null) {
			throw new IllegalStateException(failure);
		}
		return current.getOrDefault(id, id);
	}

	public static ResourceLocation resolve(ResourceLocation id) {
		if (id == null) {
			return null;
		}
		String resolved = resolve(id.toString());
		return resolved.equals(id.toString()) ? id : ResourceLocation.parse(resolved);
	}

	public static String failure(String id) {
		index();
		return invalid.get(id);
	}

	/** 只迁移已确认映射；失效目标的原身份继续留在资产与存档中，生产资格仍由 resolve 拒绝。 */
	public static String migrate(String id) {
		return index().getOrDefault(id, id);
	}

	public static ResourceLocation migrate(ResourceLocation id) {
		if (id == null) {
			return null;
		}
		String migrated = migrate(id.toString());
		return migrated.equals(id.toString()) ? id : ResourceLocation.parse(migrated);
	}

	public static boolean isAlias(ResourceLocation id) {
		if (id == null) {
			return false;
		}
		return index().containsKey(id.toString()) || invalid.containsKey(id.toString());
	}

	public static boolean hasMigrations() {
		return !index().isEmpty() || !invalid.isEmpty();
	}

	public static long generation() {
		index();
		return generation;
	}

	public static void migrateBeeData(CompoundTag data) {
		if (data.contains("type", Tag.TAG_STRING)) {
			String previous = data.getString("type");
			String next = migrate(previous);
			if (!previous.equals(next)) {
				data.putString("type", next);
			}
		}
	}

	public static CustomData migrateCustomData(CustomData value) {
		if (!hasMigrations()) {
			return value;
		}
		CompoundTag data = value.copyTag();
		return migrateTags(data) ? CustomData.of(data) : value;
	}

	private static boolean migrateTags(Tag value) {
		boolean changed = false;
		if (value instanceof CompoundTag data) {
			if (data.getString("id").equals("productivebees:configurable_bee")
					|| data.getString("entity").equals("productivebees:configurable_bee")) {
				String previous = data.getString("type");
				String next = migrate(previous);
				if (!previous.equals(next)) {
					data.putString("type", next);
					changed = true;
				}
			}
			for (String key : Set.copyOf(data.getAllKeys())) {
				changed |= migrateTags(data.get(key));
			}
		} else if (value instanceof ListTag list) {
			for (Tag child : list) {
				changed |= migrateTags(child);
			}
		}
		return changed;
	}

	public static JsonObject rename(JsonObject document, String next) {
		ResourceLocation id = ResourceLocation.parse(next);
		String previous = document.get("id").getAsString();
		if (previous.equals(next) || !id.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IllegalArgumentException("designer.error.id");
		}
		if (BeeDesignerNativeBees.recipesOnly(document) || BeeDesignerMyriad.recipesOnly(document)
				|| BeeDesignerLifecycle.deleted(document)) {
			throw new IllegalArgumentException("designer.error.identity_scope");
		}
		JsonObject renamed = document.deepCopy();
		retargetRecipes(renamed, Map.of(previous, next));
		BeeDesignerDocument.rewriteSelfConditions(renamed.get("definition"), previous, next);
		renamed.addProperty("id", next);
		renamed.addProperty("definition_path", next);
		String baseline = BeeDesignerDocument.text(document, "baseline", "new");
		if (!baseline.equals("new")) {
			JsonObject migration = document.has(MIGRATION) ? document.getAsJsonObject(MIGRATION).deepCopy() : new JsonObject();
			if (!migration.has("from")) {
				migration.addProperty("from", previous);
				migration.addProperty("baseline", baseline);
				migration.addProperty("definition_path", document.get("definition_path").getAsString());
			}
			renamed.add(MIGRATION, migration);
			renamed.addProperty("baseline", baseline);
			JsonObject compatibility = BeeDesignerDocument.object(renamed, ALIASES);
			compatibility.addProperty(migration.get("definition_path").getAsString(), migration.get("from").getAsString());
		}
		return renamed;
	}

	static void validateAliases(JsonObject document) {
		if (document.getAsJsonObject("definition").has(TARGET)) {
			throw new IllegalArgumentException("designer.error.identity_scope");
		}
		if (!document.has(ALIASES)) {
			return;
		}
		JsonObject compatibility = document.getAsJsonObject(ALIASES);
		if (compatibility.size() > 128 || BeeDesignerNativeBees.recipesOnly(document) || BeeDesignerMyriad.recipesOnly(document)) {
			throw new IllegalArgumentException("designer.error.identity_scope");
		}
		for (var entry : compatibility.entrySet()) {
			ResourceLocation path = ResourceLocation.parse(entry.getKey());
			ResourceLocation id = ResourceLocation.parse(entry.getValue().getAsString());
			if (!path.getNamespace().equals(id.getNamespace()) || path.getPath().contains("..")
					|| !path.getPath().substring(path.getPath().lastIndexOf('/') + 1).equals(id.getPath())
					|| id.toString().equals(document.get("id").getAsString())) {
				throw new IllegalArgumentException("designer.error.identity_scope");
			}
		}
	}

	static String sourceId(JsonObject document) {
		return document.has(MIGRATION) ? document.getAsJsonObject(MIGRATION).get("from").getAsString()
				: document.get("id").getAsString();
	}

	private static Map<String, String> migrationTargets(Map<String, JsonObject> documents) {
		Map<String, String> targets = new LinkedHashMap<>();
		for (var entry : documents.entrySet()) {
			if (entry.getValue().has(MIGRATION)) {
				String source = sourceId(entry.getValue());
				if (targets.putIfAbsent(source, entry.getKey()) != null || documents.containsKey(source)) {
					throw new IllegalArgumentException("designer.error.migration_duplicate|" + source);
				}
			}
		}
		return Map.copyOf(targets);
	}

	private static void retargetRecipes(JsonObject document, Map<String, String> targets) {
		for (var target : targets.entrySet()) {
			for (var value : document.getAsJsonObject("recipes").asMap().values()) {
				JsonObject recipe = value.getAsJsonObject();
				if (BeeDesignerMachineRecipes.host(recipe) != null) {
					BeeDesignerMachineRecipes.retargetOutputs(recipe, target.getKey(), target.getValue());
				}
			}
			retarget(document.get("recipes"), target.getKey(), target.getValue());
		}
	}

	private static Set<String> sharedMigrationOwners(Map<String, JsonObject> documents, Map<String, JsonObject> originals) {
		Map<String, String> targets = migrationTargets(documents);
		Set<String> owners = new HashSet<>();
		if (targets.isEmpty()) {
			return Set.of();
		}
		for (var original : originals.values()) {
			for (var entry : original.getAsJsonObject("recipes").entrySet()) {
				JsonObject before = entry.getValue().getAsJsonObject();
				Set<String> shared = BeeDesignerLifecycle.recipeOwners(before);
				if (shared.size() <= 1) {
					continue;
				}
				JsonObject after = before.deepCopy();
				for (var target : targets.entrySet()) {
					retarget(after, target.getKey(), target.getValue());
				}
				if (!before.equals(after)) {
					for (String owner : shared) {
						String replacement = targets.getOrDefault(owner, owner);
						if (!documents.containsKey(replacement)) {
							owners.add(owner);
							if (owners.size() + documents.size() > BeeDesignerCollection.MAX_BEES) {
								throw new IllegalArgumentException("designer.error.collection_count");
							}
						}
					}
				}
			}
		}
		return Set.copyOf(owners);
	}

	/** 服务器只取封闭资源引用；源码读取由已有 IO 队列执行。 */
	static Map<String, BeeDesignerReader.Snapshot> sharedOwnerSnapshots(MinecraftServer server,
			Map<String, JsonObject> documents, Map<String, JsonObject> originals) {
		Map<String, BeeDesignerReader.Snapshot> snapshots = new LinkedHashMap<>();
		for (String owner : sharedMigrationOwners(documents, originals)) {
			snapshots.put(owner, BeeDesignerReader.snapshot(server, ResourceLocation.parse(owner)));
		}
		return Map.copyOf(snapshots);
	}

	static Map<String, JsonObject> readSharedOwners(Map<String, BeeDesignerReader.Snapshot> snapshots) {
		Map<String, JsonObject> documents = new LinkedHashMap<>();
		for (var entry : snapshots.entrySet()) {
			documents.put(entry.getKey(), BeeDesignerReader.readSources(entry.getValue()));
		}
		return Map.copyOf(documents);
	}

	/** 同一映射同时替换所有受管引用与实际共享所有者；不传播一方额外编辑的共享正文。 */
	static Map<String, String> coordinateMigration(MinecraftServer server, Map<String, JsonObject> submitted,
			Map<String, JsonObject> originals, Map<String, JsonObject> related) {
		Map<String, String> targets = migrationTargets(submitted);
		if (!related.keySet().equals(sharedMigrationOwners(submitted, originals))) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		for (var entry : submitted.entrySet()) {
			prepareMigration(originals.get(entry.getKey()), entry.getValue());
		}
		Map<String, JsonObject> replacements = new LinkedHashMap<>();
		submitted.forEach((id, document) -> replacements.put(id, document.deepCopy()));
		for (var entry : related.entrySet()) {
			if (!BeeDesignerReader.unchanged(server, entry.getValue())) {
				throw new IllegalArgumentException("designer.error.draft_conflict|" + entry.getKey());
			}
			replacements.put(entry.getKey(), entry.getValue().deepCopy());
		}
		replacements.values().forEach(document -> retargetRecipes(document, targets));
		Map<String, JsonObject> sharedOriginals = new LinkedHashMap<>(originals);
		sharedOriginals.putAll(related);
		for (var entry : replacements.entrySet()) {
			JsonObject original = sharedOriginals.get(entry.getKey());
			if (original != null) {
				original = original.deepCopy();
				retargetRecipes(original, targets);
			}
			BeeDesignerLifecycle.validateSharedChanges(original, entry.getValue(), replacements);
		}
		Set<ResourceLocation> declared = replacements.keySet().stream().map(ResourceLocation::parse)
				.collect(java.util.stream.Collectors.toSet());
		for (var entry : related.entrySet()) {
			BeeDesignerCompiler.validate(replacements.get(entry.getKey()), server, declared);
		}
		Map<String, String> combined = new LinkedHashMap<>(BeeDesignerSources.managed);
		for (String source : targets.keySet()) {
			combined.remove(source);
		}
		if (!targets.isEmpty()) {
			for (var entry : combined.entrySet()) {
				JsonObject document = BeeDesignerProtocol.parse(entry.getValue());
				retargetRecipes(document, targets);
				entry.setValue(document.toString());
			}
		}
		for (var entry : replacements.entrySet()) {
			entry.getValue().remove(MIGRATION);
			combined.put(entry.getKey(), entry.getValue().toString());
		}
		if (combined.size() > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		return Map.copyOf(combined);
	}

	static String prepareMigration(JsonObject original, JsonObject document) {
		if (!document.has(MIGRATION)) {
			if (original != null && original.get("id").equals(document.get("id"))
					&& !java.util.Objects.equals(original.get(ALIASES), document.get(ALIASES))) {
				throw new IllegalArgumentException("designer.error.identity_scope");
			}
			return document.get("id").getAsString();
		}
		JsonObject migration = document.getAsJsonObject(MIGRATION);
		String previous = migration.get("from").getAsString();
		if (original == null || !previous.equals(original.get("id").getAsString())
				|| !migration.get("baseline").equals(original.get("baseline"))
				|| !migration.get("definition_path").equals(original.get("definition_path"))
				|| BeeDesignerNativeBees.recipesOnly(original) || BeeDesignerMyriad.recipesOnly(original)
				|| BeeDesignerLifecycle.deleted(original)) {
			throw new IllegalArgumentException("designer.error.conflict");
		}
		JsonObject expected = original.has(ALIASES) ? original.getAsJsonObject(ALIASES).deepCopy() : new JsonObject();
		expected.addProperty(original.get("definition_path").getAsString(), previous);
		if (!expected.equals(document.get(ALIASES))) {
			throw new IllegalArgumentException("designer.error.identity_scope");
		}
		return previous;
	}

	static void retarget(JsonElement value, String previous, String next) {
		if (value.isJsonArray()) {
			JsonArray array = value.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				JsonElement child = array.get(i);
				retarget(child, previous, next);
			}
		} else if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			for (var entry : Set.copyOf(object.entrySet())) {
				String field = entry.getKey();
				JsonElement child = entry.getValue();
				if (child.isJsonArray() && (field.equals("productivebees:bee_type_list")
						|| field.equals("results") && BeeDesignerDocument.text(object, "type", "").equals("productivebees:bee_spawning"))) {
					JsonArray list = child.getAsJsonArray();
					for (int index = 0; index < list.size(); index++) {
						JsonElement item = list.get(index);
						if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() && previous.equals(item.getAsString())) {
							list.set(index, new com.google.gson.JsonPrimitive(next));
						}
					}
				} else if (Set.of("bee", "parent1", "parent2", "offspring", "source", "result", "ingredient",
						"productivebees:bee_type").contains(field)
						&& child.isJsonPrimitive() && child.getAsJsonPrimitive().isString() && previous.equals(child.getAsString())) {
					object.addProperty(field, next);
				} else if (field.equals("minecraft:entity_data") && child.isJsonObject()) {
					JsonObject entity = child.getAsJsonObject();
					if (previous.equals(BeeDesignerDocument.text(entity, "type", ""))) {
						entity.addProperty("type", next);
					}
					retarget(child, previous, next);
				} else if (!field.equals(ALIASES) && !field.equals(MIGRATION) && !field.equals("_editor_origin")) {
					retarget(child, previous, next);
				}
			}
		}
	}
}
