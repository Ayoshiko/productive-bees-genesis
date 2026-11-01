package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDrafts;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.Comparator;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 多蜂草稿目录，坏草稿保持可见；归档保留文件，不停用世界中的蜂种。 */
final class DesignerDraftsScreen extends DesignerFormScreen {
	private final BeeDesignerScreen parent;
	private List<BeeDesignerDrafts.Entry> drafts;
	private String query = "";
	private long searchAt;
	private boolean busy;
	private boolean requested;
	private String sort = "recent";
	private final java.util.Set<String> selected = new java.util.LinkedHashSet<>();
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	DesignerDraftsScreen(BeeDesignerScreen parent) {
		super(tr("local_drafts"));
		this.parent = parent;
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		searchField(frameTop + 32, query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 400;
		});
		contentTop = frameTop + 56;
		rows.add(new Row("draft_sort", (x, y, w) -> dropdown(x, y, w, sort,
				new String[]{"recent", "oldest", "name", "id"}, value -> {
					sort = value;
					page = 0;
				})));
		note("", tr("local_drafts_help"));
		if (!requested) {
			requested = true;
			refresh();
		}
		if (drafts != null) {
			var filter = DesignerSearch.compile(query);
			Comparator<BeeDesignerDrafts.Entry> order = switch (sort) {
				case "oldest" -> Comparator.comparingLong(BeeDesignerDrafts.Entry::modified);
				case "name" -> Comparator.comparing(this::name, String.CASE_INSENSITIVE_ORDER);
				case "id" -> Comparator.comparing(BeeDesignerDrafts.Entry::id);
				default -> Comparator.comparingLong(BeeDesignerDrafts.Entry::modified).reversed();
			};
			int visible = 0;
			for (var entry : drafts.stream().sorted(order.thenComparing(BeeDesignerDrafts.Entry::id)).toList()) {
				String name = name(entry);
				if (!filter.matches(entry.id().split(":", 2)[0], DesignerSearch.indexed(entry.id() + " " + name), List.of())) {
					continue;
				}
				visible++;
				String date = entry.modified() == 0 ? tr("date_unknown").getString() : DATE.format(Instant.ofEpochMilli(entry.modified()));
				rows.add(new Row("", (x, y, w) -> button(x, y, w, Component.translatable(
						selected.contains(entry.id()) ? "designer.collection_unselect_named" : "designer.collection_select_named", name), () -> {
					if (!selected.remove(entry.id()) && selected.size() < com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection.MAX_BEES) {
						selected.add(entry.id());
					}
					rebuild();
				}).active = entry.problem().isBlank()));
				rows.add(new Row("", (x, y, w) -> {
					addRenderableWidget(new DesignerBeeButton(x, y, w - 56, entry.id(), Component.literal(name), entry.pending(), () -> {
						if (!entry.problem().isBlank()) {
							status = entry.problem();
							return;
						}
						busy = true;
						DesignerProjectFiles.draft(entry.id(), document -> {
							busy = false;
							if (minecraft.screen == this) {
								parent.importDocument(document);
							}
						}, this::failed);
					})).active = !busy;
					button(x + w - 52, y, 24, Component.literal("+"), () -> {
						busy = true;
						DesignerProjectFiles.draft(entry.id(), document -> {
							busy = false;
							if (minecraft.screen == this) {
								minecraft.setScreen(new NewBeeScreen(parent, document));
							}
						}, this::failed);
					}).active = !busy && entry.problem().isBlank();
					var archive = button(x + w - 22, y, 22, Component.literal("×"), () -> archive(entry.id()));
					archive.active = !busy;
					archive.setTooltip(Tooltip.create(tr("archive_draft")));
				}));
				note("", Component.translatable("designer.draft_modified", date).append(" · " + entry.id()).append(" · ")
						.append(tr(entry.pending() ? "unapplied_changes" : "active_revision")));
				if (!entry.problem().isBlank()) {
					note("error", statusMessage(entry.problem()));
				}
			}
			if (visible == 0) {
				note("", tr("empty_search"));
			}
		}
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("recycle_bin"), () -> minecraft.setScreen(new DesignerRecycleScreen(parent)))));
		rows.add(new Row("", (x, y, w) -> primaryButton(x, y, w, Component.translatable("designer.export_selection", selected.size()),
				() -> minecraft.setScreen(new DesignerCollectionScreen(this, java.util.List.copyOf(selected), null, true))).active = !selected.isEmpty()));
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("file_locations"), () -> minecraft.setScreen(new DesignerFileLocationsScreen(this)))));
		finishRows();
		button(left, footerY(), 90, tr("refresh"), this::refresh).active = !busy;
		button(left + 96, footerY(), Math.max(50, contentWidth - 182), tr("open_drafts_folder"),
				() -> DesignerProjectFiles.openFolder(false, message -> status = message));
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void refresh() {
		busy = true;
		DesignerProjectFiles.drafts(entries -> {
			busy = false;
			drafts = entries;
			if (minecraft.screen == this) {
				rebuild();
			}
		}, this::failed);
		DesignerDraftIndex.refresh();
	}

	private String name(BeeDesignerDrafts.Entry entry) {
		String localized = entry.names().getOrDefault(minecraft.options.languageCode, entry.names().getOrDefault("en_us", ""));
		return localized.isBlank() ? BeeDesignerScreen.beeName(entry.id()).getString() : localized;
	}

	private void archive(String id) {
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			minecraft.setScreen(this);
			if (confirmed) {
				busy = true;
				DesignerProjectFiles.archiveDraft(id, this::refresh, this::failed);
			}
		}, tr("archive_draft"), tr("archive_draft_help")));
	}

	private void failed(String message) {
		busy = false;
		status = message;
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	@Override
	public void tick() {
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			page = 0;
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
