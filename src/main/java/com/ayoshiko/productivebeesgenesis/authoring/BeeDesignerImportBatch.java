package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 每个编辑会话一份有界暂存；全部校验通过前没有世界写入。 */
final class BeeDesignerImportBatch {
	final UUID token;
	final long generation;
	private final int expected;
	private final boolean drafts;
	private final Map<String, JsonObject> documents = new LinkedHashMap<>();
	private final Map<String, JsonObject> originals = new LinkedHashMap<>();
	private final Map<String, JsonObject> managedBaselines = new LinkedHashMap<>();
	private final Map<String, String> managedSources = new LinkedHashMap<>();
	private final Map<String, String> requestFingerprints = new LinkedHashMap<>();
	private final Map<String, String> requestBaselines = new LinkedHashMap<>();
	private int bytes;
	private long touched = System.nanoTime();

	BeeDesignerImportBatch(JsonObject request) {
		token = UUID.fromString(request.get("token").getAsString());
		expected = request.get("count").getAsInt();
		drafts = request.has("drafts") && request.get("drafts").getAsBoolean();
		if (expected < 1 || expected > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		generation = BeeDesignerSources.generation;
	}

	void check(JsonObject request) {
		if (!token.toString().equals(BeeDesignerDocument.text(request, "token", ""))
				|| generation != BeeDesignerSources.generation
				|| System.nanoTime() - touched > 120L * 1_000_000_000) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		touched = System.nanoTime();
	}

	void add(JsonObject source, JsonObject original) {
		BeeDesignerCompiler.validateStructure(source);
		String id = source.get("id").getAsString();
		if (drafts) {
			String baseline = BeeDesignerDocument.text(source, "baseline", "new");
			String managed = BeeDesignerSources.managed.get(id);
			boolean matchesManaged = original != null && managed != null
					&& baseline.equals(BeeDesignerDocument.fingerprint(BeeDesignerProtocol.parse(managed)));
			if (!baseline.equals(original == null ? "new" : original.get("baseline").getAsString())) {
				if (!matchesManaged) {
					throw new IllegalArgumentException("designer.error.draft_conflict|" + id);
				}
				managedBaselines.put(id, BeeDesignerProtocol.parse(managed));
			}
		}
		if (BeeDesignerNativeBees.contains(ResourceLocation.parse(id)) || BeeDesignerNativeBees.recipesOnly(source)) {
			BeeDesignerNativeBees.validate(source);
		}
		if (documents.containsKey(id) || documents.size() >= expected) {
			throw new IllegalArgumentException("designer.error.migration_duplicate|" + id);
		}
		JsonObject document = source.deepCopy();
		document.remove("disabled_recipes");
		document.remove("_editor_export_generation");
		if (original != null) {
			if (original.has("_editor_origin") && original.get("_editor_origin").isJsonObject()) {
				document.add("_editor_origin", original.getAsJsonObject("_editor_origin").deepCopy());
			}
			document.add("definition_path", original.get("definition_path").deepCopy());
			document.add("baseline", original.get("baseline").deepCopy());
			if (BeeDesignerMyriad.recipesOnly(document)) {
				document.add("definition", original.get("definition").deepCopy());
				document.add("names", original.get("names").deepCopy());
				BeeDesignerMyriad.validateRecipes(original, document);
			}
			JsonArray disabled = original.has("disabled_recipes") ? original.getAsJsonArray("disabled_recipes").deepCopy() : new JsonArray();
			for (String recipe : original.getAsJsonObject("recipes").keySet()) {
				if (!document.getAsJsonObject("recipes").has(recipe)) {
					disabled.add(recipe);
				}
			}
			document.add("disabled_recipes", disabled);
			originals.put(id, original.deepCopy());
		} else {
			if (BeeDesignerMyriad.recipesOnly(document)) {
				throw new IllegalArgumentException("designer.error.myriad_import");
			}
			JsonObject origin = document.has("_editor_origin") && document.get("_editor_origin").isJsonObject()
					? document.getAsJsonObject("_editor_origin").deepCopy() : new JsonObject();
			String sourcePack = BeeDesignerDocument.text(origin, "source_pack", "");
			if (!sourcePack.isBlank()) {
				origin.addProperty("copied_source_pack", sourcePack);
			}
			origin.addProperty("kind", "editor");
			origin.addProperty("source_pack", "");
			document.add("_editor_origin", origin);
			document.addProperty("baseline", "new");
		}
		bytes += document.toString().getBytes(StandardCharsets.UTF_8).length;
		if (bytes > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IllegalArgumentException("designer.error.export_budget");
		}
		documents.put(id, document);
		managedSources.put(id, BeeDesignerSources.managed.get(id));
		requestFingerprints.put(id, BeeDesignerDocument.fingerprint(source));
		requestBaselines.put(id, BeeDesignerDocument.text(source, "baseline", "new"));
	}

	Map<String, String> validate(MinecraftServer server) {
		if (generation != BeeDesignerSources.generation || documents.size() != expected) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		Set<ResourceLocation> batchBees = documents.keySet().stream().map(ResourceLocation::parse).collect(Collectors.toSet());
		for (var entry : documents.entrySet()) {
			String id = entry.getKey();
			JsonObject document = entry.getValue();
			JsonObject original = originals.get(id);
			if (!Objects.equals(managedSources.get(id), BeeDesignerSources.managed.get(id))) {
				throw new IllegalArgumentException("designer.error.draft_conflict|" + id);
			}
			if (original != null && !BeeDesignerReader.unchanged(server, original)) {
				throw new IllegalArgumentException("designer.error.collection_changed");
			}
			JsonObject baseline = managedBaselines.get(id);
			if (baseline != null && !BeeDesignerReader.unchanged(server, baseline)) {
				throw new IllegalArgumentException("designer.error.draft_conflict|" + id);
			}
			var conflicts = BeeDesignerImportConflicts.inspect(id,
					BeeDesignerEggs.bees(), documents.keySet());
			if (!conflicts.canKeepIdentity()) {
				throw new IllegalArgumentException("designer.import_name_conflict|" + String.join(", ", conflicts.nameCollisions()));
			}
			if (document.has("_editor_recovery")) {
				throw new IllegalArgumentException("designer.error.collection_unfinished");
			}
			BeeDesignerLifecycle.validateTransition(original, document, null);
			if (BeeDesignerLifecycle.deleted(document) && (original == null || !BeeDesignerLifecycle.deleted(original))) {
				throw new IllegalArgumentException("designer.error.delete_confirmation");
			}
			if (BeeDesignerMyriad.recipesOnly(document)) {
				BeeDesignerMyriad.validateRecipes(original, document);
			}
			BeeDesignerLifecycle.validateSharedChanges(original, document, documents);
			for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
				boolean owned = original != null && original.getAsJsonObject("recipes").has(recipe.getKey());
				if (!owned && server.getRecipeManager().byKey(ResourceLocation.parse(recipe.getKey())).isPresent()) {
					throw new IllegalArgumentException("designer.error.migration_recipe_conflict|" + recipe.getKey());
				}
			}
			try {
				BeeDesignerCompiler.validate(document, server, batchBees);
			} catch (RuntimeException invalid) {
				throw new IllegalArgumentException("designer.error.migration_invalid|" + id + "|" + BeeDesignerServer.safeMessage(invalid), invalid);
			}
		}
		Map<String, String> combined = new LinkedHashMap<>(BeeDesignerSources.managed);
		documents.forEach((id, document) -> combined.put(id, document.toString()));
		if (combined.size() > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		BeeDesignerCompiler.files(combined);
		return Map.copyOf(combined);
	}

	String requestDigest() {
		JsonObject request = new JsonObject();
		request.addProperty("count", expected);
		request.addProperty("drafts", drafts);
		JsonObject fingerprints = new JsonObject();
		requestFingerprints.forEach(fingerprints::addProperty);
		request.add("documents", fingerprints);
		JsonObject baselines = new JsonObject();
		requestBaselines.forEach(baselines::addProperty);
		request.add("baselines", baselines);
		return BeeDesignerDocument.fingerprint(request);
	}

	Map<String, String> baselines() {
		return Map.copyOf(requestBaselines);
	}

	JsonObject document(String id) {
		return documents.get(id).deepCopy();
	}

	JsonObject first() {
		return documents.values().iterator().next().deepCopy();
	}

	int size() {
		return documents.size();
	}

	int expected() {
		return expected;
	}
}
