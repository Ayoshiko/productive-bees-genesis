package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 删除保留蜂种身份及原始配方；只阻止新周期，旧产物和已付工作仍由原所有者结清。 */
public final class BeeDesignerLifecycle {

	public static final String FIELD = "_editor_lifecycle";
	private static final Set<String> SETTLEMENT_RECIPES = Set.of("productivebees:advanced_beehive",
			"productivebees:centrifuge", "productivebeesgenesis:resource_production",
			"productivebeesgenesis:resource_centrifuging");

	private BeeDesignerLifecycle() {
	}

	public static boolean deleted(JsonObject document) {
		return document.has(FIELD) && document.get(FIELD).isJsonObject()
				&& BeeDesignerDocument.text(document.getAsJsonObject(FIELD), "state", "").equals("deleted");
	}

	public static boolean deleted(ResourceLocation bee) {
		return bee != null && BeeDesignerSources.deleted.contains(bee);
	}

	public static JsonObject retire(JsonObject original, long time, UUID player) {
		if (deleted(original)) {
			throw new IllegalArgumentException("designer.error.already_deleted");
		}
		JsonObject document = original.deepCopy();
		JsonObject lifecycle = new JsonObject();
		lifecycle.addProperty("state", "deleted");
		lifecycle.addProperty("deleted_at", time);
		lifecycle.addProperty("deleted_by", player.toString());
		document.add(FIELD, lifecycle);
		return document;
	}

	public static JsonObject restore(JsonObject retired) {
		JsonObject restored = retired.deepCopy();
		restored.remove(FIELD);
		return restored;
	}

	public static void validate(JsonObject document) {
		if (!document.has(FIELD)) {
			return;
		}
		if (!deleted(document)) {
			throw new IllegalArgumentException("designer.error.lifecycle_data");
		}
		JsonObject lifecycle = document.getAsJsonObject(FIELD);
		try {
			if (lifecycle.get("deleted_at").getAsBigDecimal().longValueExact() < 0) {
				throw new IllegalArgumentException("designer.error.lifecycle_data");
			}
			UUID.fromString(lifecycle.get("deleted_by").getAsString());
		} catch (RuntimeException failure) {
			throw new IllegalArgumentException("designer.error.lifecycle_data", failure);
		}
	}

	/** 返回实际发布内容；多蜂巢穴配方仅移除被删除的蜂种，保留其它结果。 */
	public static JsonObject activeRecipe(JsonObject document, JsonObject recipe) {
        return activeRecipe(document, recipe, deleted(document) ? Set.of(document.get("id").getAsString()) : Set.of());
    }

    static Set<String> retiredIds(Map<String, String> documents) {
        Set<String> retired = new HashSet<>();
        for (var entry : documents.entrySet()) {
            JsonObject document = JsonParser.parseString(entry.getValue()).getAsJsonObject();
            if (deleted(document)) {
                retired.add(entry.getKey());
            }
        }
        return Set.copyOf(retired);
    }

    static JsonObject activeRecipe(JsonObject document, JsonObject recipe, Set<String> retired) {
        if (SETTLEMENT_RECIPES.contains(BeeDesignerDocument.text(recipe, "type", ""))) {
			return recipe;
		}
		if (BeeDesignerDocument.text(recipe, "type", "").equals("productivebees:bee_spawning")) {
			JsonObject remaining = recipe.deepCopy();
			JsonArray results = new JsonArray();
			for (var result : recipe.getAsJsonArray("results")) {
				if (!retired.contains(result.getAsString())) {
					results.add(result.deepCopy());
				}
			}
			if (!results.isEmpty()) {
				remaining.add("results", results);
				return remaining;
			}
            return null;
		}
        return deleted(document) ? null : recipe;
	}

	public static int retiredRecipes(JsonObject document) {
		JsonObject retired = deleted(document) ? document : retire(document, 0, new UUID(0, 0));
		return (int) retired.getAsJsonObject("recipes").asMap().values().stream()
				.filter(recipe -> activeRecipe(retired, recipe.getAsJsonObject()) == null).count();
	}

	/** 确认顺序及身份由服务器会话拥有；过期、重放和内容变化都需要重新确认。 */
	static final class Confirmation {
		private static final long LIFETIME_NANOS = 10L * 60 * 1_000_000_000;
		private final UUID token = UUID.randomUUID();
		private final JsonObject original;
		private final long generation;
		private final long created = System.nanoTime();
		private int step;

		Confirmation(JsonObject original, long generation) {
			this.original = original.deepCopy();
			this.generation = generation;
		}

		JsonObject review() {
			JsonObject review = new JsonObject();
			review.addProperty("id", original.get("id").getAsString());
			review.addProperty("token", token.toString());
			review.addProperty("step", step);
			review.addProperty("retired_recipes", retiredRecipes(original));
			review.addProperty("total_recipes", original.getAsJsonObject("recipes").size());
			return review;
		}

		void confirm(JsonObject request) {
			if (System.nanoTime() - created > LIFETIME_NANOS
					|| !token.toString().equals(BeeDesignerDocument.text(request, "token", ""))
					|| !original.get("id").getAsString().equals(BeeDesignerDocument.text(request, "id", ""))
					|| request.get("step").getAsBigDecimal().intValueExact() != step + 1 || step >= 3
					|| step == 2 && !original.get("id").getAsString().equals(BeeDesignerDocument.text(request, "phrase", ""))) {
				throw new IllegalArgumentException("designer.error.delete_confirmation");
			}
			step++;
		}

		boolean ready() {
			return step == 3;
		}

		boolean permits(JsonObject document) {
			return ready() && System.nanoTime() - created <= LIFETIME_NANOS
					&& generation == BeeDesignerSources.generation
					&& original.get("id").equals(document.get("id"))
					&& original.get("baseline").equals(document.get("baseline"))
					&& BeeDesignerDocument.fingerprint(restore(original)).equals(BeeDesignerDocument.fingerprint(restore(document)));
		}
	}
}
