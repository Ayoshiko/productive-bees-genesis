package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigResultPayload;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigSyncPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import com.ayoshiko.productivebeesgenesis.network.ForgeNetwork;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 与原生模组配置共用服务端数据；本地编辑只有收到匹配请求的成功回执才视为已保存。 */
public final class DesignerFilterScreen extends DesignerFormScreen {
	private final Screen parent;
	private final List<String> types = new ArrayList<>();
	private final Set<String> selected = new HashSet<>();
	private final Set<String> adding = new HashSet<>();
	private List<String> matches = List.of();
	private String mode = "DISABLED";
	private String baseline = "";
	private String saved = "";
	private String query = "";
	private String entered = "";
	private String anchor = "";
	private boolean catalog;
	private boolean preview;
	private String shown = "filter_show_all";
	private final DesignerFilterCatalog candidates = new DesignerFilterCatalog();
	private final java.util.ArrayDeque<String> undo = new java.util.ArrayDeque<>();
	private boolean initialized;
	private boolean closeAfterSave;
	private boolean saving;
	private UUID pending;
	private long deadline;
	private long searchAt;
	private String dragging;
	private String dropTarget;
	private String dragCandidate;
	private double pressedX;
	private double pressedY;
	private String conflictSnapshot = "";

	public DesignerFilterScreen(Screen parent) {
		super(Component.translatable("productivebeesgenesis.config.myriad_creations_filter_title"));
		this.parent = parent;
		editorParent = parent;
		candidates.reset();
	}

	public static void receive(FilterConfigResultPayload response) {
		Screen page = Minecraft.getInstance().screen;
		for (int depth = 0; depth < 16 && page instanceof DesignerFormScreen form; depth++) {
			if (form instanceof DesignerFilterScreen screen) {
				screen.response(response);
				return;
			}
			page = form.editorParent;
		}
	}

	@Override
	protected void init() {
		begin();
		if (!initialized) {
			initialized = true;
			request(false);
		}
		int y = contentTop;
		String tab = catalog ? "filter_available" : "filter_list";
		if (preview) {
			tab = "filter_preview";
		}
		tabs(left, y, contentWidth, tab, new String[]{"filter_list", "filter_available", "filter_preview"}, value -> pending == null, value -> {
			catalog = value.equals("filter_available");
			preview = value.equals("filter_preview");
			page = 0;
			anchor = "";
			rebuild();
		});
		contentTop += 26;
		searchField(contentTop, Math.max(40, contentWidth - 32), query, value -> {
			query = value;
			searchAt = System.currentTimeMillis() + 200;
		});
		menu(left + contentWidth - 26, contentTop, 26, "filter_tools",
				new String[]{"filter_invert", "filter_copy", "filter_copy_selected", "filter_paste", "filter_sort",
						"filter_remove_missing", "filter_reset", "refresh"}, value -> pending == null, this::action);
		contentTop += 28;
		boolean compact = contentWidth < 560 || frameHeight < 320;
		if (compact) {
			rows.add(new Row("", (x, rowY, w) -> {
				int modeWidth = Math.min(126, (w - 82) / 2);
				dropdown(x, rowY, modeWidth, "filter." + mode, new String[]{"filter.DISABLED", "filter.BLACKLIST", "filter.WHITELIST"}, value -> {
					remember();
					mode = value.substring(7);
				});
				int filterWidth = Math.min(124, w - modeWidth - 82);
				dropdown(x + modeWidth + 6, rowY, filterWidth, shown,
						new String[]{"filter_show_all", "filter_show_eligible", "filter_show_inactive"}, value -> shown = value);
				int toolsX = x + modeWidth + filterWidth + 12;
				var selectAll = button(toolsX, rowY, 42, tr("filter_select_short"), () -> action("filter_select_all"));
				selectAll.active = !preview && !matches.isEmpty();
				selectAll.setTooltip(Tooltip.create(tr("filter_select_all").copy().append("\n")
						.append(Component.translatable("designer.filter_visible", matches.size(), (catalog ? adding : selected).size()))));
				button(toolsX + 46, rowY, 24, Component.literal("↶"), this::undo).active = !undo.isEmpty();
			}, 20, true));
		} else {
			rows.add(new Row("filter_mode", (x, rowY, w) -> selection(x, rowY, w, "filter." + mode,
				new String[]{"filter.DISABLED", "filter.BLACKLIST", "filter.WHITELIST"}, value -> {
			remember();
			mode = value.substring(7);
		}), 20, true));
		}
		note("", summary());
		if (!conflictSnapshot.isEmpty()) {
			note("", tr("filter_conflict"));
			rows.add(new Row("", (x, rowY, w) -> {
				int cell = (w - 6) / 2;
				primaryButton(x, rowY, cell, tr("filter_merge"), this::mergeConflict);
				button(x + cell + 6, rowY, cell, tr("filter_use_server"), () -> confirmReload(conflictSnapshot));
			}, 20, true));
		}
		var filter = DesignerSearch.compile(query);
		Set<String> existing = Set.copyOf(types);
		List<String> source;
		if (catalog || preview) {
			var ids = new java.util.TreeSet<>(BeeDesignerEggs.bees());
			ids.addAll(candidates.entries.keySet());
			source = ids.stream().filter(id -> preview ? candidates.eligible(id) && allowed(id) : !existing.contains(id)).toList();
		} else {
			source = List.copyOf(types);
		}
		matches = source.stream().filter(id -> shown.equals("filter_show_all")
				|| shown.equals("filter_show_eligible") == candidates.eligible(id))
				.filter(id -> filter.matches(id.split(":", 2)[0],
				filter.needsName() ? DesignerSearch.indexed(id + " " + BeeDesignerClient.beeName(id).getString()) : id, List.of())).toList();
		if (!compact) {
			rows.add(new Row("", (x, rowY, w) -> {
			int choiceWidth = Math.min(130, Math.max(100, w / 3));
			dropdown(x, rowY, choiceWidth, shown, new String[]{"filter_show_all", "filter_show_eligible", "filter_show_inactive"}, value -> shown = value);
			int remaining = w - choiceWidth - 12;
			int selectWidth = Math.min(140, Math.max(80, remaining / 2));
			button(x + choiceWidth + 6, rowY, selectWidth, tr("filter_select_all"), () -> action("filter_select_all")).active = !preview && !matches.isEmpty();
			button(x + choiceWidth + selectWidth + 12, rowY, Math.min(70, remaining - selectWidth), tr("undo"), this::undo).active = !undo.isEmpty();
		}, 20, true));
		}
		if (matches.isEmpty()) {
			String empty = catalog ? "empty_search" : "filter_empty";
			if (preview) {
				empty = "filter_preview_empty";
			}
			note("", tr(empty));
		}
		int columnWidth = contentWidth >= 560 ? (contentWidth - 12) / 2 : contentWidth;
		for (String id : matches) {
			anchor("filter/" + id);
			rows.add(new Row("", (x, rowY, w) -> {
				Set<String> selection = catalog ? adding : selected;
				int controls = catalog || preview ? 0 : 28;
				var card = addRenderableWidget(new DesignerBeeButton(x, rowY, w - controls, id, () -> {
					if (!preview) {
						select(id);
					}
				}).detail(Component.literal(id + " · ").append(tr("filter_state." + candidates.kind(id))))
						.selectionMode(!preview).selected(selection.contains(id)));
				card.active = pending == null;
				if (!catalog && !preview) {
					button(x + w - 24, rowY + 7, 24, Component.literal("×"), () -> {
						remember();
						types.remove(id);
						selected.remove(id);
						rebuild();
					}).active = pending == null;
				}
			}, 34, true).flow(columnWidth));
		}
		group("filter_rule_help", true, () -> {
			note("", tr("filter_help." + mode));
			note("", tr("filter_order_help"));
			if (!candidates.problem.isEmpty()) {
				note("", tr(candidates.problem));
			}
		});
		if (!preview) {
			group("filter_manual", true, () -> {
				text("id", entered, value -> entered = value);
				rows.add(new Row("", (x, rowY, w) -> button(x, rowY, w, tr("add"), () -> addTokens(List.of(entered)))));
			});
		}
		finishRows();
		int cell = (contentWidth - 12) / 3;
		Component primaryLabel = catalog ? Component.translatable("designer.filter_add_selected_count", adding.size()) : tr("save");
		primaryButton(left, footerY(), cell, primaryLabel, () -> {
			if (catalog) {
				addTokens(adding.stream().sorted().toList());
				adding.clear();
				catalog = false;
				rebuild();
			} else {
				request(true);
				rebuild();
			}
		}).active = pending == null && !baseline.isEmpty() && (catalog ? !adding.isEmpty() : dirty()) && conflictSnapshot.isEmpty();
		Component secondaryLabel = catalog || preview ? tr("filter_list")
				: Component.translatable("designer.filter_remove_selected_count", selected.size());
		button(left + cell + 6, footerY(), cell, secondaryLabel, () -> {
			if (catalog || preview) {
				catalog = false;
				preview = false;
				rebuild();
			} else {
				remember();
				types.removeIf(selected::contains);
				selected.clear();
				rebuild();
			}
		}).active = pending == null && (catalog || preview || !selected.isEmpty());
		button(left + (cell + 6) * 2, footerY(), cell, Component.translatable("gui.done"), this::onClose).active = pending == null;
		if (pending != null) {
			for (var child : children()) {
				if (child instanceof AbstractWidget widget) {
					widget.active = false;
				}
			}
		}
	}

	private Component summary() {
		if (!candidates.complete) {
			return tr(candidates.problem.isEmpty() ? "filter_catalog_loading" : candidates.problem);
		}
		int combs = 0;
		int blocks = 0;
		for (var entry : candidates.entries.entrySet()) {
			if (candidates.eligible(entry.getKey()) && allowed(entry.getKey())) {
				combs++;
				if (entry.getValue().equals("both")) {
					blocks++;
				}
			}
		}
		var text = Component.translatable("designer.filter_effect", combs, blocks, types.size());
		if (!candidates.enabled) {
			text.append(" · ").append(tr("filter_global_disabled"));
		}
		return text;
	}

	private boolean allowed(String id) {
		return mode.equals("DISABLED") || mode.equals("WHITELIST") == types.contains(id);
	}

	private void remember() {
		String snapshot = snapshot().toString();
		if (undo.isEmpty() || !undo.getLast().equals(snapshot)) {
			if (undo.size() >= 24) {
				undo.removeFirst();
			}
			undo.addLast(snapshot);
		}
	}

	private void undo() {
		if (!undo.isEmpty()) {
			JsonObject previous = BeeDesignerProtocol.parse(undo.removeLast());
			mode = previous.get("mode").getAsString();
			types.clear();
			previous.getAsJsonArray("types").forEach(value -> types.add(value.getAsString()));
			selected.retainAll(types);
			status = "designer.filter_undone";
			rebuild();
		}
	}

	private void mergeConflict() {
		JsonObject remote = BeeDesignerProtocol.parse(conflictSnapshot);
		JsonObject previous = BeeDesignerProtocol.parse(saved);
		List<String> old = previous.getAsJsonArray("types").asList().stream().map(value -> value.getAsString()).toList();
		List<String> merged = new ArrayList<>(remote.getAsJsonArray("types").asList().stream().map(value -> value.getAsString()).toList());
		merged.removeIf(id -> old.contains(id) && !types.contains(id));
		for (String id : types) {
			if (!old.contains(id) && !merged.contains(id)) {
				merged.add(id);
			}
		}
		if (merged.size() > 512) {
			status = "designer.filter_limit";
			return;
		}
		String localMode = mode;
		boolean changedMode = !localMode.equals(previous.get("mode").getAsString());
		JsonObject checked = remote.deepCopy();
		JsonArray combined = new JsonArray();
		merged.forEach(combined::add);
		checked.add("types", combined);
		if (changedMode) {
			checked.addProperty("mode", localMode);
		}
		try {
			BeeDesignerProtocol.checkSize(checked.toString());
		} catch (IllegalArgumentException tooLarge) {
			status = "designer.filter_config_large";
			return;
		}
		loadSnapshot(conflictSnapshot);
		remember();
		if (changedMode) {
			mode = localMode;
		}
		types.clear();
		types.addAll(merged);
		status = "designer.filter_merged";
		rebuild();
	}

	private void select(String id) {
		Set<String> selection = catalog ? adding : selected;
		int previous = matches.indexOf(anchor);
		int current = matches.indexOf(id);
		if (hasShiftDown() && previous >= 0 && current >= 0) {
			for (int index = Math.min(previous, current); index <= Math.max(previous, current); index++) {
				selection.add(matches.get(index));
			}
		} else if (!selection.remove(id)) {
			selection.add(id);
		}
		anchor = id;
		rebuild();
	}

	private void action(String action) {
		Set<String> selection = catalog ? adding : selected;
		switch (action) {
			case "filter_select_all" -> selection.addAll(matches);
			case "filter_invert" -> {
				for (String id : matches) {
					if (!selection.remove(id)) {
						selection.add(id);
					}
				}
			}
			case "filter_sort" -> {
				remember();
				types.sort(String::compareTo);
			}
			case "filter_remove_missing" -> {
				remember();
				types.removeIf(id -> candidates.kind(id).equals("missing"));
				selected.retainAll(types);
			}
			case "filter_copy_selected" -> {
				JsonArray array = new JsonArray();
				selection.stream().sorted().forEach(array::add);
				minecraft.keyboardHandler.setClipboard(array.toString());
			}
			case "filter_copy" -> minecraft.keyboardHandler.setClipboard(snapshot().get("types").toString());
			case "filter_paste" -> {
				try {
					String text = minecraft.keyboardHandler.getClipboard().strip();
					BeeDesignerProtocol.checkSize(text);
					List<String> tokens = text.startsWith("[") ? JsonParser.parseString(text).getAsJsonArray().asList().stream()
							.map(value -> value.getAsString()).toList() : List.of(text.split("[,\\s]+"));
					addTokens(tokens);
				} catch (RuntimeException invalid) {
					status = "designer.filter_invalid";
				}
			}
			case "filter_reset" -> {
				minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
					if (confirmed) {
						remember();
						types.clear();
						selected.clear();
						mode = "DISABLED";
					}
					minecraft.setScreen(this);
				}, tr("filter_reset"), tr("filter_reset_help")));
				return;
			}
			case "refresh" -> {
				if (dirty()) {
					minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
						minecraft.setScreen(this);
						if (confirmed) {
							request(false);
						}
					}, tr("refresh"), tr("filter_reload_help")));
					return;
				}
				request(false);
			}
			default -> throw new IllegalArgumentException(action);
		}
		rebuild();
	}

	private void addTokens(List<String> tokens) {
		List<String> added = new ArrayList<>(types);
		int duplicates = 0;
		for (String token : tokens) {
			String id = token.strip();
			if (!ModConfig.isValidBeeTypeEntry(id) || id.length() > 256) {
				status = "designer.filter_invalid";
				return;
			}
			if (added.contains(id)) {
				duplicates++;
			} else {
				added.add(id);
			}
		}
		if (added.size() > 512) {
			status = "designer.filter_limit";
			return;
		}
		remember();
		types.clear();
		types.addAll(added);
		status = Component.translatable("designer.filter_added", tokens.size() - duplicates, duplicates).getString();
		entered = "";
		rebuild();
	}

	private JsonObject snapshot() {
		JsonObject data = new JsonObject();
		data.addProperty("mode", mode);
		JsonArray array = new JsonArray();
		types.forEach(array::add);
		data.add("types", array);
		return data;
	}

	private boolean dirty() {
		return !saved.isEmpty() && !snapshot().toString().equals(saved);
	}

	private void request(boolean save) {
		if (pending != null) {
			return;
		}
		if (save && (!conflictSnapshot.isEmpty() || !dirty())) {
			return;
		}
		if (minecraft == null || minecraft.getConnection() == null) {
			status = "designer.filter_unavailable";
			return;
		}
		pending = UUID.randomUUID();
		saving = save;
		deadline = System.currentTimeMillis() + 8000;
		status = save ? "designer.filter_saving" : "designer.status.loading";
		try {
			if (save) {
				if (baseline.isEmpty()) {
					throw new IllegalStateException("Filter baseline must be read before saving");
				}
				JsonObject complete = snapshot();
				complete.addProperty("baseline", baseline);
				BeeDesignerProtocol.checkSize(complete.toString());
			}
			ForgeNetwork.sendToServer(new FilterConfigSyncPayload(mode, save ? types : List.of(), pending, save ? baseline : "", save));
		} catch (RuntimeException failure) {
			pending = null;
			status = "designer.filter_invalid";
		}
	}

	private void response(FilterConfigResultPayload response) {
		if (candidates.receive(response)) {
			if (minecraft.screen == this) {
				rebuild();
			}
			return;
		}
		if (!response.request().equals(pending)) {
			return;
		}
		pending = null;
		status = response.message();
		if (response.success()) {
			loadSnapshot(response.snapshot());
			if (closeAfterSave && saving) {
				minecraft.setScreen(parent);
				return;
			}
		} else if (response.message().equals("designer.filter_conflict")) {
			conflictSnapshot = response.snapshot();
		} else if (response.message().equals("designer.filter_save_unknown")) {
			baseline = "";
		}
		closeAfterSave = false;
		rebuild();
	}

	private void loadSnapshot(String raw) {
		JsonObject data = BeeDesignerProtocol.parse(raw);
		mode = data.get("mode").getAsString();
		types.clear();
		data.getAsJsonArray("types").forEach(value -> types.add(value.getAsString()));
		baseline = data.get("baseline").getAsString();
		saved = snapshot().toString();
		conflictSnapshot = "";
		undo.clear();
		selected.retainAll(types);
		candidates.reset();
	}

	private void confirmReload(String snapshot) {
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			if (confirmed) {
				loadSnapshot(snapshot);
				status = "designer.filter_loaded";
			}
			minecraft.setScreen(this);
		}, tr("filter_use_server"), tr("filter_reload_help")));
	}

	@Override
	public void tick() {
		super.tick();
		if (minecraft.getConnection() != null && candidates.tick()) {
			rebuild();
		}
		if (pending != null && System.currentTimeMillis() >= deadline) {
			pending = null;
			status = saving ? "designer.filter_save_unknown" : "designer.filter_load_timeout";
			if (saving) {
				baseline = "";
			}
			closeAfterSave = false;
			rebuild();
		}
		if (searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			page = 0;
			rebuild();
		}
	}

	@Override
	protected Component idleStatus() {
		return tr(dirty() ? "filter_unsaved" : "filter_current");
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (pending != null) {
			return true;
		}
		if (dragging != null && key == GLFW.GLFW_KEY_ESCAPE) {
			dragging = null;
			dropTarget = null;
			return true;
		}
		if ((hasControlDown() || (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) && key == GLFW.GLFW_KEY_Z) {
			undo();
			return true;
		}
		if (hasControlDown() && key == GLFW.GLFW_KEY_S) {
			request(true);
			rebuild();
			return true;
		}
		if (!(getFocused() instanceof EditBox) && key == GLFW.GLFW_KEY_DELETE && !catalog && !preview) {
			remember();
			types.removeIf(selected::contains);
			selected.clear();
			rebuild();
			return true;
		}
		return super.keyContent(key, scan, modifiers);
	}

	@Override
	protected boolean clickContent(double x, double y, int button) {
		if (pending == null && !catalog && !preview && button == 0) {
			dragCandidate = children().stream().filter(DesignerBeeButton.class::isInstance).map(DesignerBeeButton.class::cast)
					.filter(bee -> bee.isMouseOver(x, y)).map(DesignerBeeButton::beeId).findFirst().orElse(null);
			pressedX = x;
			pressedY = y;
		}
		return super.clickContent(x, y, button);
	}

	@Override
	protected boolean dragContent(double x, double y, int button, double dx, double dy) {
		if (!catalog && !preview && pending == null && button == 0) {
			if (dragging == null && dragCandidate != null && Math.hypot(x - pressedX, y - pressedY) >= 4) {
				dragging = dragCandidate;
			}
			if (dragging != null) {
				dropTarget = children().stream().filter(DesignerBeeButton.class::isInstance).map(DesignerBeeButton.class::cast)
						.filter(bee -> bee.isMouseOver(x, y)).map(DesignerBeeButton::beeId).findFirst().orElse(null);
				return true;
			}
		}
		return super.dragContent(x, y, button, dx, dy);
	}

	@Override
	protected boolean releaseContent(double x, double y, int button) {
		dragCandidate = null;
		if (dragging != null && button == 0) {
			if (dropTarget != null && !dragging.equals(dropTarget)) {
				remember();
				types.remove(dragging);
				types.add(types.indexOf(dropTarget), dragging);
			}
			dragging = null;
			dropTarget = null;
			rebuild();
			return true;
		}
		return super.releaseContent(x, y, button);
	}

	@Override
	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderContent(graphics, mouseX, mouseY, partialTick);
		if (dragging != null) {
			graphics.renderTooltip(font, BeeDesignerClient.beeName(dragging), mouseX, mouseY);
			if (dropTarget != null) {
				var area = locationBounds("filter/" + dropTarget);
				if (area != null) {
					graphics.fill(area.getX(), area.getY(), area.getX() + area.getWidth(), area.getY() + 2, ACCENT);
				}
			}
		}
	}

	@Override
	public void onClose() {
		if (pending != null) {
			return;
		}
		if (dirty()) {
			minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("filter_close_help"), () -> {
				minecraft.setScreen(this);
				closeAfterSave = true;
				request(true);
				rebuild();
			}, () -> minecraft.setScreen(parent), () -> minecraft.setScreen(this)));
		} else {
			minecraft.setScreen(parent);
		}
	}

	@Override
	protected boolean prepareWindowClose() {
		onClose();
		return false;
	}
}
