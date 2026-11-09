package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerInstanceCatalog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 外部蜂种只预览工程；导入和导出使用同一批完整快照，不改当前注册表。 */
final class DesignerInstanceBeesScreen extends DesignerFormScreen {
	private final BeeDesignerScreen editor;
	private final DesignerFormScreen parent;
	private final Path instance;
	private final DesignerCatalogSelection selection = new DesignerCatalogSelection();
	private BeeDesignerInstanceCatalog.Catalog catalog;
	private Map<String, JsonObject> documents = Map.of();
	private String query = "";
	private boolean started;
	private boolean closed;

	DesignerInstanceBeesScreen(BeeDesignerScreen editor, DesignerFormScreen parent, Path instance) {
		super(Component.literal(instance.getFileName().toString()));
		this.editor = editor;
		this.parent = parent;
		editorParent = parent;
		this.instance = instance;
	}

	@Override
	protected void init() {
		begin();
		text("search", query, value -> {
			query = value;
			rebuild();
		});
		if (!started) {
			started = true;
			DesignerProjectFiles.instanceCatalog(instance, value -> {
				if (!closed) {
					catalog = value;
					Map<String, JsonObject> parsed = new LinkedHashMap<>();
					value.documents().forEach((id, raw) -> parsed.put(id, JsonParser.parseString(raw).getAsJsonObject()));
					documents = Map.copyOf(parsed);
					if (minecraft.screen == this) {
						rebuild();
					}
				}
			}, message -> {
				if (!closed) {
					status = message;
					if (minecraft.screen == this) {
						rebuild();
					}
				}
			});
		}
		if (catalog == null) {
			note("", tr("status.loading"));
		} else {
			note("", tr(catalog.runtimeSnapshot() ? "instance_runtime_snapshot" : "instance_offline_snapshot"));
			if (!catalog.capturedAt().isBlank()) {
				note("", Component.translatable("designer.instance_snapshot_time", catalog.capturedAt()));
			}
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("quick_select"), () -> minecraft.setScreen(
					new DesignerChoiceScreen(this, "quick_select",
							new String[]{"select_all", "clear_selection", "select_pack_bees", "select_editor_bees", "select_registered_bees"},
							this::quickSelect)))));
			note("", Component.translatable("designer.instance_bee_count", documents.size(), selection.size()));
			for (String id : visible()) {
				JsonObject document = documents.get(id);
				Component detail = Component.literal(id).append(" · ").append(tr(catalog.registered().contains(id)
						? "instance_registered_bee" : "instance_offline_bee"));
				String kind = origin(id);
				rows.add(new Row("", (x, y, w) -> {
					addRenderableWidget(new DesignerBeeButton(x, y, w - 28, id, DesignerCollectionScreen.localizedName(document), false, () -> {
						selection.toggle(-1, id);
						rebuild();
					}).detail(detail).selected(selection.contains(id)).selectionMode(true));
					button(x + w - 24, y, 24, Component.literal("…"), () -> minecraft.setScreen(
							new DesignerJsonScreen(this, document, id, true)))
							.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("preview")));
				}, 38));
				note("", tr(kind.equals("pack") ? "instance_pack_resource" : "origin." + kind));
			}
			if (!catalog.problems().isEmpty()) {
				group("instance_read_problems", true, () -> {
					for (String problem : catalog.problems()) {
						note("error", statusMessage(problem));
					}
				});
			}
		}
		finishRows();
		int cell = (contentWidth - 8) / 3;
		primaryButton(left, footerY(), cell, tr("batch_import"), () -> minecraft.setScreen(
				new DesignerBatchImportScreen(editor, this, selectedDocuments()))).active = selection.size() > 0;
		button(left + cell + 4, footerY(), cell, tr("collection_export"), () -> minecraft.setScreen(
				new DesignerCollectionScreen(this, selectedDocuments(), instance.getFileName().toString()))).active = selection.size() > 0;
		button(left + 2 * (cell + 4), footerY(), cell, tr("back"), this::onClose);
	}

	private List<String> visible() {
		var filter = DesignerSearch.compile(query);
		return documents.keySet().stream().sorted().filter(id -> {
			String name = DesignerCollectionScreen.localizedName(documents.get(id)).getString();
			return filter.matches(id.split(":", 2)[0], filter.needsName() ? DesignerSearch.indexed(id + " " + name) : id, List.of());
		}).toList();
	}

	private String origin(String id) {
		JsonObject document = documents.get(id);
		return document.has("_editor_origin") ? BeeDesignerDocument.text(document.getAsJsonObject("_editor_origin"), "kind", "unknown") : "unknown";
	}

	private void quickSelect(String action) {
		if (action.equals("clear_selection")) {
			selection.clear();
			return;
		}
		List<String> ids = visible().stream().filter(id -> switch (action) {
			case "select_all" -> true;
			case "select_pack_bees" -> origin(id).equals("pack");
			case "select_editor_bees" -> origin(id).equals("editor") || documents.get(id).has("_editor_origin")
					&& documents.get(id).getAsJsonObject("_editor_origin").has("edited")
					&& documents.get(id).getAsJsonObject("_editor_origin").get("edited").getAsBoolean();
			case "select_registered_bees" -> catalog.registered().contains(id);
			default -> false;
		}).toList();
		if (!selection.replace(ids)) {
			status = "designer.catalog_selection_limit";
		}
	}

	private Map<String, String> selectedDocuments() {
		Map<String, String> result = new LinkedHashMap<>();
		selection.ids().forEach(id -> result.put(id, documents.get(id).toString()));
		return Map.copyOf(result);
	}

	@Override
	public void onClose() {
		closed = true;
		minecraft.setScreen(parent);
	}
}
