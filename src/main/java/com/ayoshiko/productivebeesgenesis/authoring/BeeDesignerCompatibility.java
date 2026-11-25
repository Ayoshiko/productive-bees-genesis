package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只说明当前声明的运行依赖；蜂种／配方身份和编辑元数据不是模组资源引用。 */
public final class BeeDesignerCompatibility {
	public record Requirement(String bee, String reason, String detail) {}

	public record Report(List<Requirement> requirements) {
		public Report {
			requirements = List.copyOf(requirements);
		}

		public boolean requiresGenesis() {
			return !requirements.isEmpty();
		}
	}

	private static final Set<String> REFERENCES = Set.of("id", "item", "items", "block", "fluid", "fluids", "entity", "type",
			"renderer", "model", "texture", "textures", "flowerItem", "flowerBlock", "flowerTag", "breedingItem");

	private BeeDesignerCompatibility() {
	}

	public static Report describe(Map<String, String> documents) {
		Set<String> identities = new HashSet<>(documents.keySet());
		List<JsonObject> parsed = new ArrayList<>();
		for (String raw : documents.values()) {
			JsonObject document = JsonParser.parseString(raw).getAsJsonObject();
			parsed.add(document);
			identities.add(BeeDesignerDocument.text(document, "definition_path", document.get("id").getAsString()));
			identities.addAll(document.getAsJsonObject("recipes").keySet());
			if (document.has(BeeDesignerIdentity.ALIASES)) {
				identities.addAll(document.getAsJsonObject(BeeDesignerIdentity.ALIASES).keySet());
			}
		}
		List<Requirement> requirements = new ArrayList<>();
		for (JsonObject document : parsed) {
			Map<String, String> reasons = new LinkedHashMap<>();
			JsonObject definition = document.getAsJsonObject("definition");
			if (definition.has("flowerResource") || !BeeDesignerDocument.text(definition, "flowerFluid", "").isBlank()) {
				reasons.put("container", "definition/" + (definition.has("flowerResource") ? "flowerResource" : "flowerFluid"));
			}
			if (document.has(BeeDesignerIdentity.ALIASES) && !document.getAsJsonObject(BeeDesignerIdentity.ALIASES).isEmpty()) {
				reasons.put("migration", "identity_aliases");
			}
			if (BeeDesignerLifecycle.deleted(document)) {
				reasons.put("retired", BeeDesignerLifecycle.FIELD);
			}
			String bee = document.get("id").getAsString();
			if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE_STRING) || BeeDesignerMyriad.recipesOnly(document)) {
				reasons.put("myriad", bee);
			}
			references(definition, "definition", "", identities, reasons);
			for (var entry : document.getAsJsonObject("recipes").entrySet()) {
				JsonObject recipe = entry.getValue().getAsJsonObject();
				if (BeeDesignerDocument.text(recipe, "type", "").startsWith("productivebeesgenesis:")) {
					reasons.putIfAbsent("resources", entry.getKey());
				}
				references(recipe, "recipes/" + entry.getKey(), "", identities, reasons);
			}
			reasons.forEach((reason, detail) -> requirements.add(new Requirement(bee, reason, detail)));
		}
		return new Report(requirements);
	}

	private static void references(JsonElement value, String path, String field, Set<String> identities, Map<String, String> reasons) {
		if (value.isJsonObject()) {
			for (var entry : value.getAsJsonObject().entrySet()) {
				String key = entry.getKey();
				if (key.startsWith("_editor_") || key.equals("productivebeesgenesis:designer_recipe")) {
					continue;
				}
				if (key.equals("type") && path.startsWith("recipes/") && reasons.containsKey("resources")
						&& entry.getValue().isJsonPrimitive() && entry.getValue().getAsString().startsWith("productivebeesgenesis:")) {
					continue;
				}
				if (key.startsWith("productivebeesgenesis:")) {
					reasons.putIfAbsent("reference", path + "/" + key);
				}
				references(entry.getValue(), path + "/" + key, key, identities, reasons);
			}
		} else if (value.isJsonArray()) {
			for (JsonElement child : value.getAsJsonArray()) {
				references(child, path, field, identities, reasons);
			}
		} else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() && REFERENCES.contains(field)) {
			String text = value.getAsString();
			String resource = text.startsWith("#") ? text.substring(1) : text;
			ResourceLocation id = ResourceLocation.tryParse(resource);
			boolean identity = identities.contains(resource) && (path.endsWith("/minecraft:entity_data/type")
					|| path.equals("recipes/" + resource + "/id"));
			if (id != null && id.getNamespace().equals("productivebeesgenesis") && !identity) {
				reasons.putIfAbsent("reference", path + " = " + text);
			}
		}
	}
}
