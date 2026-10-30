package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDrafts;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 世界停用记录与本机归档分开展示，恢复不覆盖已有草稿或自动改写世界。 */
final class DesignerRecycleScreen extends DesignerFormScreen {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
	private final BeeDesignerScreen parent;
	private List<BeeDesignerDrafts.Archived> drafts;
	private JsonObject world;
	private long request = -1;
	private int worldPage;
	private boolean localRequested;
	private boolean busy;

	DesignerRecycleScreen(BeeDesignerScreen parent) {
		super(tr("recycle_bin"));
		this.parent = parent;
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		if (!localRequested) {
			localRequested = true;
			refreshLocal();
		}
		if (request < 0) {
			requestWorld();
		}
		note("", tr("recycle_help"));
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("file_locations"), () -> minecraft.setScreen(new DesignerFileLocationsScreen(this)))));
		section("recycle_pending");
		for (String id : DesignerDraftIndex.deletingIds()) {
			anchor("pending/" + id);
			rows.add(new Row("", (x, y, w) -> {
				addRenderableWidget(new DesignerBeeButton(x, y, w, id, DesignerDraftIndex.name(id), true, () -> {}));
			}));
			rows.add(new Row("", (x, y, w) -> {
				int cell = (w - 6) / 2;
				button(x, y, cell, tr("restore_bee"), () -> restorePending(id)).active = !busy && DesignerDraftIndex.restorable(id);
				button(x + cell + 6, y, cell, tr("recycle_discard_pending"), () -> discardPending(id)).active = !busy;
			}, 20, true));
		}
		section("recycle_world");
		if (world == null) {
			note("", tr("status.loading"));
		} else {
			for (var entry : world.getAsJsonArray("ids")) {
				String id = entry.getAsString();
				anchor("world/" + id);
				rows.add(new Row("", (x, y, w) -> {
					addRenderableWidget(new DesignerBeeButton(x, y, w, id, () -> {}));
				}));
				rows.add(new Row("", (x, y, w) -> {
					int cell = (w - 12) / 3;
					button(x, y, cell, tr("restore_bee"), () -> parent.loadForRestore(id)).active = parent.canPublish && !busy;
					button(x + cell + 6, y, cell, tr("copy_short"), () -> {
						minecraft.setScreen(parent);
						parent.copyBee(id);
					}).active = !busy;
					button(x + (cell + 6) * 2, y, cell, tr("recycle_purge_world"), () -> parent.changeDocument(
							() -> minecraft.setScreen(new DesignerDeleteScreen(parent, this, id, true)))).active = parent.canPublish && !busy;
				}, 20, true));
				JsonObject origin = world.getAsJsonObject("origins").getAsJsonObject(id);
				if (origin.has("deleted_at")) {
					note("", Component.translatable("designer.deleted_date", DATE.format(Instant.ofEpochMilli(origin.get("deleted_at").getAsLong()))));
				}
			}
			if (world.getAsJsonArray("ids").isEmpty()) {
				note("", tr("recycle_empty"));
			}
			int pages = world.get("pages").getAsInt();
			if (pages > 1) {
				rows.add(new Row("", (x, y, w) -> {
					button(x, y, 90, tr("previous_page"), () -> {
						worldPage = Math.max(0, worldPage - 1);
						requestWorld();
					}).active = worldPage > 0;
					button(x + w - 90, y, 90, tr("next_page"), () -> {
						worldPage = Math.min(pages - 1, worldPage + 1);
						requestWorld();
					}).active = worldPage < pages - 1;
				}));
			}
		}
		section("recycle_drafts");
		if (drafts == null) {
			note("", tr("status.loading"));
		} else {
			for (var archived : drafts) {
				anchor("drafts/" + archived.token());
				var entry = archived.entry();
				String name = entry.names().getOrDefault(minecraft.options.languageCode, entry.names().getOrDefault("en_us", entry.id()));
				rows.add(new Row("", (x, y, w) -> {
					if (entry.problem().isBlank()) {
						addRenderableWidget(new DesignerBeeButton(x, y, w, entry.id(), Component.literal(name), false, () -> {}));
					} else {
						button(x, y, w, Component.literal(archived.token()), () -> {}).active = false;
					}
				}));
				rows.add(new Row("", (x, y, w) -> {
					int cell = (w - 12) / 3;
					button(x, y, cell, tr("restore_draft"), () -> restoreDraft(archived)).active = !busy && entry.problem().isBlank();
					button(x + cell + 6, y, cell, tr("copy_short"), () -> DesignerProjectFiles.recycledDraft(archived.token(), document -> {
						if (minecraft.screen == this) {
							minecraft.setScreen(new NewBeeScreen(parent, document));
						}
					}, this::failed)).active = !busy && entry.problem().isBlank();
					button(x + (cell + 6) * 2, y, cell, tr("recycle_delete_local"), () -> deleteDraft(archived)).active = !busy;
				}, 20, true));
				note("", Component.translatable("designer.draft_modified", DATE.format(Instant.ofEpochMilli(entry.modified()))).append(" · " + entry.id()));
				if (!entry.problem().isBlank()) {
					note("error", statusMessage(entry.problem()));
				}
			}
			if (drafts.isEmpty()) {
				note("", tr("recycle_empty"));
			}
		}
		finishRows();
		button(left, footerY(), 90, tr("refresh"), () -> {
			refreshLocal();
			requestWorld();
		}).active = !busy;
		button(left + contentWidth - 90, footerY(), 90, Component.translatable("gui.done"), this::onClose);
	}

	private void refreshLocal() {
		DesignerDraftIndex.refresh();
		DesignerProjectFiles.recycledDrafts(entries -> {
			drafts = entries;
			if (minecraft.screen == this) {
				rebuild();
			}
		}, this::failed);
	}

	private void requestWorld() {
		JsonObject query = new JsonObject();
		query.addProperty("trash", true);
		query.addProperty("page", worldPage);
		request = BeeDesignerClient.request("catalog", query);
	}

	void receive(long sequence, JsonObject data) {
		if (sequence == request) {
			world = data;
			worldPage = data.get("page").getAsInt();
			rebuild();
		}
	}

	private void restorePending(String id) {
		busy = true;
		DesignerProjectFiles.draft(id, document -> {
			if (BeeDesignerLifecycle.purged(document)) {
				failed("designer.error.recycle_not_restorable");
				return;
			}
			JsonObject restored = BeeDesignerLifecycle.restore(document);
			DesignerProjectFiles.saveDraft(restored.toString(), () -> {
				DesignerDraftIndex.saved(restored);
				busy = false;
				if (minecraft.screen == this) {
					parent.importDocument(restored);
				}
			}, this::failed);
		}, this::failed);
	}

	private void restoreDraft(BeeDesignerDrafts.Archived archived) {
		busy = true;
		DesignerProjectFiles.restoreDraft(archived.token(), document -> {
			busy = false;
			status = "designer.status.draft_restored";
			refreshLocal();
		}, this::failed);
	}

	private void discardPending(String id) {
		if (busy) {
			return;
		}
		busy = true;
		DesignerProjectFiles.drafts(entries -> {
			busy = false;
			if (minecraft.screen != this) {
				return;
			}
			var entry = entries.stream().filter(value -> value.id().equals(id) && value.deleted()).findFirst();
			if (entry.isEmpty()) {
				failed("designer.error.recycle_changed");
				return;
			}
			confirmDiscard(entry.get());
		}, this::failed);
		rebuild();
	}

	private void confirmDiscard(BeeDesignerDrafts.Entry entry) {
		String id = entry.id();
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			minecraft.setScreen(this);
			if (confirmed) {
				busy = true;
				parent.discardLocalDraft(id, entry.modified(), () -> {
					busy = false;
					status = "designer.status.recycle_deleted";
					refreshLocal();
				}, this::failed);
				rebuild();
			}
		}, tr("recycle_discard_pending"), Component.translatable("designer.recycle_discard_help", id)));
	}

	private void deleteDraft(BeeDesignerDrafts.Archived archived) {
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			minecraft.setScreen(this);
			if (confirmed) {
				busy = true;
				DesignerProjectFiles.deleteRecycledDraft(archived.token(), archived.entry().modified(), () -> {
					busy = false;
					status = "designer.status.recycle_deleted";
					refreshLocal();
				}, this::failed);
				rebuild();
			}
		}, tr("recycle_delete_local"), Component.translatable("designer.recycle_delete_help", archived.entry().id())));
	}

	private void failed(String message) {
		busy = false;
		status = message;
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
