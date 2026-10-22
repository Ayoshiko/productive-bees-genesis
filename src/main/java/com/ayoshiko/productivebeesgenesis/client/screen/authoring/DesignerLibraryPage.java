package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.Button;

import java.util.LinkedHashMap;
import java.util.Map;

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
	private boolean resetPosition;
	private final Map<Integer, JsonObject> pages = new LinkedHashMap<>(16, 0.75F, true);
	private Button previous;
	private Button next;

	void add(BeeDesignerScreen screen) {
		var mc = Minecraft.getInstance();
		int toolsY = screen.contentTop;
		int searchWidth = screen.contentWidth - 120;
		screen.searchField(toolsY, searchWidth, query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 350;
		});
		screen.primaryButton(screen.left + searchWidth + 4, toolsY, 54, DesignerFormScreen.tr("new"),
				() -> screen.changeDocument(() -> mc.setScreen(new NewBeeScreen(screen))));
		screen.button(screen.left + searchWidth + 62, toolsY, 58, DesignerFormScreen.tr("refresh"), this::request);
		screen.contentTop += 24;
		int actionWidth = (screen.contentWidth - 9) / 4;
		screen.button(screen.left, screen.contentTop, actionWidth, DesignerFormScreen.tr("local_drafts"),
				() -> screen.changeDocument(() -> mc.setScreen(new DesignerDraftsScreen(screen))));
		screen.button(screen.left + actionWidth + 3, screen.contentTop, actionWidth, DesignerFormScreen.tr("import_project"),
				() -> screen.changeDocument(() -> mc.setScreen(new DesignerImportScreen(screen))));
		screen.button(screen.left + (actionWidth + 3) * 2, screen.contentTop, actionWidth, DesignerFormScreen.tr("history"),
				() -> mc.setScreen(new DesignerHistoryScreen(screen))).active = screen.canPublish;
		screen.button(screen.left + (actionWidth + 3) * 3, screen.contentTop, actionWidth, DesignerFormScreen.tr("recycle_bin"),
				() -> screen.changeDocument(() -> mc.setScreen(new DesignerRecycleScreen(screen))));
		screen.contentTop += 24;
		Component help = DesignerFormScreen.tr("catalog_guide");
		int helpHeight = Math.max(20, mc.font.split(help, screen.contentWidth - 12).size() * 10 + 8);
		screen.fixedNote(help, screen.left, screen.contentTop, screen.contentWidth, helpHeight);
		screen.contentTop += helpHeight + 4;
		if (!DesignerDraftIndex.problem().isBlank()) {
			screen.note("error", DesignerFormScreen.statusMessage(DesignerDraftIndex.problem()));
		}
		java.util.List<Integer> nameWidths = new java.util.ArrayList<>();
		for (var id : BeeReloadListener.INSTANCE.getData().keySet()) {
			Component name = DesignerDraftIndex.name(id.toString());
			nameWidths.add(mc.font.width(name == null ? BeeDesignerClient.beeName(id.toString()) : name));
		}
		nameWidths.sort(Integer::compareTo);
		int nameWidth = nameWidths.isEmpty() ? 0 : nameWidths.get((nameWidths.size() - 1) * 4 / 5);
		columns = Math.max(1, Math.min(8, screen.contentWidth / Math.max(152, Math.min(228, nameWidth + 80))));
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
							() -> screen.changeDocument(() -> {
								if (DesignerDraftIndex.pending(id)) {
									DesignerProjectFiles.draft(id, screen::importDocument, message -> screen.status = message);
								} else {
									screen.loadBee(id);
								}
							})).origin(origin.get("kind").getAsString(), origin.get("edited").getAsBoolean(), deleting,
							origin.get("source_pack").getAsString()).actionSpace(50));
					screen.addRenderableWidget(new DesignerTheme.Icon(cardX + card - 52, y + 2, DesignerTheme.Symbol.COPY,
							() -> screen.changeDocument(() -> screen.copyBee(id))));
					var remove = screen.addRenderableWidget(new DesignerTheme.Icon(cardX + card - 28, y + 2, DesignerTheme.Symbol.DELETE,
							() -> screen.changeDocument(() -> mc.setScreen(new DesignerDeleteScreen(screen, id)))));
					remove.active = screen.canPublish;
					remove.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("designer.delete_named_bee", BeeDesignerClient.beeName(id).getString())));
				}
			}, 38));
		}
		if (total == 0) {
			screen.note("", DesignerFormScreen.tr("empty_search"));
		}
		previous = screen.button(screen.left, screen.footerY(), 20, Component.literal("‹"), () -> screen.scrollViewport(-1));
		next = screen.button(screen.left + 24, screen.footerY(), 20, Component.literal("›"), () -> screen.scrollViewport(1));
	}

	void renderFooter(BeeDesignerScreen screen, net.minecraft.client.gui.GuiGraphics graphics) {
		if (total >= 0) {
			int viewPage = screen.page / rowsPerView;
			int viewPages = Math.max(1, ((total + columns - 1) / columns + rowsPerView - 1) / rowsPerView);
			graphics.drawString(Minecraft.getInstance().font, (viewPage + 1) + "/" + viewPages + "  ·  " + total,
					screen.left + 52, screen.footerY() + 6, DesignerFormScreen.MUTED, false);
		}
	}

	void request() {
		searchAt = 0;
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
		if (!normalized.isBlank()) {
			for (String id : DesignerDraftIndex.pendingIds()) {
				Component name = DesignerDraftIndex.name(id);
				if (names.size() < 128 && name != null && name.getString().toLowerCase(java.util.Locale.ROOT).contains(normalized)) {
					names.add(id);
				}
			}
			BeeReloadListener.INSTANCE.getData().keySet().stream().map(Object::toString).sorted()
					.filter(id -> BeeDesignerScreen.beeName(id).getString().toLowerCase(java.util.Locale.ROOT).contains(normalized))
					.filter(id -> !names.contains(new JsonPrimitive(id)))
					.limit(128 - names.size()).forEach(names::add);
		}
		search.add("names", names);
		JsonArray preferred = new JsonArray();
		DesignerDraftIndex.pendingIds().forEach(preferred::add);
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
			next.active = screen.page + rowsPerView < (total + columns - 1) / columns;
		}
		if (request >= 0 || total < 0 || searchAt != 0) {
			return;
		}
		int first = screen.page * columns / 32;
		int last = Math.min(serverPages - 1, (screen.page + rowsPerView + 2) * columns / 32);
		for (int page = first; page <= last; page++) {
			if (!pages.containsKey(page)) {
				requestPage(page);
				return;
			}
		}
	}

	boolean receive(long sequence, JsonObject data) {
		if (sequence != request || !requestedQuery.equals(query)) {
			return false;
		}
		request = -1;
		problem = "";
		total = data.get("total").getAsInt();
		serverPages = data.get("pages").getAsInt();
		pages.put(data.get("page").getAsInt(), data);
		if (pages.size() > 64) {
			pages.remove(pages.keySet().iterator().next());
		}
		return true;
	}

	boolean failed(long sequence, String message) {

		if (sequence != request) {
			return false;
		}
		request = -1;
		problem = message;
		return true;
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
