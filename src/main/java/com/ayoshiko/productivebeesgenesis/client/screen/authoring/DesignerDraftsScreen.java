package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDrafts;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerNativeBees;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
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
	private String stateFilter = "draft_all";
	private final DesignerCatalogSelection selection = new DesignerCatalogSelection();
	private List<BeeDesignerDrafts.Entry> matching = List.of();
	private List<String> selectable = List.of();
	private int[] selectionIndices = new int[0];
	private int firstDraftRow;
	private int draftRowHeight;
	private int columns = 1;
	private int cardWidth;
	private static final int CARD_GAP = 4;
	private static final int MIN_CARD_WIDTH = 180;
	private DesignerDraftCard.Check allCheck;
	private Button countButton;
	private Button applyButton;
	private Button exportButton;
	private boolean pressed;
	private boolean boxing;
	private boolean additiveBox;
	private boolean shiftClick;
	private boolean controlClick;
	private DesignerDraftCard pressedCard;
	private double startX;
	private double startY;
	private double pointerX;
	private double pointerY;
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	DesignerDraftsScreen(BeeDesignerScreen parent) {
		super(tr("local_drafts"));
		this.parent = parent;
		editorParent = parent;
	}

	@Override
	protected boolean showsInventory() {
		return false;
	}

	@Override
	protected void init() {
		cancelSelectionDrag();
		begin();
		if (!requested) {
			requested = true;
			refresh();
		}
		int sortWidth = contentWidth < 400 ? 84 : 112;
		int searchY = frameTop + 28;
		searchField(searchY, contentWidth - sortWidth - 4, query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 400;
		});
		dropdown(left + contentWidth - sortWidth, searchY + 5, sortWidth, sort,
				new String[]{"recent", "oldest", "name", "id"}, value -> {
					cancelSelectionDrag();
					selection.resetOrder();
					sort = value;
					page = 0;
				}).setTooltip(Tooltip.create(tr("draft_sort")));
		matching = matchingDrafts();
		selectable = matching.stream().filter(entry -> entry.problem().isBlank()).map(BeeDesignerDrafts.Entry::id).toList();
		selectionIndices = new int[matching.size()];
		int selectableIndex = 0;
		for (int index = 0; index < matching.size(); index++) {
			selectionIndices[index] = matching.get(index).problem().isBlank() ? selectableIndex++ : -1;
		}
		toolbar(frameTop + 57);
		contentTop = frameTop + 82;
		draftRowHeight = 26;
		columns = Math.max(1, Math.min(3, (contentWidth + CARD_GAP) / (MIN_CARD_WIDTH + CARD_GAP)));
		cardWidth = (contentWidth - (columns - 1) * CARD_GAP) / columns;
		firstDraftRow = rows.size();
		for (int index = 0; index < matching.size(); index += columns) {
			int first = index;
			rows.add(new Row("", (x, y, w) -> {
				for (int column = 0; column < columns && first + column < matching.size(); column++) {
					int entryIndex = first + column;
					draftRow(matching.get(entryIndex), selectionIndices[entryIndex],
							x + column * (cardWidth + CARD_GAP), y, cardWidth, draftRowHeight);
				}
			}, draftRowHeight, true));
		}
		if (drafts != null && matching.isEmpty()) {
			note("", tr("empty_search"));
		}
		finishRows();
		int actionWidth = (contentWidth - 64) / 2;
		applyButton = primaryButton(left, footerY(), actionWidth, tr("drafts_apply_short"), this::applySelected);
		exportButton = button(left + actionWidth + 4, footerY(), actionWidth, tr("drafts_export_short"),
				() -> minecraft.setScreen(new DesignerCollectionScreen(this, List.copyOf(selection.ids()), null, true)));
		updateSelection();
		button(left + contentWidth - 56, footerY(), 56, Component.translatable("gui.cancel"), this::onClose);
	}

	private List<BeeDesignerDrafts.Entry> matchingDrafts() {
		if (drafts == null) {
			return List.of();
		}
		var filter = DesignerSearch.compile(query);
		Comparator<BeeDesignerDrafts.Entry> order = switch (sort) {
			case "oldest" -> Comparator.comparingLong(BeeDesignerDrafts.Entry::modified);
			case "name" -> Comparator.comparing(this::name, String.CASE_INSENSITIVE_ORDER);
			case "id" -> Comparator.comparing(BeeDesignerDrafts.Entry::id);
			default -> Comparator.comparingLong(BeeDesignerDrafts.Entry::modified).reversed();
		};
		return drafts.stream().filter(entry -> switch (stateFilter) {
			case "draft_pending" -> entry.pending() && entry.problem().isBlank();
			case "draft_applied" -> !entry.pending() && entry.problem().isBlank();
			case "draft_errors" -> !entry.problem().isBlank();
			default -> true;
		}).filter(entry -> filter.matches(entry.id().split(":", 2)[0],
				DesignerSearch.indexed(entry.id() + " " + name(entry)), List.of()))
				.sorted(order.thenComparing(BeeDesignerDrafts.Entry::id)).toList();
	}

	private void toolbar(int y) {
		boolean allSelected = allSelected();
		Component selectHelp = Component.translatable(allSelected ? "designer.drafts_unselect_filtered" : "designer.drafts_select_filtered",
				BeeDesignerCollection.MAX_BEES);
		allCheck = addRenderableWidget(new DesignerDraftCard.Check(left + 2, y, allSelected, selectHelp, () -> {
			cancelSelectionDrag();
			if (allSelected()) {
				Set<String> retained = new LinkedHashSet<>(selection.ids());
				retained.removeAll(selectable);
				selection.replace(retained);
			} else {
				selectFiltered();
			}
			updateSelection();
		}));
		int filterWidth = contentWidth < 400 ? 88 : 112;
		int countWidth = contentWidth - filterWidth - 128;
		countButton = button(left + 26, y, countWidth, Component.empty(), () -> {
			cancelSelectionDrag();
			selection.clear();
			updateSelection();
		});
		int filterX = left + contentWidth - filterWidth - 98;
		dropdown(filterX, y, filterWidth, stateFilter,
				new String[]{"draft_all", "draft_pending", "draft_applied", "draft_errors"}, value -> {
					cancelSelectionDrag();
					selection.resetOrder();
					stateFilter = value;
					page = 0;
				});
		int toolsX = left + contentWidth - 94;
		addRenderableWidget(new DesignerTheme.Icon(toolsX, y, DesignerTheme.Symbol.REFRESH, () -> {
			refresh();
			rebuild();
		})).active = !busy;
		var folder = button(toolsX + 24, y, 22, Component.literal("↗"),
				() -> DesignerProjectFiles.openFolder(false, message -> status = message));
		folder.setTooltip(Tooltip.create(tr("open_drafts_folder")));
		var locations = button(toolsX + 48, y, 22, Component.literal("i"),
				() -> minecraft.setScreen(new DesignerFileLocationsScreen(this)));
		locations.setTooltip(Tooltip.create(tr("file_locations")));
		var recycle = addRenderableWidget(new DesignerTheme.Icon(toolsX + 72, y, DesignerTheme.Symbol.DELETE,
				() -> minecraft.setScreen(new DesignerRecycleScreen(parent))));
		recycle.setMessage(tr("recycle_bin"));
		recycle.setTooltip(Tooltip.create(tr("recycle_bin")));
	}

	private void draftRow(BeeDesignerDrafts.Entry entry, int selectionIndex, int x, int y, int width, int height) {
		String name = name(entry);
		String date = entry.modified() == 0 ? tr("date_unknown").getString() : DATE.format(Instant.ofEpochMilli(entry.modified()));
		String stateKey = entry.pending() ? "unapplied_changes" : "active_revision";
		if (!entry.problem().isBlank()) {
			stateKey = "error";
		}
		Component state = tr(stateKey);
		Component details = Component.literal(name + "\n" + entry.id() + "\n")
				.append(Component.translatable("designer.draft_modified", date)).append("\n").append(state);
		if (!entry.problem().isBlank()) {
			details = details.copy().append("\n").append(statusMessage(entry.problem()));
		}
		addRenderableWidget(new DesignerDraftCard(x, y, width, height, name, entry.id(), entry.pending(), !entry.problem().isBlank(), selection.contains(entry.id()), details, selectionIndex, () -> {
			if (!entry.problem().isBlank()) {
				status = entry.problem();
				return;
			}
			parent.loadLocalDraft(entry.id(), this, false);
		})).active = !busy;
		Component selectHelp = Component.translatable(selection.contains(entry.id())
				? "designer.collection_unselect_named" : "designer.collection_select_named", name);
		addRenderableWidget(new DesignerDraftCard.Check(x + 1, y + (height - 20) / 2,
				selection.contains(entry.id()), selectHelp, () ->
				choose(selectionIndex, entry.id(), hasShiftDown(), hasControlDown()), entry.id(), name))
				.active = !busy && entry.problem().isBlank();
		var copy = addRenderableWidget(new DesignerTheme.Icon(x + width - 43, y + (height - 20) / 2,
				DesignerTheme.Symbol.COPY, () -> parent.loadLocalDraft(entry.id(), this, true)));
		copy.active = !busy && entry.problem().isBlank() && !BeeDesignerNativeBees.contains(ResourceLocation.parse(entry.id()));
		var archive = addRenderableWidget(new DesignerTheme.Icon(x + width - 21, y + (height - 20) / 2,
				DesignerTheme.Symbol.DELETE, () -> archive(entry.id())));
		archive.active = !busy;
		archive.setMessage(tr("archive_draft"));
		archive.setTooltip(Tooltip.create(tr("archive_draft")));
	}

	private boolean allSelected() {
		return !selectable.isEmpty() && (selectable.stream().allMatch(selection::contains)
				|| selection.size() == BeeDesignerCollection.MAX_BEES && selectable.stream().anyMatch(selection::contains));
	}

	private void selectFiltered() {
		List<String> added = selectable.stream().filter(id -> !selection.contains(id))
				.limit(BeeDesignerCollection.MAX_BEES - selection.size()).toList();
		selection.add(added);
		if (!selectable.stream().allMatch(selection::contains)) {
			status = "designer.catalog_selection_limit";
		}
	}

	private void choose(int index, String id, boolean range, boolean additive) {
		boolean changed;
		if (range) {
			changed = selection.range(index, additive);
			if (changed && !selection.resolve(selectable::get)) {
				status = "designer.catalog_selection_limit";
			}
		} else {
			changed = selection.toggle(index, id);
		}
		if (!changed) {
			status = "designer.catalog_selection_limit";
		}
		updateSelection();
	}

	private void updateSelection() {
		for (var child : children()) {
			if (child instanceof DesignerDraftCard card) {
				card.selected(selection.contains(card.draftId()));
			} else if (child instanceof DesignerDraftCard.Check check && !check.draftId().isEmpty()) {
				check.selected(selection.contains(check.draftId()));
			}
		}
		boolean allSelected = allSelected();
		Component selectHelp = Component.translatable(allSelected ? "designer.drafts_unselect_filtered" : "designer.drafts_select_filtered",
				BeeDesignerCollection.MAX_BEES);
		allCheck.selected(allSelected);
		allCheck.setMessage(selectHelp);
		allCheck.setTooltip(Tooltip.create(selectHelp.copy().append("\n").append(tr("drafts_selection_help"))));
		allCheck.active = !busy && !selectable.isEmpty();
		countButton.setMessage(countButton.getWidth() < 88 ? Component.literal(selection.size() + " / " + matching.size())
				: Component.translatable("designer.drafts_selected_count", selection.size(), matching.size()));
		countButton.active = !busy && selection.size() > 0;
		countButton.setTooltip(Tooltip.create(Component.translatable("designer.drafts_selection_summary", selection.size(),
				matching.size(), drafts == null ? 0 : drafts.size()).append("\n").append(tr("drafts_clear_selection"))
				.append("\n").append(tr("drafts_selection_help"))));
		applyButton.active = !busy && parent.canPublish && selection.size() > 0;
		applyButton.setTooltip(Tooltip.create(Component.translatable("designer.drafts_apply_selection", selection.size())));
		exportButton.active = !busy && selection.size() > 0;
		exportButton.setTooltip(Tooltip.create(Component.translatable("designer.export_selection", selection.size())));
	}

	private void cancelSelectionDrag() {
		selection.cancelBox();
		pressed = false;
		boxing = false;
		pressedCard = null;
		setDragging(false);
	}

	@Override
	protected Component idleStatus() {
		return tr("drafts_selection_shortcuts");
	}

	@Override
	protected Component statusTooltip(Component message) {
		return status.isBlank() ? tr("drafts_selection_help") : super.statusTooltip(message);
	}

	@Override
	protected boolean clickContent(double x, double y, int button) {
		if (!busy && !hasDropdown() && !isInventoryDragging() && button == 0 && !matching.isEmpty()
				&& x >= left && x < left + contentWidth && y >= contentTop && y < editorBottom()) {
			var child = getChildAt(x, y).orElse(null);
			if (child == null || child instanceof DesignerDraftCard) {
				pressed = true;
				boxing = false;
				pressedCard = child instanceof DesignerDraftCard card ? card : null;
				startX = x;
				startY = y - contentTop + scrollOffset();
				pointerX = x;
				pointerY = y;
				shiftClick = hasShiftDown();
				controlClick = hasControlDown();
				additiveBox = controlClick || shiftClick;
				setFocused(null);
				setDragging(true);
				return true;
			}
		}
		return super.clickContent(x, y, button);
	}

	@Override
	protected boolean dragContent(double x, double y, int button, double dx, double dy) {
		if (!pressed || button != 0) {
			return super.dragContent(x, y, button, dx, dy);
		}
		pointerX = x;
		pointerY = y;
		if (!boxing && Math.hypot(x - startX, y - contentTop + scrollOffset() - startY) >= 4) {
			boxing = true;
			selection.beginBox(additiveBox);
		}
		if (boxing) {
			updateBox();
		}
		return true;
	}

	private void updateBox() {
		double endY = pointerY - contentTop + scrollOffset();
		double top = Math.min(startY, endY) - rowOffset(firstDraftRow);
		double bottom = Math.max(startY, endY) - rowOffset(firstDraftRow);
		int rowHeight = draftRowHeight + DesignerPreferences.rowGap();
		int first = Math.max(0, (int) Math.floor(top / rowHeight));
		int last = Math.min((matching.size() - 1) / columns, (int) Math.floor(bottom / rowHeight));
		double boxLeft = Math.min(startX, pointerX);
		double boxRight = Math.max(startX, pointerX);
		List<Integer> indices = new ArrayList<>();
		for (int row = first; row <= last && indices.size() <= DesignerCatalogSelection.LIMIT; row++) {
			if (row * rowHeight + draftRowHeight < top) {
				continue;
			}
			for (int column = 0; column < columns && row * columns + column < matching.size(); column++) {
				int entryIndex = row * columns + column;
				int x = left + column * (cardWidth + CARD_GAP);
				if (x < boxRight && x + cardWidth > boxLeft && selectionIndices[entryIndex] >= 0) {
					indices.add(selectionIndices[entryIndex]);
				}
			}
		}
		if (!selection.box(indices, selectable::get)) {
			status = "designer.catalog_selection_limit";
		}
		updateSelection();
	}

	@Override
	protected boolean releaseContent(double x, double y, int button) {
		if (!pressed || button != 0) {
			return super.releaseContent(x, y, button);
		}
		pressed = false;
		setDragging(false);
		if (boxing) {
			pointerX = x;
			pointerY = y;
			updateBox();
			selection.endBox();
			boxing = false;
		} else if (pressedCard != null && pressedCard.isMouseOver(x, y)) {
			if ((shiftClick || controlClick) && pressedCard.selectionIndex() >= 0) {
				choose(pressedCard.selectionIndex(), pressedCard.draftId(), shiftClick, controlClick);
			} else {
				pressedCard.onPress();
			}
		} else if (pressedCard == null && !additiveBox) {
			selection.clear();
		}
		pressedCard = null;
		updateSelection();
		return true;
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (isLeaving()) {
			return true;
		}
		if (!hasDropdown() && key == GLFW.GLFW_KEY_ESCAPE && pressed) {
			cancelSelectionDrag();
			updateSelection();
			return true;
		}
		if (!busy && !hasDropdown() && !(getFocused() instanceof EditBox) && hasControlDown() && key == GLFW.GLFW_KEY_A) {
			cancelSelectionDrag();
			selectFiltered();
			updateSelection();
			return true;
		}
		return super.keyContent(key, scan, modifiers);
	}

	@Override
	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderContent(graphics, mouseX, mouseY, partialTick);
		if (!boxing) {
			return;
		}
		int offset = (int) Math.round(contentOffsetX());
		int x = (int) Math.min(startX, pointerX) + offset;
		int y = (int) Math.min(startY + contentTop - scrollOffset(), pointerY);
		int width = Math.max(1, (int) Math.abs(startX - pointerX));
		int height = Math.max(1, (int) Math.abs(startY + contentTop - scrollOffset() - pointerY));
		DesignerTheme.scissor(graphics, left, contentTop, left + contentWidth, editorBottom());
		int color = DesignerTheme.colors().accent();
		graphics.fill(x, y, x + width, y + height, 0x26000000 | color & 0xFFFFFF);
		graphics.renderOutline(x, y, width, height, color);
		graphics.disableScissor();
	}

	private void refresh() {
		cancelSelectionDrag();
		busy = true;
		DesignerProjectFiles.drafts(entries -> {
			busy = false;
			drafts = entries;
			Set<String> retained = new LinkedHashSet<>(selection.ids());
			retained.retainAll(entries.stream().filter(entry -> entry.problem().isBlank()).map(BeeDesignerDrafts.Entry::id).toList());
			selection.replace(retained);
			if (minecraft.screen == this) {
				rebuild();
			}
		}, this::failed);
		DesignerDraftIndex.refresh();
	}

	private void applySelected() {
		if (busy || !parent.canPublish || selection.size() == 0) {
			return;
		}
		busy = true;
		var connection = minecraft.getConnection();
		DesignerProjectFiles.drafts(List.copyOf(selection.ids()), documents -> {
			busy = false;
			if (minecraft.screen == this && minecraft.getConnection() == connection) {
				requested = false;
				minecraft.setScreen(new DesignerBatchImportScreen(parent, this, documents, true));
			}
		}, this::failed);
		rebuild();
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
			cancelSelectionDrag();
			selection.resetOrder();
			page = 0;
			rebuild();
		}
		if (boxing) {
			if (pointerY < contentTop + 18) {
				scrollRows(-1);
			} else if (pointerY > editorBottom() - 18) {
				scrollRows(1);
			}
			updateBox();
		}
	}

	@Override
	public void onClose() {
		cancelSelectionDrag();
		minecraft.setScreen(parent);
	}
}
