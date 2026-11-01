package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCompiler;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.function.Consumer;

/** 检查状态对应当前声明；生成预览只缓存这一份工程，变更时失效。 */
final class DesignerReviewPage {
	private JsonObject generatedSource;
	private Map<String, String> generated = Map.of();
	private String generatedProblem = "";

	void add(DesignerFormScreen screen, JsonObject document, JsonObject diagnostics, JsonObject publication,
			boolean canPublish, boolean publishing, Consumer<String> action, Consumer<String> locate) {
		Minecraft mc = Minecraft.getInstance();
		boolean current = diagnostics != null && BeeDesignerDocument.fingerprint(document).equals(diagnostics.get("fingerprint").getAsString());
		boolean valid = current && diagnostics.get("valid").getAsBoolean();
		boolean uncertain = publication != null && publication.get("uncertain").getAsBoolean();
		screen.section("review_check_step");
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.addRenderableWidget(new DesignerBeeButton(
				x, y, w, document.get("id").getAsString(), DesignerCollectionScreen.localizedName(document), false, () -> locate.accept("definition")))));
		String result = "check_not_run";
		if (diagnostics != null) {
			result = "check_stale";
			if (current) {
				result = valid ? "status.valid" : "check_failed";
			}
		}
		screen.note("check_result", DesignerFormScreen.tr(result));
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, Math.min(230, w),
				DesignerFormScreen.tr("check_generate"), () -> action.accept("check")).active = !publishing));
		if (diagnostics != null) {
			for (var value : diagnostics.getAsJsonArray("issues")) {
				JsonObject issue = value.getAsJsonObject();
				String path = issue.get("path").getAsString();
				Component text = DesignerFormScreen.statusMessage(issue.get("message").getAsString());
				Component location = DesignerFieldNavigation.breadcrumb(document, path);
				screen.rows.add(new DesignerFormScreen.Row(issue.get("severity").getAsString(), (x, y, w) ->
						screen.addRenderableWidget(new DesignerTheme.Action(x, y, w, text.copy().append("  ·  ").append(location),
								() -> locate.accept(path))).setTooltip(net.minecraft.client.gui.components.Tooltip.create(
										text.copy().append("\n").append(location).append("\n" + path))), 24, true));
			}
		}
		screen.section("review_deliver_step");
		screen.note("", DesignerFormScreen.tr("review_delivery_help"));
		if (publication != null && (publishing || uncertain)) {
			screen.note("publish_phase", DesignerFormScreen.tr("phase." + BeeDesignerDocument.text(publication, "phase", "idle")));
		}
		if (uncertain) {
			screen.note("error", DesignerFormScreen.tr("unknown_publish_help"));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.primaryButton(x, y, w,
					DesignerFormScreen.tr("reconcile"), () -> action.accept("reconcile")).active = canPublish && !publishing));
		}
		screen.rows.add(new DesignerFormScreen.Row("review_world", (x, y, w) -> {
			screen.primaryButton(x, y, w, DesignerFormScreen.tr("apply"), () -> action.accept("confirm_apply"))
					.active = !publishing && canPublish && !uncertain;
		}, 20, false, 140));
		if (screen instanceof BeeDesignerScreen editor) {
			screen.rows.add(new DesignerFormScreen.Row("review_local", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("local_export"), editor::exportLocal).active = !publishing, 20, false, 140));
			screen.anchor("review_portable");
			screen.rows.add(new DesignerFormScreen.Row("review_portable", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("collection_export"), () -> mc.setScreen(new DesignerCollectionScreen(editor,
							java.util.List.of(document.get("id").getAsString()), document))).active = !publishing, 20, false, 140));
			screen.rows.add(new DesignerFormScreen.Row("review_scripts", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("kubejs_export"), () -> DesignerProjectFiles.exportKubeJs(document,
							path -> screen.status = Component.translatable("designer.status.exported", path.toString()).getString(),
							message -> screen.status = message)).active = !publishing, 20, false, 140));
		}
		if (!canPublish) {
			screen.note("permission", DesignerFormScreen.tr("error.permission"));
		}
		screen.group("review_more", true, () -> {
			screen.note("", DesignerFormScreen.tr("files_export_choice_help"));
			if (screen instanceof BeeDesignerScreen editor) {
				actions(screen, "runtime_data", editor::runtimeData, "history", () -> mc.setScreen(new DesignerHistoryScreen(editor)));
			}
			actions(screen, "open_exports_folder", () -> DesignerProjectFiles.openFolder(true, message -> screen.status = message),
					"file_locations", () -> mc.setScreen(new DesignerFileLocationsScreen(screen)));
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.button(x, y, w, DesignerFormScreen.tr("export"),
					() -> action.accept("export")).active = canPublish && !publishing));
			screen.note("", DesignerFormScreen.tr("kubejs_export_help"));
		});
		screen.group("generated_files", true, () -> {
			refreshGenerated(document);
			if (!generatedProblem.isBlank()) {
				screen.note("error", DesignerFormScreen.statusMessage(generatedProblem));
			}
			var files = generated.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
			for (int index = 0; index < files.size(); index++) {
				int selected = index;
				var entry = files.get(index);
				screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> screen.addRenderableWidget(
						new DesignerTheme.Action(x, y, w, Component.literal(entry.getKey()), () -> mc.setScreen(
								new DesignerJsonScreen(screen, files, selected))))));
			}
			screen.rows.add(new DesignerFormScreen.Row("advanced", (x, y, w) -> screen.button(x, y, w,
					DesignerFormScreen.tr("edit_project"), () -> mc.setScreen(new DesignerJsonScreen(screen, document, "Bee Designer project")))));
		});
	}

	private void refreshGenerated(JsonObject document) {
		if (document.equals(generatedSource)) {
			return;
		}
		generatedSource = document.deepCopy();
		generated = Map.of();
		generatedProblem = "";
		try {
			JsonObject source = document.deepCopy();
			if (!source.has("definition_path")) {
				source.add("definition_path", source.get("id"));
			}
			generated = Map.copyOf(BeeDesignerCompiler.files(Map.of(source.get("id").getAsString(), source.toString())));
		} catch (RuntimeException exception) {
			generatedProblem = exception.getMessage() == null ? "designer.error.invalid_project" : exception.getMessage();
		}
	}

	private static void actions(DesignerFormScreen screen, String first, Runnable firstAction, String second, Runnable secondAction) {
		screen.rows.add(new DesignerFormScreen.Row("", (x, y, w) -> {
			int cell = (w - 6) / 2;
			screen.button(x, y, cell, DesignerFormScreen.tr(first), firstAction);
			screen.button(x + cell + 6, y, cell, DesignerFormScreen.tr(second), secondAction);
		}));
	}
}
