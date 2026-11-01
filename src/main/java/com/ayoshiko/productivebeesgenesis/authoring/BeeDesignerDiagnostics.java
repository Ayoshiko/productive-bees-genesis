package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

/** 检查收集独立位置的错误；应用仍重新执行完整校验，诊断结果不授予发布权限。 */
final class BeeDesignerDiagnostics {
	private BeeDesignerDiagnostics() {
	}

	static JsonObject check(JsonObject document, MinecraftServer server) {
		JsonArray issues = new JsonArray();
		JsonObject base = document.deepCopy();
		base.add("recipes", new JsonObject());
		inspect(issues, "definition", () -> BeeDesignerCompiler.validate(base, server));
		if (issues.isEmpty()) {
			for (var entry : document.getAsJsonObject("recipes").entrySet()) {
				JsonObject one = base.deepCopy();
				one.getAsJsonObject("recipes").add(entry.getKey(), entry.getValue());
				inspect(issues, "recipes/" + entry.getKey(), () -> BeeDesignerCompiler.validate(one, server));
			}
		}
		boolean acquisition = document.getAsJsonObject("recipes").asMap().values().stream()
				.map(value -> value.getAsJsonObject().get("type").getAsString())
				.anyMatch(type -> type.startsWith("minecraft:crafting_") || type.startsWith("productivebees:bee_")
						|| type.startsWith("modern_industrialization:") || type.startsWith("mbd2:")
						|| type.equals("modular_machinery_reborn:machine_recipe"));
		if (!acquisition) {
			issue(issues, "warning", "acquisition", "designer.diagnostic.no_acquisition");
		}
		boolean production = document.getAsJsonObject("recipes").asMap().values().stream()
				.map(value -> value.getAsJsonObject().get("type").getAsString())
				.anyMatch(type -> type.equals("productivebees:advanced_beehive") || type.equals("productivebees:centrifuge")
						|| type.equals("productivebeesgenesis:resource_production") || type.equals("productivebeesgenesis:resource_centrifuging"));
		if (!production) {
			issue(issues, "warning", "production", "designer.diagnostic.no_production");
		}
		JsonArray files = new JsonArray();
		if (issues.asList().stream().noneMatch(value -> value.getAsJsonObject().get("severity").getAsString().equals("error"))) {
			inspect(issues, "files", () -> BeeDesignerCompiler.files(Map.of(document.get("id").getAsString(), document.toString()))
				.forEach((path, content) -> {
					JsonObject file = new JsonObject();
					file.addProperty("path", path);
					file.addProperty("bytes", content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
					files.add(file);
				}));
		}
		JsonObject result = new JsonObject();
		result.addProperty("valid", issues.asList().stream().noneMatch(value -> value.getAsJsonObject().get("severity").getAsString().equals("error")));
		result.addProperty("fingerprint", BeeDesignerDocument.fingerprint(document));
		result.add("issues", issues);
		result.add("files", files);
		return result;
	}

	private static void inspect(JsonArray issues, String path, Runnable check) {
		try {
			check.run();
		} catch (RuntimeException exception) {
			String message = BeeDesignerServer.safeMessage(exception);
			if (path.equals("definition") && message.startsWith("designer.error.flower_entity_tag|")) {
				path += "/flowerTag";
			}
			if ((path.equals("definition") || path.startsWith("recipes/")) && message.startsWith("designer.error.field_")) {
				String[] parts = message.split("\\|", -1);
				if (parts.length > 1) {
					path += "/" + parts[1];
				}
			}
			issue(issues, "error", path, message);
		}
	}

	private static void issue(JsonArray issues, String severity, String path, String message) {
		JsonObject issue = new JsonObject();
		issue.addProperty("severity", severity);
		issue.addProperty("path", path);
		issue.addProperty("message", message);
		issues.add(issue);
	}
}
