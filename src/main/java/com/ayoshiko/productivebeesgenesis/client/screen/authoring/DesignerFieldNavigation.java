package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.Set;

/** 诊断和总览共享精确字段路径；配方 ID 本身允许包含斜杠。 */
final class DesignerFieldNavigation {
	private static final Set<String> APPEARANCE = Set.of("primaryColor", "secondaryColor", "particleColor", "glowColor",
			"size", "createComb", "description", "beeTexture", "renderer", "model", "animation", "renderTransform", "translucent", "conditions");
	private static final Set<String> ADVANCED = Set.of("description", "beeTexture", "animation", "renderTransform", "translucent",
			"conditions", "selfheal", "irradiated", "withered", "blinding", "draconic", "slimy", "teleporting", "munchies",
			"redstoned", "stringy", "stingless", "nestingPreference", "flowerType", "postPollination", "particleType");

	private DesignerFieldNavigation() {
	}

	static int tab(JsonObject document, String path) {
		if (path.startsWith("recipes/")) {
			String id = recipeId(document, path);
			return id != null && production(document.getAsJsonObject("recipes").getAsJsonObject(id)) ? 4 : 3;
		}
		if (path.startsWith("production_nodes/") || path.equals("production")) {
			return 4;
		}
		if (path.equals("acquisition")) {
			return 3;
		}
		if (path.equals("id") || path.startsWith("names/") || path.equals("definition") || APPEARANCE.contains(field(path))) {
			return 1;
		}
		return 2;
	}

	static boolean advanced(String path) {
		return ADVANCED.contains(field(path));
	}

	static String recipeId(JsonObject document, String path) {
		if (!path.startsWith("recipes/")) {
			return null;
		}
		String suffix = path.substring(8);
		String match = null;
		for (String id : document.getAsJsonObject("recipes").keySet()) {
			if ((suffix.equals(id) || suffix.startsWith(id + "/")) && (match == null || id.length() > match.length())) {
				match = id;
			}
		}
		return match;
	}

	static String field(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	static boolean production(JsonObject recipe) {
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			return false;
		}
		String type = BeeDesignerDocument.text(recipe, "type", "");
		return type.equals("productivebees:advanced_beehive") || type.equals("productivebees:centrifuge")
				|| type.equals("productivebeesgenesis:resource_production") || type.equals("productivebeesgenesis:resource_centrifuging");
	}

	static Component breadcrumb(JsonObject document, String path) {
		int page = tab(document, path);
		Component result = DesignerFormScreen.tr(switch (page) {
			case 1 -> "basics";
			case 3 -> "acquisition";
			case 4 -> "production";
			default -> "attributes";
		});
		String id = recipeId(document, path);
		if (id != null) {
			result = result.copy().append(" › ").append(DesignerFormScreen.option(
					document.getAsJsonObject("recipes").getAsJsonObject(id).get("type").getAsString()));
		}
		String field = field(path);
		if (!field.isEmpty() && !path.equals("definition") && !path.equals("acquisition") && !path.equals("production")
				&& (id == null || !path.equals("recipes/" + id))) {
			result = result.copy().append(" › ").append(DesignerFormScreen.option(field));
		}
		return result;
	}
}
