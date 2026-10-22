package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCompiler;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.function.Consumer;

/** 检查、文件预览与发布状态共享同一份声明，陈旧诊断不会显示为当前检查通过。 */
final class DesignerReviewPage {
	private DesignerReviewPage() {
	}

	static void add(DesignerFormScreen screen, JsonObject document, JsonObject diagnostics, JsonObject publication,
			boolean canPublish, boolean publishing, Consumer<String> action, Consumer<String> locate) {
		Minecraft mc = Minecraft.getInstance();
		screen.section("review_summary");
		screen.note("id", Component.literal(document.get("id").getAsString()));
		screen.note("recipe_count", Component.literal(Integer.toString(document.getAsJsonObject("recipes").size())));
		screen.note("edit_mode", DesignerFormScreen.tr(BeeDesignerDocument.text(document, "baseline", "new").equals("new")
				? "new_identity" : "override_identity"));
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("check_generate"),
				() -> action.accept("check")).active = !publishing));
		if (diagnostics != null) {
			boolean current = BeeDesignerDocument.fingerprint(document).equals(diagnostics.get("fingerprint").getAsString());
			screen.note("check_result", DesignerFormScreen.tr(!current ? "check_stale"
					: diagnostics.get("valid").getAsBoolean() ? "status.valid" : "check_failed"));
			for (var value : diagnostics.getAsJsonArray("issues")) {
				JsonObject issue = value.getAsJsonObject();
				String path = issue.get("path").getAsString();
				String message = issue.get("message").getAsString();
				Component text = DesignerFormScreen.statusMessage(message);
				Component location = DesignerFieldNavigation.breadcrumb(document, path);
				screen.rows.add(new DesignerFormScreen.Row(issue.get("severity").getAsString(), (x, y, w) ->
						screen.button(x, y, w, text.copy().append("  ·  ").append(location), () -> locate.accept(path))
							.setTooltip(net.minecraft.client.gui.components.Tooltip.create(text.copy().append("\n").append(location).append("\n" + path)))));
			}
		}
		screen.section("generated_files");
		try {
			JsonObject source = document.deepCopy();
			if (!source.has("definition_path")) {
				source.add("definition_path", source.get("id"));
			}
			BeeDesignerCompiler.files(Map.of(source.get("id").getAsString(), source.toString())).entrySet().stream()
					.sorted(Map.Entry.comparingByKey()).forEach(entry -> screen.rows.add(new DesignerFormScreen.Row("file", (x, y, w) ->
						screen.button(x, y, w, Component.literal(entry.getKey()), () -> mc.setScreen(
								new DesignerJsonScreen(screen, JsonParser.parseString(entry.getValue()).getAsJsonObject(), entry.getKey(), true))))));
		} catch (RuntimeException exception) {
			screen.note("error", DesignerFormScreen.statusMessage(exception.getMessage() == null ? "designer.error.invalid_project" : exception.getMessage()));
		}
		screen.section("publish_section");
		if (canPublish && screen instanceof BeeDesignerScreen editor) {
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("history"),
					() -> mc.setScreen(new DesignerHistoryScreen(editor)))));
		}
		if (screen instanceof BeeDesignerScreen editor) {
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("runtime_data"), editor::runtimeData)));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("local_export"), editor::exportLocal)));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("kubejs_export"), () -> DesignerProjectFiles.exportKubeJs(document,
							path -> screen.status = Component.translatable("designer.status.exported", path.toString()).getString(),
							message -> screen.status = message))));
			screen.note("", DesignerFormScreen.tr("kubejs_export_help"));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("open_exports_folder"), () -> DesignerProjectFiles.openFolder(true, message -> screen.status = message))));
		}
		if (publication != null) {
			screen.note("publish_phase", DesignerFormScreen.tr("phase." + BeeDesignerDocument.text(publication, "phase", "idle")));
			if (publication.has("operation") && !publication.get("operation").getAsString().isEmpty()) {
				screen.note("operation", Component.literal(publication.get("operation").getAsString()));
			}
			if (publication.get("uncertain").getAsBoolean()) {
				screen.note("error", DesignerFormScreen.tr("unknown_publish_help"));
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, w,
						DesignerFormScreen.tr("reconcile"), () -> action.accept("reconcile")).active = canPublish && !publishing));
			}
		}
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("export"),
				() -> action.accept("export")).active = !publishing && canPublish));
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, w, DesignerFormScreen.tr("apply"),
				() -> action.accept("confirm_apply")).active = !publishing && canPublish
					&& (publication == null || !publication.get("uncertain").getAsBoolean())));
		if (!canPublish) {
			screen.note("permission", DesignerFormScreen.tr("error.permission"));
		}
		screen.note("export_help", DesignerFormScreen.tr("install_help"));
		screen.rows.add(new DesignerFormScreen.Row("advanced", (x, y, w) -> screen.button(x, y, w,
				DesignerFormScreen.tr("edit_project"), () -> mc.setScreen(new DesignerJsonScreen(screen, document, "Bee Designer project")))));
	}
}
