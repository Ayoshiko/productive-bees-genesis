package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCredits;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerExportName;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerNativeBees;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Tooltip;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每次只读取一个封闭工程；关闭、超时和迟到响应不能改写编辑会话。 */
final class DesignerCollectionScreen extends DesignerFormScreen {

	private final DesignerFormScreen parent;
	private final BeeDesignerScreen editor;
	private final boolean applicationEntry;
	private boolean applyOnReady;
	private final JsonObject exportOptions = new JsonObject();
	private final List<String> selected;
	private final Map<String, String> documents = new LinkedHashMap<>();
	private final Map<String, JsonObject> previews = new LinkedHashMap<>();
	private final Map<String, String> editingSnapshots = new LinkedHashMap<>();
	private final Map<String, String> failures = new LinkedHashMap<>();
	private final ArrayDeque<String> pending;
	private final boolean localDrafts;
	private JsonObject credits = BeeDesignerCredits.empty();
	private String format = "portable";
	private String current = "";
	private long request = -1;
	private long requestedAt;
	private long generation = -1;
	private boolean localRead;
	private boolean started;
	private boolean writing;
	private boolean closed;
	private boolean detectingCredits;
	private Path exported;
	private boolean installedResult;

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing) {
		this(parent, ids, editing, false);
	}

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing, String format) {
		this(parent, ids, editing, false);
		this.format = format;
	}

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing, boolean localDrafts) {
		this(parent, ids, editing, localDrafts, false);
	}

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing, boolean localDrafts, boolean applicationEntry) {
		super(tr(applicationEntry ? "batch_apply_world" : "collection_export"));
		this.parent = parent;
		if (parent instanceof BeeDesignerScreen main) {
			editor = main;
		} else if (parent instanceof DesignerDraftsScreen drafts) {
			editor = drafts.parentEditor();
		} else {
			editor = null;
		}
		this.applicationEntry = applicationEntry;
		applyOnReady = applicationEntry;
		editorParent = parent;
		this.localDrafts = localDrafts;
		selected = new ArrayList<>(ids.stream().distinct().toList());
		if (selected.size() > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		pending = new ArrayDeque<>(selected);
		if (editing != null && selected.contains(BeeDesignerDocument.text(editing, "id", ""))) {
			String id = editing.get("id").getAsString();
			documents.put(id, editing.deepCopy().toString());
			editingSnapshots.put(id, documents.get(id));
			pending.remove(id);
		}
	}

	DesignerCollectionScreen(DesignerFormScreen parent, Map<String, String> projects, String sourcePack) {
		this(parent, List.copyOf(projects.keySet()), null, false);
		documents.putAll(projects);
		pending.clear();
		credits.addProperty("pack", sourcePack);
		started = true;
	}

	@Override
	protected void init() {
		begin();
		if (applicationEntry) {
			started = true;
		}
		if (!started) {
			started = true;
			detectingCredits = true;
			DesignerProjectFiles.detectCredits(value -> {
				detectingCredits = false;
				if (!closed) {
					for (String key : BeeDesignerCredits.FIELDS) {
						if (BeeDesignerDocument.text(credits, key, "").isBlank()) {
							credits.add(key, value.get(key).deepCopy());
						}
					}
					rebuildIfVisible();
				}
			}, message -> {
				detectingCredits = false;
				if (!closed) {
					status = message;
					rebuildIfVisible();
				}
			});
		}
		note("", tr(applicationEntry ? "batch_apply_world_help" : "collection_help"));
		if (minecraft.getSingleplayerServer() == null) {
			note("", tr("collection_remote_source"));
		}
		note("", Component.translatable("designer.collection_progress", documents.size(), selected.size(), failures.size()));
		if (!applicationEntry) {
			rows.add(new Row("collection_format", (x, y, w) -> {
				if (writing) {
					fixedNote(tr("option." + format), x, y, w, 20);
				} else {
					selection(x, y, w, format, new String[]{"portable", "datapack", "kubejs", "runtime_datapack", "runtime_kubejs"}, value -> {
						format = value;
						status = "";
						exported = null;
						installedResult = false;
						rebuild();
					});
				}
			}));
			addFormatHelp(this, format);
			if (editor != null) {
				String applyLabel = format.equals("runtime_kubejs") ? "collection_apply_after_install" : "collection_apply_after_export";
				anchor("apply_after_export");
				rows.add(new Row(applyLabel, (x, y, w) -> {
					boolean enabled = exportOptions.has("apply") && exportOptions.get("apply").getAsBoolean();
					var toggle = addRenderableWidget(new DesignerTheme.Switch(x, y, Math.min(w, 74), enabled, () -> {
						boolean current = exportOptions.has("apply") && exportOptions.get("apply").getAsBoolean();
						exportOptions.addProperty("apply", !current);
					}));
					toggle.active = canApply() && !writing;
					toggle.setTooltip(Tooltip.create(tr("collection_apply_after_help")));
				}, 20, false, 74));
			}
			if (!format.equals("portable")) {
				note("", tr("collection_identity_choice_help"));
			}
			section("collection_source");
			if (detectingCredits) {
				note("", tr("credits_detecting"));
			} else if (!BeeDesignerDocument.text(credits, "manifest", "").isBlank()) {
				note("", Component.translatable("designer.credits_detected", credits.get("manifest").getAsString()));
			} else {
				note("", tr("credits_not_detected"));
			}
			for (String key : List.of("pack", "version", "author")) {
				field("credits." + key, credits, key, "");
			}
			group("credits_optional", true, () -> {
				for (String key : List.of("url", "permission", "notes")) {
					field("credits." + key, credits, key, "");
				}
			});
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("credits_read_manifest"), () ->
					minecraft.setScreen(new DesignerFileBrowserScreen(this, "", false, path ->
							DesignerProjectFiles.readCredits(path, value -> {
								for (String key : List.of("permission", "notes")) {
									if (credits.has(key) && !credits.get(key).getAsString().isBlank()) {
										value.add(key, credits.get(key).deepCopy());
									}
								}
								credits = value;
								rebuildIfVisible();
							}, message -> status = message)))).active = !writing));
			note("", tr("collection_license_help"));
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("recipe_batch"), () ->
					minecraft.setScreen(new DesignerRecipeBatchScreen(this, documents))).active = ready() && !writing));
		}
		section("collection_selected");
		note("", tr("collection_remove_selection_help"));
		for (String id : selected) {
			JsonObject document = documents.containsKey(id)
					? previews.computeIfAbsent(id, key -> JsonParser.parseString(documents.get(key)).getAsJsonObject()) : null;
			Component name = document == null ? BeeDesignerClient.beeName(id) : localizedName(document);
			rows.add(new Row("", (x, y, w) -> {
				int removeWidth = Math.min(120, Math.max(60, font.width(tr("collection_remove_selection")) + 18));
				addRenderableWidget(new DesignerBeeButton(x, y, w - removeWidth - 6, id, name, false, () -> {})
						.detail(Component.literal(id)));
				var remove = button(x + w - removeWidth, y + 7, removeWidth, tr("collection_remove_selection"), () -> removeSelection(id));
				remove.active = document != null && !writing;
				remove.setTooltip(Tooltip.create(tr("collection_remove_selection_help")));
			}, 34, true));
			if (document != null) {
				addCredits(this, document);
			} else if (failures.containsKey(id)) {
				note("error", statusMessage(failures.get(id)));
			}
		}
		if (!failures.isEmpty()) {
			note("error", tr("collection_failed_help"));
			rows.add(new Row("", (x, y, w) -> {
				var retry = button(x, y, w, tr("collection_retry"), () -> {
				failures.clear();
				pending.clear();
				documents.clear();
				previews.clear();
				documents.putAll(editingSnapshots);
				selected.stream().filter(id -> !editingSnapshots.containsKey(id)).forEach(pending::add);
				generation = -1;
				status = "";
				rebuild();
				});
				retry.active = request < 0 && !localRead && pending.isEmpty();
			}));
		}
		if (exported != null) {
			note("", Component.literal(exported.toString()));
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr(installedResult ? "open_instance_folder" : "open_exports_folder"), () ->
					DesignerProjectFiles.openDirectory(exported.getParent(), false, message -> status = message))));
		}
		finishRows();
		int columns = editor != null && !applicationEntry ? 3 : 2;
		int cell = (contentWidth - (columns - 1) * 6) / columns;
		if (!applicationEntry) {
			primaryButton(left, footerY(), cell, tr(format.equals("runtime_kubejs") ? "install_preview" : "collection_preview_export"), this::export).active = ready() && !writing;
		}
		if (editor != null) {
			int x = applicationEntry ? left : left + cell + 6;
			var apply = button(x, footerY(), cell, Component.translatable("designer.catalog_apply", selected.size()), this::applyToWorld);
			apply.active = ready() && !writing && canApply();
			String applyHelp = "collection_apply_world_help";
			if (!editor.canPublish) {
				applyHelp = "apply_world_requires_author";
			} else if (editor.isPublishing()) {
				applyHelp = "apply_world_busy";
			}
			apply.setTooltip(Tooltip.create(tr(applyHelp)));
		}
		button(left + (columns - 1) * (cell + 6), footerY(), cell, tr(writing ? "close_editor" : "back"), this::onClose);
	}

	static void addFormatHelp(DesignerFormScreen screen, String format) {
		screen.note("", tr("collection_format_help." + format));
		screen.group("collection_format_guide", true, () -> {
			for (String part : List.of("contents", "install", "reload", "editing")) {
				screen.note("", tr("collection_guide." + part).copy().append(": ")
						.append(tr("collection_" + part + "." + format)));
			}
			screen.note("", tr("collection_guide_dependencies"));
			screen.note("", tr("collection_guide_apply"));
			screen.note("", tr("collection_guide_packaging"));
			if (!format.equals("portable")) {
				screen.note("", tr("collection_guide_one_install"));
			}
		});
	}

	@Override
	public void tick() {
		if (closed || writing) {
			return;
		}
		if (applyOnReady && ready()) {
			applyOnReady = false;
			applyToWorld();
			return;
		}
		if (request >= 0 && System.currentTimeMillis() - requestedAt >= 15000) {
			BeeDesignerClient.cancelRequest(request);
			failed(request, "designer.error.bee_catalog_timeout");
		}
		if (request >= 0 || localRead || pending.isEmpty()) {
			return;
		}
		current = pending.removeFirst();
		if (localDrafts || DesignerDraftIndex.pending(current)) {
			localRead = true;
			String id = current;
			DesignerProjectFiles.draft(id, document -> {
				localRead = false;
				if (!closed) {
					documents.put(id, document.toString());
					rebuildIfVisible();
				}
			}, message -> {
				localRead = false;
				if (!closed) {
					failures.put(id, message);
					rebuildIfVisible();
				}
			});
		} else {
			try {
				JsonObject query = new JsonObject();
				query.addProperty("id", current);
				if (generation >= 0) {
					query.addProperty("generation", generation);
				}
				request = BeeDesignerClient.request("export_snapshot", query);
				requestedAt = System.currentTimeMillis();
				if (request < 0) {
					failures.put(current, "designer.error.designer_session");
				}
			} catch (RuntimeException failure) {
				failures.put(current, failure.getMessage());
			}
		}
	}

	boolean receive(long sequence, JsonObject document) {
		if (closed || sequence != request || !current.equals(BeeDesignerDocument.text(document, "id", ""))) {
			return false;
		}
		request = -1;
		if (document.has("_editor_export_generation")) {
			long receivedGeneration = document.remove("_editor_export_generation").getAsLong();
			if (generation >= 0 && receivedGeneration != generation) {
				failures.put(current, "designer.error.collection_changed");
				pending.clear();
				rebuildIfVisible();
				return true;
			}
			generation = receivedGeneration;
		}
		captureName(document);
		documents.put(current, document.toString());
		rebuildIfVisible();
		return true;
	}

	boolean failed(long sequence, String message) {
		if (closed || sequence != request) {
			return false;
		}
		request = -1;
		failures.put(current, message);
		rebuildIfVisible();
		return true;
	}

	private void removeSelection(String id) {
		selected.remove(id);
		documents.remove(id);
		previews.remove(id);
		editingSnapshots.remove(id);
		status = "";
		exported = null;
		installedResult = false;
		rebuild();
	}

	private boolean ready() {
		return !selected.isEmpty() && documents.size() == selected.size() && failures.isEmpty();
	}

	private boolean canApply() {
		return editor != null && editor.canPublish && !editor.isPublishing();
	}

	private Map<String, String> namedDocuments() {
		Map<String, String> named = new LinkedHashMap<>();
		for (var entry : documents.entrySet()) {
			JsonObject source = JsonParser.parseString(entry.getValue()).getAsJsonObject();
			if (source.has("_editor_recovery")) {
				throw new IllegalArgumentException("designer.error.collection_unfinished");
			}
			captureName(source);
			named.put(entry.getKey(), source.toString());
		}
		return Map.copyOf(named);
	}

	private void applyToWorld() {
		if (!ready() || writing || !canApply()) {
			return;
		}
		try {
			minecraft.setScreen(new DesignerBatchImportScreen(editor, applicationEntry ? parent : this, namedDocuments(), true));
		} catch (RuntimeException failure) {
			status = failure.getMessage();
			rebuild();
		}
	}

	private void captureName(JsonObject document) {
		if (BeeDesignerNativeBees.recipesOnly(document)) {
			return;
		}
		JsonObject names = document.getAsJsonObject("names");
		String locale = minecraft.options.languageCode;
		String key = BeeDesignerDocument.translationKey(document.get("id").getAsString());
		if (!names.has(locale) && net.minecraft.locale.Language.getInstance().has(key)) {
			names.addProperty(locale, Component.translatable(key).getString());
		}
	}

	private void export() {
		if (!ready() || writing) {
			return;
		}
		try {
			Map<String, String> named = namedDocuments();
			boolean alsoApply = exportOptions.has("apply") && exportOptions.get("apply").getAsBoolean();
			if (alsoApply && !canApply()) {
				throw new IllegalArgumentException(editor.canPublish ? "designer.apply_world_busy" : "designer.apply_world_requires_author");
			}
			var exportConnection = minecraft.getConnection();
			BeeDesignerCredits.validate(credits);
			if (BeeDesignerDocument.text(credits, "pack", "").isBlank()) {
				throw new IllegalArgumentException("designer.error.credits_pack");
			}
			writing = true;
			status = format.equals("runtime_kubejs") ? "designer.install_preparing" : "designer.status.exporting";
			DesignerProjectFiles.prepareExport(named, credits, format, preview -> {
				writing = false;
				if (!closed && minecraft.screen == this) {
					status = "";
					String name = preview.installation() == null ? BeeDesignerExportName.suggest(named.keySet(), preview.format()) : "";
					minecraft.setScreen(new DesignerExportPreviewScreen(this, preview, name, path -> {
						exported = path;
						installedResult = preview.installation() != null;
						status = Component.translatable(installedResult ? "designer.install_complete" : "designer.status.exported", path.toString()).getString();
						rebuildIfVisible();
						if (generation >= 0 && !localDrafts && minecraft.getSingleplayerServer() != null) {
							List<String> registered = BeeDesignerEggs.bees();
							if (named.keySet().equals(java.util.Set.copyOf(registered)) && editingSnapshots.isEmpty()
									&& selected.stream().noneMatch(DesignerDraftIndex::pending)) {
								DesignerProjectFiles.saveRegisteredCatalog(named, registered, generation, () -> {}, message -> {
									status = message;
									rebuildIfVisible();
								});
							}
						}
						if (alsoApply) {
							if (minecraft.getConnection() != exportConnection
									|| !(minecraft.screen instanceof DesignerExportPreviewScreen active) || active.editorParent != this) {
								status = "designer.export_apply_context_changed";
								return;
							}
							var application = new DesignerBatchImportScreen(editor, this, named, true);
							application.continueAfterExport(path);
							minecraft.setScreen(application);
						}
					}, alsoApply));
				}
			}, message -> {
				writing = false;
				status = message;
				rebuildIfVisible();
			});
		} catch (RuntimeException failure) {
			writing = false;
			status = failure.getMessage();
		}
		rebuild();
	}

	private void rebuildIfVisible() {
		if (!closed && minecraft.screen == this) {
			rebuild();
		}
	}

	static Component localizedName(JsonObject document) {
		String name = BeeDesignerDocument.text(document.getAsJsonObject("names"), net.minecraft.client.Minecraft.getInstance().options.languageCode,
				BeeDesignerDocument.text(document.getAsJsonObject("names"), "en_us", ""));
		return name.isBlank() ? BeeDesignerClient.beeName(document.get("id").getAsString()) : Component.literal(name);
	}

	static void addCredits(DesignerFormScreen screen, JsonObject document) {
		if (document.has("_editor_origin")) {
			String source = BeeDesignerDocument.text(document.getAsJsonObject("_editor_origin"), "source_pack", "");
			if (!source.isBlank()) {
				screen.note("", Component.translatable("designer.credits_resource", source));
			}
			String copied = BeeDesignerDocument.text(document.getAsJsonObject("_editor_origin"), "copied_source_pack", "");
			if (!copied.isBlank() && !copied.equals(source)) {
				screen.note("", Component.translatable("designer.credits_resource", copied));
			}
		}
		if (document.has(BeeDesignerCredits.FIELD)) {
			for (var source : document.getAsJsonArray(BeeDesignerCredits.FIELD)) {
				for (String key : List.of("pack", "version", "author", "url", "permission", "notes")) {
					String value = BeeDesignerDocument.text(source.getAsJsonObject(), key, "");
					if (!value.isBlank()) {
						screen.note("", tr("credits." + key).copy().append(": " + value));
					}
				}
			}
		}
	}

	@Override
	public void onClose() {
		closed = true;
		pending.clear();
		BeeDesignerClient.cancelRequest(request);
		request = -1;
		List<String> remaining = List.copyOf(selected);
		if (parent instanceof BeeDesignerScreen editor) {
			editor.rememberExportSelection(remaining);
		} else if (parent instanceof DesignerDraftsScreen drafts) {
			drafts.rememberExportSelection(remaining);
		}
		minecraft.setScreen(parent);
	}
}
