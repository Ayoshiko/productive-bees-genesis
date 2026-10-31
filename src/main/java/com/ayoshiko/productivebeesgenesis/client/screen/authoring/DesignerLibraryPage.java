package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import org.lwjgl.glfw.GLFW;

/** 目录自己管理搜索节流、分页和响应序号，迟到的搜索不能替换新结果。 */
final class DesignerLibraryPage {
	private String query = "";
	private long searchAt;
	private long request = -1;
	private long requestAt;
	private String problem = "";
	private String requestedQuery = "";
	private int total = -1;
	private int serverPages = 1;
	private int columns = 1;
	private int rowsPerView = 1;
	private int firstCatalogRow;
	private boolean resetPosition;
	private final Map<Integer, JsonObject> pages = new LinkedHashMap<>(16, 0.75F, true);
	private Button previous;
	private Button next;
	private boolean selecting;
	private final DesignerCatalogSelection selection = new DesignerCatalogSelection();
	private final DesignerCatalogToolbar toolbar = new DesignerCatalogToolbar();
	private DesignerCatalogQuickSelection quickSelection;
	private boolean pressed;
	private boolean boxing;
	private boolean additiveBox;
	private int pressedIndex = -1;
	private double startX;
	private double startY;
	private double pointerX;
	private double pointerY;

	void add(BeeDesignerScreen screen) {
		var mc = Minecraft.getInstance();
		toolbar.add(screen, selecting, query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 350;
		}, this::actionLabel, value -> enabled(screen, value), value -> action(screen, value));
		if (DesignerPreferences.enabled("catalog_help") && screen.frameHeight >= 300 && !selecting) {
			Component help = DesignerFormScreen.tr(DesignerPreferences.enabled("tutorial_seen") ? "catalog_guide" : "catalog_welcome");
			int helpHeight = Math.max(20, DesignerFonts.body().split(help, screen.contentWidth - 12).size() * 10 + 8);
			if (screen.editorBottom() - screen.contentTop - helpHeight >= 76) {
				screen.fixedNote(help, screen.left, screen.contentTop, screen.contentWidth, helpHeight);
				screen.contentTop += helpHeight + 4;
			}
		}
		if (!DesignerDraftIndex.problem().isBlank()) {
			screen.note("error", DesignerFormScreen.statusMessage(DesignerDraftIndex.problem()));
		}
		if (selecting && screen.frameHeight >= 248) {
			screen.fixedNote(DesignerFormScreen.tr("catalog_selection_help"), screen.left, screen.contentTop, screen.contentWidth, 18);
			screen.contentTop += 20;
		}
		firstCatalogRow = screen.rows.size();
		int nameWidth = DesignerCatalogMetrics.nameWidth();
		int cardWidth = Math.max(152, Math.min(228, nameWidth + (selecting ? 42 : 62)));
		columns = Math.max(1, Math.min(8, screen.contentWidth / cardWidth));
		rowsPerView = Math.max(1, (screen.editorBottom() - screen.contentTop) / (38 + DesignerPreferences.rowGap()));
		if (total < 0) {
			screen.note("", DesignerFormScreen.tr("status.loading"));
			return;
		}
		for (int offset = 0; offset < total; offset += columns) {
			int first = offset;
			screen.rows.add(new DesignerFormScreen.Row("", (x, y, size) -> {
				int card = size / columns;
				for (int column = 0; column < columns && first + column < total; column++) {
					int index = first + column;
					int cardX = x + column * card;
					JsonObject catalog = pages.get(index / 32);
					if (catalog == null || index % 32 >= catalog.getAsJsonArray("ids").size()) {
						screen.fixedNote(DesignerFormScreen.tr("status.loading"), cardX, y, card - 4, 34);
						continue;
					}
					String id = catalog.getAsJsonArray("ids").get(index % 32).getAsString();
					JsonObject origin = catalog.getAsJsonObject("origins").getAsJsonObject(id);
					boolean deleting = DesignerDraftIndex.deletingIds().contains(id);
					screen.addRenderableWidget(new DesignerBeeButton(cardX, y, card - 4, id, DesignerDraftIndex.name(id), DesignerDraftIndex.pending(id),
							() -> {
								if (selecting) {
									choose(screen, index, id, Screen.hasShiftDown(), Screen.hasControlDown());
									return;
								}
								screen.changeDocument(() -> {
								if (id.equals(PBConstants.MYRIADCREATIONS_TYPE_STRING)) {
									screen.loadBee(id);
								} else if (DesignerDraftIndex.pending(id)) {
									DesignerProjectFiles.draft(id, screen::importDocument, message -> screen.status = message);
								} else {
									screen.loadBee(id);
								}
								});
							}).origin(origin.get("kind").getAsString(), origin.get("edited").getAsBoolean(), deleting,
							origin.get("source_pack").getAsString()).selected(selecting && selection.contains(id)).selectionMode(selecting).catalogIndex(index).actionSpace(selecting ? 0 : 26));
					if (selecting) {
						continue;
					}
					screen.menu(cardX + card - 28, y + 2, 24, "bee_actions",
							new String[]{"copy", "delete_bee", "collection_export"}, value -> !value.equals("delete_bee") || screen.canPublish, value -> {
								if (value.equals("copy")) {
									screen.changeDocument(() -> screen.copyBee(id));
								} else if (value.equals("delete_bee")) {
									screen.changeDocument(() -> mc.setScreen(new DesignerDeleteScreen(screen, id)));
								} else {
									mc.setScreen(new DesignerCollectionScreen(screen, java.util.List.of(id), screen.editingDocument()));
								}
							});

				}
			}, 38));
		}
		if (total == 0) {
			screen.note("", DesignerFormScreen.tr("empty_search"));
		}
		previous = screen.button(screen.left, screen.footerY(), 20, Component.literal("‹"), () -> screen.scrollViewport(-1));
		next = screen.button(screen.left + 24, screen.footerY(), 20, Component.literal("›"), () -> screen.scrollViewport(1));
	}

	void prepareTutorial() {
		cancelQuickSelection();
		selecting = false;
		pressed = false;
		boxing = false;
		selection.clear();
	}

	Rect2i guideTarget(BeeDesignerScreen screen, String action) {
		return toolbar.bounds(screen, action);
	}

	private Component actionLabel(String action) {
		if (action.equals("export_selection_action")) {
			return Component.translatable("designer.catalog_export", selection.size());
		}
		if (action.equals("select_bees")) {
			return DesignerFormScreen.tr(selecting ? "catalog_selection_done" : "catalog_select");
		}
		return DesignerFormScreen.tr(action);
	}

	private boolean enabled(BeeDesignerScreen screen, String action) {
		return (!action.equals("history") || screen.canPublish)
				&& (!action.equals("export_selection_action") || selection.size() > 0 && !selection.waiting() && quickSelection == null);
	}

	private void selectVisible(BeeDesignerScreen screen) {
		List<String> visible = new ArrayList<>();
		for (var child : screen.children()) {
			if (child instanceof DesignerBeeButton bee && bee.catalogIndex() >= 0
					&& bee.getY() + bee.getHeight() > screen.contentTop && bee.getY() < screen.editorBottom()) {
				visible.add(bee.beeId());
			}
		}
		if (!selection.add(visible)) {
			screen.status = "designer.catalog_selection_limit";
		}
		updateSelection(screen);
	}

	private void action(BeeDesignerScreen screen, String value) {
		var mc = Minecraft.getInstance();
		switch (value) {
			case "new" -> screen.changeDocument(() -> mc.setScreen(new NewBeeScreen(screen)));
			case "refresh" -> request();
			case "local_drafts" -> screen.changeDocument(() -> mc.setScreen(new DesignerDraftsScreen(screen)));
			case "import_project" -> screen.changeDocument(() -> mc.setScreen(new DesignerImportScreen(screen)));
			case "browse_instances" -> mc.setScreen(new DesignerInstanceScreen(screen));
			case "history" -> mc.setScreen(new DesignerHistoryScreen(screen));
			case "recycle_bin" -> screen.changeDocument(() -> mc.setScreen(new DesignerRecycleScreen(screen)));
			case "tutorial_start" -> DesignerTutorialScreen.start(screen);
			case "export_selection_action" -> mc.setScreen(new DesignerCollectionScreen(screen, List.copyOf(selection.ids()), screen.editingDocument()));
			case "quick_select" -> mc.setScreen(new DesignerChoiceScreen(screen, "quick_select",
					new String[]{"select_all", "clear_selection", "select_pack_bees", "select_editor_bees", "select_editor_new", "select_editor_modified", "select_pending_bees", "select_visible"},
					choice -> action(screen, choice)));
			case "select_all", "select_pack_bees", "select_editor_bees", "select_editor_new", "select_editor_modified", "select_pending_bees" -> {
				cancelQuickSelection();
				quickSelection = new DesignerCatalogQuickSelection(value, query);
				screen.status = "designer.catalog_selection_loading";
				updateSelection(screen);
			}
			case "select_visible" -> {
				cancelQuickSelection();
				selectVisible(screen);
			}
			case "clear_selection" -> {
				cancelQuickSelection();
				selection.clear();
				updateSelection(screen);
			}
			case "select_bees" -> {
				cancelQuickSelection();
				selecting = !selecting;
				pressed = false;
				boxing = false;
				selection.clear();
				screen.rebuild();
			}
			default -> throw new IllegalArgumentException("Unknown catalog action");
		}
	}

	void renderFooter(BeeDesignerScreen screen, net.minecraft.client.gui.GuiGraphics graphics) {
		if (total >= 0) {
			int viewPage = Math.max(0, screen.page - firstCatalogRow) / rowsPerView;
			int viewPages = Math.max(1, ((total + columns - 1) / columns + rowsPerView - 1) / rowsPerView);
			graphics.drawString(DesignerFonts.body(), (viewPage + 1) + "/" + viewPages + "  ·  " + total,
					screen.left + 52, screen.footerY() + 6, DesignerFormScreen.MUTED, false);
		}
	}

	void request() {
		cancelQuickSelection();
		searchAt = 0;
		selection.resetOrder();
		pressed = false;
		boxing = false;
		problem = "";
		pages.clear();
		total = -1;
		resetPosition = true;
		requestPage(0);
	}

	private void requestPage(int page) {
		JsonObject search = new JsonObject();
		search.addProperty("search", query);
		search.addProperty("page", page);
		JsonArray names = new JsonArray();
		String normalized = query.toLowerCase(java.util.Locale.ROOT);
		var filter = DesignerSearch.compile(query);
		if (!normalized.isBlank()) {
			for (String id : DesignerDraftIndex.pendingIds()) {
				Component name = DesignerDraftIndex.name(id);
				if (names.size() < 128 && name != null && filter.matches(id.split(":", 2)[0], filter.needsName() ? DesignerSearch.indexed(id + " " + name.getString()) : id, List.of())) {
					names.add(id);
				}
			}
			BeeReloadListener.INSTANCE.getData().keySet().stream().map(Object::toString).sorted()
					.filter(id -> filter.matches(id.split(":", 2)[0], filter.needsName() ? DesignerSearch.indexed(id + " " + BeeDesignerScreen.beeName(id).getString()) : id, List.of()))
					.filter(id -> !names.contains(new JsonPrimitive(id)))
					.limit(128 - names.size()).forEach(names::add);
		}
		search.add("names", names);
		JsonArray preferred = new JsonArray();
		DesignerDraftIndex.pendingIds().stream().limit(128).forEach(preferred::add);
		search.add("preferred", preferred);
		DesignerDraftIndex.ensureLoaded();
		requestedQuery = query;
		try {
			request = BeeDesignerClient.request("catalog", search);
			requestAt = System.currentTimeMillis();
			if (request < 0) {
				problem = "designer.error.designer_session";
			}
		} catch (IllegalStateException exception) {
			request = -1;
			problem = exception.getMessage() == null ? "designer.error.queue" : exception.getMessage();
		}
	}

	void tick(BeeDesignerScreen screen) {
		if (quickSelection != null) {
			quickSelection.tick();
			if (!quickSelection.problem().isBlank()) {
				screen.status = quickSelection.problem();
				cancelQuickSelection();
			} else if (quickSelection.complete()) {
				if (selection.replace(quickSelection.selected())) {
					screen.status = "";
				} else {
					screen.status = "designer.catalog_selection_limit";
				}
				cancelQuickSelection();
				updateSelection(screen);
			}
		}
		if (quickSelection == null && !selection.waiting() && screen.status.equals("designer.catalog_selection_loading")) {
			screen.status = "";
		}
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			request();
		}
		if (request >= 0 && System.currentTimeMillis() - requestAt >= 15000) {
			failed(request, "designer.error.bee_catalog_timeout");
		}
		if (!problem.isEmpty()) {
			screen.status = problem;
			return;
		}
		if (previous != null) {
			previous.active = screen.page > 0;
			next.active = screen.page + rowsPerView < firstCatalogRow + (total + columns - 1) / columns;
		}
		if (boxing) {
			if (pointerY < screen.contentTop + 18) {
				screen.scrollRows(-1);
			} else if (pointerY > screen.editorBottom() - 18) {
				screen.scrollRows(1);
			}
			updateBox(screen);
		}
		if (request >= 0 || total < 0 || searchAt != 0) {
			return;
		}
		int missing = selection.missing(this::idAt);
		if (missing >= 0) {
			requestPage(missing / 32);
			return;
		}
		int row = Math.max(0, screen.page - firstCatalogRow);
		int first = row * columns / 32;
		int last = Math.min(serverPages - 1, (row + rowsPerView + 2) * columns / 32);
		for (int page = first; page <= last; page++) {
			if (!pages.containsKey(page)) {
				requestPage(page);
				return;
			}
		}
	}

	boolean receive(long sequence, JsonObject data) {
		if (quickSelection != null && quickSelection.receive(sequence, data)) {
			return false;
		}
		if (sequence != request || !requestedQuery.equals(query)) {
			return false;
		}
		request = -1;
		problem = "";
		total = data.get("total").getAsInt();
		serverPages = data.get("pages").getAsInt();
		pages.put(data.get("page").getAsInt(), data);
		selection.resolve(this::idAt);
		if (pages.size() > (DesignerCatalogSelection.LIMIT + 31) / 32) {
			pages.remove(pages.keySet().iterator().next());
		}
		return true;
	}

	boolean failed(long sequence, String message) {
		if (quickSelection != null && quickSelection.failed(sequence, message)) {
			return true;
		}
		if (sequence != request) {
			return false;
		}
		request = -1;
		problem = message;
		return true;
	}

	private String idAt(int index) {
		if (index < 0 || index >= total) {
			return null;
		}
		JsonObject data = pages.get(index / 32);
		return data == null || index % 32 >= data.getAsJsonArray("ids").size()
				? null : data.getAsJsonArray("ids").get(index % 32).getAsString();
	}

	private void choose(BeeDesignerScreen screen, int index, String id, boolean range, boolean additive) {
		cancelQuickSelection();
		boolean changed;
		if (range) {
			changed = selection.range(index, additive);
			if (changed && !selection.resolve(this::idAt)) {
				screen.status = selection.waiting() ? "designer.catalog_selection_loading" : "designer.catalog_selection_limit";
			}
		} else {
			changed = selection.toggle(index, id);
		}
		if (!changed) {
			screen.status = "designer.catalog_selection_limit";
		}
		updateSelection(screen);
	}

	private void updateSelection(BeeDesignerScreen screen) {
		for (var child : screen.children()) {
			if (child instanceof DesignerBeeButton bee && bee.catalogIndex() >= 0) {
				bee.selected(selecting && selection.contains(bee.beeId()));
			}
		}
		toolbar.update("export_selection_action", actionLabel("export_selection_action"), enabled(screen, "export_selection_action"));
	}

	boolean press(BeeDesignerScreen screen, double x, double y, int button) {
		if (button != 0 || total <= 0 || x < screen.left || x >= screen.left + screen.contentWidth
				|| y < screen.contentTop || y >= screen.editorBottom()) {
			return false;
		}
		var child = screen.getChildAt(x, y).orElse(null);
		if (!(child instanceof DesignerBeeButton) && child != null) {
			return false;
		}
		DesignerBeeButton bee = child instanceof DesignerBeeButton card ? card : null;
		if (!selecting) {
			if (bee == null || !(Screen.hasControlDown() || Screen.hasShiftDown())) {
				return false;
			}
			selecting = true;
			choose(screen, bee.catalogIndex(), bee.beeId(), Screen.hasShiftDown(), Screen.hasControlDown());
			screen.rebuild();
			return true;
		}
		pressed = true;
		boxing = false;
		pressedIndex = bee == null ? -1 : bee.catalogIndex();
		startX = x;
		startY = y - screen.contentTop + screen.scrollOffset();
		pointerX = x;
		pointerY = y;
		additiveBox = Screen.hasControlDown() || Screen.hasShiftDown();
		screen.setFocused(null);
		return true;
	}

	boolean gestureInProgress() {
		return pressed || quickSelection != null;
	}

	boolean drag(BeeDesignerScreen screen, double x, double y) {
		if (!pressed) {
			return false;
		}
		pointerX = x;
		pointerY = y;
		if (!boxing && Math.hypot(x - startX, y - screen.contentTop + screen.scrollOffset() - startY) >= 4) {
			boxing = true;
			selection.beginBox(additiveBox);
		}
		if (boxing) {
			updateBox(screen);
		}
		return true;
	}

	private void updateBox(BeeDesignerScreen screen) {
		double endY = pointerY - screen.contentTop + screen.scrollOffset();
		double top = Math.min(startY, endY) - screen.rowOffset(firstCatalogRow);
		double bottom = Math.max(startY, endY) - screen.rowOffset(firstCatalogRow);
		int rowHeight = 38 + DesignerPreferences.rowGap();
		int cell = screen.contentWidth / columns;
		List<Integer> indices = new ArrayList<>();
		int first = Math.max(0, (int) Math.floor(top / rowHeight));
		int last = Math.min((total - 1) / columns, (int) Math.floor(bottom / rowHeight));
		for (int row = first; row <= last && indices.size() <= DesignerCatalogSelection.LIMIT; row++) {
			if (row * rowHeight + 34 < top) {
				continue;
			}
			for (int column = 0; column < columns && row * columns + column < total; column++) {
				int x = screen.left + column * cell;
				if (x < Math.max(startX, pointerX) && x + cell - 4 > Math.min(startX, pointerX)) {
					indices.add(row * columns + column);
				}
			}
		}
		if (!selection.box(indices, this::idAt)) {
			screen.status = "designer.catalog_selection_limit";
		}
		updateSelection(screen);
	}

	boolean release(BeeDesignerScreen screen, double x, double y, int button) {
		if (!pressed || button != 0) {
			return false;
		}
		pressed = false;
		if (boxing) {
			updateBox(screen);
			selection.endBox();
			boxing = false;
		} else if (pressedIndex >= 0 && idAt(pressedIndex) != null) {
			choose(screen, pressedIndex, idAt(pressedIndex), Screen.hasShiftDown(), Screen.hasControlDown());
		} else if (!additiveBox) {
			selection.clear();
		}
		updateSelection(screen);
		return true;
	}

	boolean key(BeeDesignerScreen screen, int key) {
		if (key == GLFW.GLFW_KEY_ESCAPE && quickSelection != null) {
			cancelQuickSelection();
			screen.status = "";
			updateSelection(screen);
			return true;
		}
		if (key == GLFW.GLFW_KEY_ESCAPE && (pressed || selecting)) {
			if (pressed) {
				selection.cancelBox();
				pressed = false;
				boxing = false;
				updateSelection(screen);
			} else {
				selecting = false;
				selection.clear();
				screen.rebuild();
			}
			return true;
		}
		if (Screen.hasControlDown() && key == GLFW.GLFW_KEY_A) {
			if (!selecting) {
				selecting = true;
				screen.rebuild();
			}
			selectVisible(screen);
			return true;
		}
		return false;
	}

	void cancelQuickSelection() {
		if (quickSelection != null) {
			quickSelection.cancel();
			quickSelection = null;
		}
	}

	void renderSelection(BeeDesignerScreen screen, GuiGraphics graphics) {
		if (!boxing) {
			return;
		}
		int offset = (int) Math.round(screen.contentOffsetX());
		int x = (int) Math.min(startX, pointerX) + offset;
		int y = (int) Math.min(startY + screen.contentTop - screen.scrollOffset(), pointerY);
		int width = Math.max(1, (int) Math.abs(startX - pointerX));
		int height = Math.max(1, (int) Math.abs(startY + screen.contentTop - screen.scrollOffset() - pointerY));
		DesignerTheme.scissor(graphics, screen.left, screen.contentTop, screen.left + screen.contentWidth, screen.editorBottom());
		int color = DesignerTheme.colors().accent();
		graphics.fill(x, y, x + width, y + height, 0x26000000 | color & 0xFFFFFF);
		graphics.renderOutline(x, y, width, height, color);
		graphics.disableScissor();
	}

	boolean resolvedStatus(String status) {
		return status.equals("designer.error.bee_catalog_timeout") || status.equals("designer.error.rate")
				|| status.equals("designer.error.queue") || status.equals("designer.status.loading");
	}

	void positioned(BeeDesignerScreen screen) {
		if (resetPosition && total >= 0) {
			screen.scrollToFraction(0);
			resetPosition = false;
		}
	}
}
