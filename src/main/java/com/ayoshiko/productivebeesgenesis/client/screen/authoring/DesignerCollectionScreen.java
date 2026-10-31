package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCredits;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 每次只读取一个封闭工程；关闭、超时和迟到响应不能改写编辑会话。 */
final class DesignerCollectionScreen extends DesignerFormScreen {

	private final DesignerFormScreen parent;
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

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing) {
		this(parent, ids, editing, false);
	}

	DesignerCollectionScreen(DesignerFormScreen parent, List<String> ids, JsonObject editing, boolean localDrafts) {
		super(tr("collection_export"));
		this.parent = parent;
		editorParent = parent;
		this.localDrafts = localDrafts;
		selected = ids.stream().distinct().toList();
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
		note("", tr("collection_help"));
		if (minecraft.getSingleplayerServer() == null) {
			note("", tr("collection_remote_source"));
		}
		note("", Component.translatable("designer.collection_progress", documents.size(), selected.size(), failures.size()));
		rows.add(new Row("collection_format", (x, y, w) -> selection(x, y, w, format,
				new String[]{"portable", "datapack", "kubejs"}, value -> format = value)));
		note("", tr("collection_format_help." + format));
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
		section("collection_selected");
		for (String id : selected) {
			JsonObject document = documents.containsKey(id)
					? previews.computeIfAbsent(id, key -> JsonParser.parseString(documents.get(key)).getAsJsonObject()) : null;
			Component name = document == null ? BeeDesignerClient.beeName(id) : localizedName(document);
			rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, id, name, false, () -> {}))));
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
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("open_exports_folder"), () ->
					DesignerProjectFiles.openDirectory(exported.getParent(), false, message -> status = message))));
		}
		finishRows();
		int cell = (contentWidth - 6) / 2;
		primaryButton(left, footerY(), cell, tr("collection_export"), this::export).active = ready() && !writing;
		button(left + cell + 6, footerY(), cell, tr(writing ? "close_editor" : "back"), this::onClose);
	}

	@Override
	public void tick() {
		if (closed || writing) {
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

	private boolean ready() {
		return !selected.isEmpty() && documents.size() == selected.size() && failures.isEmpty();
	}

	private void captureName(JsonObject document) {
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
			Map<String, String> named = new LinkedHashMap<>();
			for (var entry : documents.entrySet()) {
				JsonObject source = JsonParser.parseString(entry.getValue()).getAsJsonObject();
				captureName(source);
				named.put(entry.getKey(), source.toString());
			}
			BeeDesignerCredits.validate(credits);
			if (BeeDesignerDocument.text(credits, "pack", "").isBlank()) {
				throw new IllegalArgumentException("designer.error.credits_pack");
			}
			if (documents.values().stream().map(JsonParser::parseString).map(value -> value.getAsJsonObject())
					.anyMatch(value -> value.has("_editor_recovery"))) {
				throw new IllegalArgumentException("designer.error.collection_unfinished");
			}
			writing = true;
			status = "designer.status.exporting";
			DesignerProjectFiles.exportCollection(named, credits, format, path -> {
				writing = false;
				exported = path;
				status = Component.translatable("designer.status.exported", path.toString()).getString();
				rebuildIfVisible();
				if (generation >= 0 && !localDrafts && minecraft.getSingleplayerServer() != null) {
					List<String> registered = cy.jdkdigital.productivebees.setup.BeeReloadListener.INSTANCE.getData().keySet().stream().map(Object::toString).sorted().toList();
					if (named.keySet().equals(java.util.Set.copyOf(registered)) && editingSnapshots.isEmpty()
							&& selected.stream().noneMatch(DesignerDraftIndex::pending)) {
						DesignerProjectFiles.saveRegisteredCatalog(named, registered, generation, () -> {}, message -> {
							status = message;
							rebuildIfVisible();
						});
					}
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
		minecraft.setScreen(parent);
	}
}
