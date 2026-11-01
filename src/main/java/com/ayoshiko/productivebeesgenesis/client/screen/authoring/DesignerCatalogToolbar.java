package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** 工程、搜索和选择按当前任务分组；空间不足时整行进入现有滚动区。 */
final class DesignerCatalogToolbar {
	private static final String[] PROJECT_ACTIONS = {"new", "import_project", "local_drafts", "browse_instances", "catalog_manage"};
	private static final String[] SELECTION_ACTIONS = {"select_all", "quick_select", "clear_selection", "export_selection_action"};
	private static final String[] QUICK_CHOICES = {"select_pack_bees", "select_editor_bees", "select_editor_new", "select_editor_modified", "select_pending_bees", "select_visible"};
	private final Map<String, AbstractWidget> controls = new LinkedHashMap<>();
	private final Map<String, String> locations = new LinkedHashMap<>();

	void add(BeeDesignerScreen screen, boolean selecting, String query, Consumer<String> search,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		controls.clear();
		locations.clear();
		List<List<String>> projects = arrange(PROJECT_ACTIONS, screen.contentWidth, label);
		List<List<String>> selections = selecting ? arrange(SELECTION_ACTIONS, screen.contentWidth, label) : List.of();
		boolean firstUse = !selecting && screen.tutorial == null && !DesignerPreferences.enabled("tutorial_seen");
		int height = (projects.size() + selections.size() + 1 + (firstUse ? 1 : 0)) * 24 + 8;
		boolean fixed = screen.editorBottom() - screen.contentTop - height >= 60;
		int y = screen.contentTop;
		for (List<String> row : projects) {
			addRow(screen, row, y, fixed, selecting, label, enabled, action);
			y += 24;
		}
		y += 4;
		if (fixed) {
			search(screen, screen.left, y, screen.contentWidth, query, search, label, action);
		} else {
			screen.anchor("catalog_toolbar/search");
			screen.rows.add(new DesignerFormScreen.Row("", (x, rowY, width) ->
					search(screen, x, rowY, width, query, search, label, action), 20));
			for (String value : List.of("search", "refresh", "select_bees")) {
				locations.put(value, "catalog_toolbar/search");
			}
		}
		y += 28;
		for (List<String> row : selections) {
			addRow(screen, row, y, fixed, selecting, label, enabled, action);
			y += 24;
		}
		if (firstUse) {
			if (fixed) {
				introduction(screen, screen.left, y, screen.contentWidth, action);
			} else {
				screen.anchor("catalog_toolbar/introduction");
				screen.rows.add(new DesignerFormScreen.Row("", (x, rowY, width) ->
						introduction(screen, x, rowY, width, action), 20));
				locations.put("tutorial_start", "catalog_toolbar/introduction");
				locations.put("tutorial_dismiss", "catalog_toolbar/introduction");
			}
			y += 24;
		}
		if (fixed) {
			screen.contentTop = y;
		}
	}

	private List<List<String>> arrange(String[] actions, int width, Function<String, Component> label) {
		List<List<String>> rows = new ArrayList<>();
		List<String> row = new ArrayList<>();
		int used = 0;
		for (String value : actions) {
			int size = Math.min(width, width(value, label));
			int gap = row.isEmpty() ? 0 : gap(value);
			if (!row.isEmpty() && used + gap + size > width) {
				rows.add(List.copyOf(row));
				row.clear();
				used = 0;
				gap = 0;
			}
			row.add(value);
			used += gap + size;
		}
		if (!row.isEmpty()) {
			rows.add(List.copyOf(row));
		}
		return List.copyOf(rows);
	}

	private void addRow(BeeDesignerScreen screen, List<String> row, int y, boolean fixed, boolean selecting,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		if (fixed) {
			buttons(screen, row, screen.left, y, screen.contentWidth, selecting, label, enabled, action);
			return;
		}
		String location = "catalog_toolbar/" + row.get(0);
		screen.anchor(location);
		for (String value : row) {
			locations.put(value, location);
		}
		screen.rows.add(new DesignerFormScreen.Row("", (x, rowY, width) ->
				buttons(screen, row, x, rowY, width, selecting, label, enabled, action), 20));
	}

	private void buttons(BeeDesignerScreen screen, List<String> row, int x, int y, int available, boolean selecting,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		int right = x + available;
		boolean first = true;
		for (String value : row) {
			if (!first) {
				x += gap(value);
			}
			int width = Math.min(available, width(value, label));
			if (value.equals("catalog_manage") || value.equals("export_selection_action")) {
				x = Math.max(x, right - width);
			}
			Button button;
			if (value.equals("catalog_manage")) {
				button = screen.namedMenu(x, y, width, "catalog_manage", new String[]{"history", "recycle_bin"}, enabled, action);
				((DesignerTheme.Action) button).subtle();
			} else if (value.equals("quick_select")) {
				button = screen.namedMenu(x, y, width, "quick_select", QUICK_CHOICES, enabled, action);
				((DesignerTheme.Action) button).subtle();
			} else if (value.equals("export_selection_action") || value.equals("new") && !selecting) {
				button = screen.primaryButton(x, y, width, display(value, label), () -> action.accept(value));
			} else {
				button = screen.addRenderableWidget(new DesignerTheme.Action(x, y, width, display(value, label), () -> action.accept(value)));
				if (!value.equals("new") && !value.equals("import_project")) {
					((DesignerTheme.Action) button).subtle();
				}
			}
			button.active = enabled.test(value);
			Component help = switch (value) {
				case "catalog_manage" -> DesignerFormScreen.tr("catalog_manage_help");
				case "select_all" -> DesignerFormScreen.tr("catalog_select_all_help");
				case "quick_select" -> DesignerFormScreen.tr("catalog_quick_help");
				default -> label.apply(value);
			};
			button.setTooltip(Tooltip.create(help));
			controls.put(value, button);
			x += width;
			first = false;
		}
	}

	private void search(BeeDesignerScreen screen, int x, int y, int width, String query,
			Consumer<String> search, Function<String, Component> label, Consumer<String> action) {
		int selectWidth = width("select_bees", label);
		int inputWidth = Math.max(40, width - selectWidth - 34);
		var input = screen.searchField(y, inputWidth, query, search);
		input.setX(x + 4);
		input.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_guide")));
		controls.put("search", input);
		Button refresh = screen.addRenderableWidget(new DesignerTheme.Icon(x + inputWidth + 4, y,
				DesignerTheme.Symbol.REFRESH, () -> action.accept("refresh")));
		controls.put("refresh", refresh);
		Button select = screen.addRenderableWidget(new DesignerTheme.Action(x + width - selectWidth, y, selectWidth,
				label.apply("select_bees"), () -> action.accept("select_bees")).subtle());
		select.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_select_help")));
		controls.put("select_bees", select);
	}

	private void introduction(BeeDesignerScreen screen, int x, int y, int width, Consumer<String> action) {
		Component title = DesignerFormScreen.tr("catalog_tour_prompt");
		int tourWidth = Math.max(48, DesignerFonts.body().width(DesignerFormScreen.tr("catalog_tutorial")) + 16);
		int captionWidth = Math.max(20, width - tourWidth - 34);
		screen.fixedNote(title, x, y + 1, captionWidth, 18);
		Button tour = screen.addRenderableWidget(new DesignerTheme.Action(x + captionWidth + 4, y, tourWidth,
				DesignerFormScreen.tr("catalog_tutorial"), () -> action.accept("tutorial_start")).subtle());
		tour.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_welcome")));
		controls.put("tutorial_start", tour);
		Button dismiss = screen.addRenderableWidget(new DesignerTheme.Icon(x + width - 22, y, DesignerTheme.Symbol.CLOSE,
				() -> action.accept("tutorial_dismiss")));
		dismiss.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_tour_dismiss")));
		controls.put("tutorial_dismiss", dismiss);
	}

	private static int gap(String value) {
		return value.equals("local_drafts") || value.equals("catalog_manage") || value.equals("export_selection_action") ? 12 : 6;
	}

	private int width(String value, Function<String, Component> label) {
		Component text = value.equals("export_selection_action")
				? Component.translatable("designer.catalog_export", DesignerCatalogSelection.LIMIT) : display(value, label);
		boolean menu = value.equals("catalog_manage") || value.equals("quick_select");
		return Math.max(36, DesignerFonts.body().width(text) + (menu ? 24 : 16));
	}

	private Component display(String value, Function<String, Component> label) {
		return switch (value) {
			case "browse_instances" -> DesignerFormScreen.tr("catalog_instances");
			case "local_drafts" -> DesignerFormScreen.tr("catalog_drafts");
			case "catalog_manage" -> DesignerFormScreen.tr("catalog_manage");
			case "select_all" -> DesignerFormScreen.tr("catalog_select_all");
			case "clear_selection" -> DesignerFormScreen.tr("catalog_clear");
			default -> label.apply(value);
		};
	}

	void update(String action, Component label, boolean active) {
		AbstractWidget control = controls.get(action);
		if (control != null) {
			control.setMessage(label);
			control.active = active;
		}
	}

	Rect2i bounds(BeeDesignerScreen screen, String action) {
		AbstractWidget control = controls.get(action);
		Rect2i bounds = screen.controlBounds(control);
		if (bounds == null && locations.containsKey(action)) {
			screen.focusLocation(locations.get(action), false);
			bounds = screen.controlBounds(controls.get(action));
		}
		return bounds;
	}
}
