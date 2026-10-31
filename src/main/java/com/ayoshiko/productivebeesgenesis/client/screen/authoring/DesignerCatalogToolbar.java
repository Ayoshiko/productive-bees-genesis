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

/** 工程入口与搜索、多选分别排布；空间不足时整行进入现有滚动区。 */
final class DesignerCatalogToolbar {
	private static final String[] PROJECT_ACTIONS = {"new", "import_project", "local_drafts", "browse_instances", "tutorial_start", "catalog_manage"};
	private static final String[] SELECTION_ACTIONS = {"export_selection_action", "quick_select", "select_all", "clear_selection", "select_bees"};
	private final Map<String, AbstractWidget> controls = new LinkedHashMap<>();
	private final Map<String, String> locations = new LinkedHashMap<>();

	void add(BeeDesignerScreen screen, boolean selecting, String query, Consumer<String> search,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		controls.clear();
		locations.clear();
		List<List<String>> projects = arrange(PROJECT_ACTIONS, screen.contentWidth, label);
		List<List<String>> selections = selecting ? arrange(SELECTION_ACTIONS, screen.contentWidth, label) : List.of();
		int height = (projects.size() + selections.size() + 1) * 24 + 4;
		boolean fixed = screen.editorBottom() - screen.contentTop - height >= 60;
		int y = screen.contentTop;
		for (List<String> row : projects) {
			addRow(screen, row, y, fixed, label, enabled, action);
			y += 24;
		}
		y += 4;
		if (fixed) {
			search(screen, screen.left, y, screen.contentWidth, selecting, query, search, label, action);
		} else {
			screen.anchor("catalog_toolbar/search");
			screen.rows.add(new DesignerFormScreen.Row("", (x, rowY, width) ->
					search(screen, x, rowY, width, selecting, query, search, label, action), 20));
			locations.put("search", "catalog_toolbar/search");
		}
		y += 24;
		for (List<String> row : selections) {
			addRow(screen, row, y, fixed, label, enabled, action);
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

	private void addRow(BeeDesignerScreen screen, List<String> row, int y, boolean fixed,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		if (fixed) {
			buttons(screen, row, screen.left, y, screen.contentWidth, label, enabled, action);
			return;
		}
		String location = "catalog_toolbar/" + row.getFirst();
		screen.anchor(location);
		for (String value : row) {
			locations.put(value, location);
		}
		screen.rows.add(new DesignerFormScreen.Row("", (x, rowY, width) ->
				buttons(screen, row, x, rowY, width, label, enabled, action), 20));
	}

	private void buttons(BeeDesignerScreen screen, List<String> row, int x, int y, int available,
			Function<String, Component> label, Predicate<String> enabled, Consumer<String> action) {
		boolean first = true;
		for (String value : row) {
			if (!first) {
				x += gap(value);
			}
			int width = Math.min(available, width(value, label));
			Button button;
			if (value.equals("catalog_manage")) {
				button = screen.namedMenu(x, y, width, "catalog_manage", new String[]{"history", "recycle_bin", "refresh"}, enabled, action);
			} else if (value.equals("new") || value.equals("export_selection_action")) {
				button = screen.primaryButton(x, y, width, display(value, label), () -> action.accept(value));
			} else {
				button = screen.button(x, y, width, display(value, label), () -> action.accept(value));
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

	private void search(BeeDesignerScreen screen, int x, int y, int width, boolean selecting, String query,
			Consumer<String> search, Function<String, Component> label, Consumer<String> action) {
		int selectWidth = selecting ? 0 : Math.min(width / 3, width("select_bees", label));
		var input = screen.searchField(y, width - (selecting ? 0 : selectWidth + 4), query, search);
		input.setX(x + 4);
		input.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_guide")));
		controls.put("search", input);
		if (!selecting) {
			Button select = screen.button(x + width - selectWidth, y, selectWidth, label.apply("select_bees"), () -> action.accept("select_bees"));
			select.setTooltip(Tooltip.create(DesignerFormScreen.tr("catalog_select_help")));
			controls.put("select_bees", select);
			if (locations.containsKey("search")) {
				locations.put("select_bees", locations.get("search"));
			}
		}
	}

	private static int gap(String value) {
		return value.equals("tutorial_start") ? 12 : 4;
	}

	private int width(String value, Function<String, Component> label) {
		Component text = value.equals("export_selection_action")
				? Component.translatable("designer.catalog_export", DesignerCatalogSelection.LIMIT) : display(value, label);
		return Math.max(40, DesignerFonts.body().width(text) + (value.equals("catalog_manage") ? 24 : 16));
	}

	private Component display(String value, Function<String, Component> label) {
		return switch (value) {
			case "tutorial_start" -> DesignerFormScreen.tr("catalog_tutorial");
			case "browse_instances" -> DesignerFormScreen.tr("catalog_instances");
			case "local_drafts" -> DesignerFormScreen.tr("catalog_drafts");
			case "catalog_manage" -> DesignerFormScreen.tr("catalog_manage");
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
