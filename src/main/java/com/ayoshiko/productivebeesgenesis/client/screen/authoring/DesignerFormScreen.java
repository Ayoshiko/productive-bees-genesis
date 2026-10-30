package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.Renderable;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import mekanism.api.chemical.ChemicalStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 独立背包、固定操作区和按可见行实例化的连续滚动表单。 */
public abstract class DesignerFormScreen extends Screen {
	static final int BACKGROUND = 0xF21C2025;
	static int FOREGROUND = 0xFFE7E9EC;
	static int MUTED = 0xFFADB5BD;
	static int ACCENT = 0xFFE6BD50;
	protected final List<Row> rows = new ArrayList<>();
	private final java.util.Set<String> collapsedGroups = new java.util.HashSet<>();
	private final java.util.Set<String> initializedGroups = new java.util.HashSet<>();
	private final java.util.Map<String, String> locationGroups = new java.util.HashMap<>();
	private String buildingGroup = "";
	protected int page;
	protected int left;
	protected int contentWidth;
	protected int contentTop = 76;
	protected String status = "";
	private final List<Label> labels = new ArrayList<>();
	private List<DesignerFormLayout.Band> bands = List.of();
	private final List<Rect2i> sections = new ArrayList<>();
	private final List<GhostTarget> targets = new ArrayList<>();
	protected int frameLeft;
	protected int frameWidth;
	protected int frameTop;
	protected int frameHeight;
	private static final DesignerWindowLayout WINDOW = new DesignerWindowLayout();
	private final DesignerInventoryTray inventory = new DesignerInventoryTray();
	private DesignerDropdown popup;
	private boolean scrollbarDragging;
	private double scrollbarOffset;
	protected int layoutWidth;
	protected int layoutHeight;
	private float uiScale = 1;
	private int restoredSource = -1;
	private int restoredWidth;
	private boolean returnButton;
	protected int titleWidth;
	private final DesignerTransition transition = new DesignerTransition();
	private boolean rebuilding;
	private static boolean completingTransition;
	private final DesignerScroll scroll = new DesignerScroll();
	private final Map<Integer, RowView> rowViews = new LinkedHashMap<>();
	private final java.util.Set<GuiEventListener> scrollingControls = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
	private int[] bandOffsets = new int[]{0};
	private int laidOutPage;
	private boolean creatingRows;
	private long lastScrollFrame;
	private String markedStatus = "";
	private int reservedStatusHeight;
	private final Map<String, Integer> locations = new LinkedHashMap<>();
	protected net.minecraft.client.gui.screens.Screen editorParent;
	private String locationScope = "";
	private int highlightedSource = -1;
	private boolean highlightedError;
	private long highlightUntil;
	private final List<RecipeHeader> recipeHeaders = new ArrayList<>();
	private final Map<String, DesignerMotionValue> tabIndicators = new LinkedHashMap<>();
	private DesignerOutputList.Transfer outputTransfer;
	private Component draggedOutput = Component.empty();
	private final java.util.NavigableSet<Integer> groupStarts = new java.util.TreeSet<>();
	private List<GroupSurface> groupSurfaces = List.of();
	private final DesignerFeedback feedback = new DesignerFeedback();

	protected DesignerFormScreen(Component title) {
		super(title);
	}

	static Component tr(String key) {
		return Component.translatable(key.startsWith("designer.") ? key : "designer." + key);
	}

	protected void begin() {
		DesignerPreferences.load();
		DesignerTheme.apply();
		font = DesignerFonts.body();
		if (!rebuilding) {
			transition.enter();
		}
		outputTransfer = null;
		clearWidgets();
		rowViews.clear();
		scrollingControls.clear();
		markedStatus = "";
		lastScrollFrame = 0;
		rows.clear();
		groupStarts.clear();
		groupSurfaces = List.of();
		locations.clear();
		locationGroups.clear();
		buildingGroup = "";
		highlightedSource = -1;
		highlightUntil = 0;
		highlightedError = false;
		locationScope = "";
		recipeHeaders.clear();
		labels.clear();
		bands = List.of();
		sections.clear();
		targets.clear();
		popup = null;
		double gameScale = minecraft.getWindow().getGuiScale();
		double pixels = Math.max(1, Math.min(3, Math.round(gameScale)));
		uiScale = (float) Math.max(0.55, Math.min(1.15, pixels / gameScale * DesignerPreferences.number("ui_scale")));
		layoutWidth = (int) Math.floor(width / uiScale);
		layoutHeight = (int) Math.floor(height / uiScale);
		int ingredientSpace = net.neoforged.fml.ModList.get().isLoaded("jei") && layoutWidth >= 480
				? Math.min(112, Math.max(60, (layoutWidth - 580) / 2)) : 0;
		var bounds = WINDOW.fit(layoutWidth, layoutHeight, ingredientSpace);
		frameWidth = bounds.width();
		frameLeft = bounds.left();
		frameTop = bounds.top();
		frameHeight = bounds.height();
		updateStatusHeight();
		left = frameLeft + 8;
		contentWidth = frameWidth - 24;
		contentTop = frameTop + 28;
		titleWidth = frameWidth - 176;
		inventory.layout(8, layoutWidth - 16, layoutHeight);
		returnButton = !(this instanceof BeeDesignerScreen);
		if (returnButton) {
			addRenderableWidget(new DesignerTheme.Icon(frameLeft + 6, frameTop + 5, DesignerTheme.Symbol.BACK, this::onClose));
			titleWidth -= 24;
		}
		addRenderableWidget(new DesignerTheme.Icon(frameLeft + frameWidth - 104, frameTop + 5, DesignerTheme.Symbol.THEME, () -> {
			DesignerTheme.toggle();
			rebuild();
		}));
		addRenderableWidget(new DesignerTheme.Icon(frameLeft + frameWidth - 54, frameTop + 5, DesignerTheme.Symbol.SETTINGS,
				() -> leaveTo(() -> minecraft.setScreen(new DesignerSettingsScreen(this)))));
		addRenderableWidget(new DesignerTheme.Pin(frameLeft + frameWidth - 79, frameTop + 5, WINDOW.pinned(), () -> {
			WINDOW.togglePin();
			rebuild();
		}));
		addRenderableWidget(new DesignerTheme.Icon(frameLeft + frameWidth - 29, frameTop + 5, DesignerTheme.Symbol.CLOSE, this::closeWindow));
	}

	private void closeWindow() {
		if (!prepareWindowClose()) {
			return;
		}
		continueWindowClose();
	}

	protected final void continueWindowClose() {
		if (this instanceof BeeDesignerScreen) {
			onClose();
		} else if (editorParent instanceof DesignerFormScreen form) {
			form.closeWindow();
		} else {
			BeeDesignerClient.closeEditorWindow();
		}
	}

	protected boolean prepareWindowClose() {
		return true;
	}

	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
	}

	protected String pendingDraftState() {
		return "";
	}

	protected final void resumePage(Runnable action) {
		leaveTo(() -> {
			minecraft.setScreen(this);
			action.run();
		});
	}

	protected Button button(int x, int y, int w, Component message, Runnable action) {
		int fitted = Math.max(10, w);
		if (y != footerY() && w > 160) {
			fitted = Math.min(w, Math.max(72, font.width(message) + 18));
		}
		if (y == footerY() && x + w >= left + contentWidth - 2) {
			x += w - fitted;
		}
		Button button = addRenderableWidget(new DesignerTheme.Action(x, y, fitted, message, action));
		if (!creatingRows && y == footerY()) {
			fitFooter(button);
		}
		if (font.width(message) > w - 10) {
			button.setTooltip(Tooltip.create(message));
		}
		return button;
	}

	@Override
	protected <T extends GuiEventListener & Renderable & NarratableEntry> T addRenderableWidget(T widget) {
		return creatingRows ? super.addWidget(widget) : super.addRenderableWidget(widget);
	}

	void slider(int x, int y, int w, double value, java.util.function.DoubleFunction<Component> label, java.util.function.DoubleConsumer changed) {
		addRenderableWidget(new DesignerSlider(x, y, w, value, label, changed));
	}

	protected Button primaryButton(int x, int y, int w, Component message, Runnable action) {
		Button button = addRenderableWidget(new DesignerTheme.Action(x, y, Math.max(20, w), message, action).primary());
		if (!creatingRows && y == footerY()) {
			fitFooter(button);
		}
		return button;
	}

	private void fitFooter(Button button) {
		button.setHeight(footerHeight());
		for (var child : children()) {
			if (child instanceof Button other && other != button && other.getY() == footerY()
					&& button.getX() < other.getRight() + 6 && button.getRight() + 6 > other.getX()) {
				if (button.getX() >= other.getX()) {
					other.setWidth(Math.max(16, button.getX() - other.getX() - 6));
				} else {
					button.setWidth(Math.max(16, other.getX() - button.getX() - 6));
				}
			}
		}
	}

	protected final void leaveTo(Runnable action) {
		if (minecraft != null && minecraft.screen != this) {
			if (minecraft.screen instanceof DesignerFormScreen current) {
				current.leaveTo(action);
			} else {
				action.run();
			}
			return;
		}
		transition.leave(() -> {
			completingTransition = true;
			try {
				action.run();
			} finally {
				completingTransition = false;
			}
		});
	}

	static void opening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
		if (completingTransition || !DesignerPreferences.animate()
				|| !(event.getCurrentScreen() instanceof DesignerFormScreen current)
				|| event.getNewScreen() != null && !(event.getNewScreen() instanceof DesignerFormScreen)
				|| event.getCurrentScreen() == event.getNewScreen()) {
			return;
		}
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (mc.player == null || mc.level == null || mc.getConnection() == null) {
			return;
		}
		Screen next = event.getNewScreen();
		event.setCanceled(true);
		current.leaveTo(() -> {
			if (mc.screen == current && mc.player != null && mc.level != null) {
				mc.setScreen(next);
			}
		});
	}

	protected final void animatePage() {
		animatePage(1);
	}

	protected final void animatePage(int direction) {
		scroll.jump(0);
		realizeRows();
		transition.enter(direction);
	}

	final void advanceTransition() {
		transition.tick();
	}

	protected final int tabs(int x, int y, int available, String current, String[] values, Predicate<String> enabled,
			Consumer<String> selected) {
		int[] cells = java.util.Arrays.stream(values).mapToInt(value -> Math.max(36, font.width(navigationLabel(value)) + 16)).toArray();
		int required = java.util.Arrays.stream(cells).sum() + (values.length - 1) * 3;
		if (values.length > 3 && DesignerPreferences.text("navigation_layout").equals("menus")) {
			dropdown(x, y, available, current, values, enabled, selected);
			return y + 24;
		}
		if (required > available) {
			if (values.length <= 3) {
				dropdown(x, y, available, current, values, enabled, selected);
				return y + 24;
			}
			int currentIndex = Math.max(0, java.util.Arrays.asList(values).indexOf(current));
			List<Integer> visible = new ArrayList<>();
			int budget = available - 31;
			int[] priority = new int[]{currentIndex, 0, currentIndex - 1, currentIndex + 1, 1, 2, 3, 4, 5, 6, 7};
			for (int index : priority) {
				if (index >= 0 && index < values.length && !visible.contains(index)
						&& cells[index] + (visible.isEmpty() ? 0 : 3) <= budget) {
					budget -= cells[index] + (visible.isEmpty() ? 0 : 3);
					visible.add(index);
				}
			}
			if (visible.isEmpty() || !visible.contains(currentIndex)) {
				dropdown(x, y, available, current, values, enabled, selected);
				return y + 24;
			}
			visible.sort(Integer::compareTo);
			String[] direct = visible.stream().map(index -> values[index]).toArray(String[]::new);
			String[] overflow = java.util.stream.IntStream.range(0, values.length).filter(index -> !visible.contains(index))
					.mapToObj(index -> values[index]).toArray(String[]::new);
			int directWidth = available - 31;
			renderTabs(x, y, directWidth, current, direct, enabled, selected);
			menu(x + directWidth + 5, y, 26, "more_pages", overflow, enabled, selected);
			return y + 24;
		}
		renderTabs(x, y, available, current, values, enabled, selected);
		return y + 24;
	}

	private void renderTabs(int x, int y, int available, String current, String[] values, Predicate<String> enabled,
			Consumer<String> selected) {
		int[] cells = java.util.Arrays.stream(values).mapToInt(value -> Math.max(36, font.width(navigationLabel(value)) + 16)).toArray();
		int spare = Math.max(0, available - java.util.Arrays.stream(cells).sum() - (values.length - 1) * 3);
		for (int index = 0; index < cells.length; index++) {
			cells[index] += spare / cells.length + (index < spare % cells.length ? 1 : 0);
		}
		int currentIndex = Math.max(0, java.util.Arrays.asList(values).indexOf(current));
		String indicatorKey = locationScope + "|" + String.join(",", values);
		DesignerMotionValue indicator = indicator(indicatorKey, currentIndex);
		addRenderableWidget(new DesignerTabIndicator(x, y, cells, currentIndex, indicator));
		int offset = 0;
		for (int index = 0; index < values.length; index++) {
			String value = values[index];
			Component label = navigationLabel(value);
			int nextIndex = index;
			addRenderableWidget(new DesignerTheme.Tab(x + offset, y, cells[index], label,
					value.equals(current), () -> {
					if (value.equals(current)) {
						return;
					}
					selected.accept(value);
					if (minecraft.screen == this && nextIndex != currentIndex) {
						transition.enter(Integer.compare(nextIndex, currentIndex));
					}
				})).active = enabled.test(value);
			offset += cells[index] + 3;
		}
	}

	protected final void selection(int x, int y, int width, String current, String[] values, Consumer<String> selected) {
		int cell = java.util.Arrays.stream(values).mapToInt(value -> font.width(selectionLabel(value)) + 16).max().orElse(40);
		if (values.length < 2 || values.length > 6 || !java.util.Arrays.asList(values).contains(current)
				|| cell * values.length + (values.length - 1) * 3 > width) {
			dropdown(x, y, width, current, values, value -> {
				if (!value.equals(current)) {
					selected.accept(value);
				}
			});
			return;
		}
		int[] cells = new int[values.length];
		java.util.Arrays.fill(cells, (width - (values.length - 1) * 3) / values.length);
		int currentIndex = Math.max(0, java.util.Arrays.asList(values).indexOf(current));
		String key = "choice|" + locationScope + "|" + x + "/" + y + "|" + String.join(",", values);
		DesignerMotionValue indicator = indicator(key, currentIndex);
		addRenderableWidget(new DesignerTabIndicator(x, y, cells, currentIndex, indicator));
		for (int index = 0; index < values.length; index++) {
			String value = values[index];
			addRenderableWidget(new DesignerTheme.Tab(x + index * (cells[index] + 3), y, cells[index], selectionLabel(value),
					value.equals(current), () -> {
						if (value.equals(current)) {
							return;
						}
						selected.accept(value);
						if (minecraft.screen == this) {
							rebuild();
						}
					})).setTooltip(Tooltip.create(option(value)));
		}
	}

	private static Component selectionLabel(String value) {
		return value.equals("random") ? tr("option.random_short") : option(value);
	}

	private DesignerMotionValue indicator(String key, int currentIndex) {
		if (!tabIndicators.containsKey(key) && tabIndicators.size() >= 64) {
			tabIndicators.remove(tabIndicators.keySet().iterator().next());
		}
		return tabIndicators.computeIfAbsent(key, ignored -> {
			DesignerMotionValue value = new DesignerMotionValue();
			value.snap(currentIndex);
			return value;
		});
	}

	private static Component navigationLabel(String value) {
		return net.minecraft.locale.Language.getInstance().has("designer.tab." + value) ? tr("tab." + value) : option(value);
	}

	protected Button beeButton(int x, int y, int w, String id, Runnable action) {
		return addRenderableWidget(new DesignerBeeButton(x, y, w, id, action));
	}

	protected Button stackButton(int x, int y, int w, ItemStack stack, String fallback, Runnable action) {
		return addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback, action).fitted());
	}

	protected EditBox searchField(int y, String query, Consumer<String> changed) {
		return searchField(y, contentWidth, query, changed);
	}

	protected EditBox searchField(int y, int w, String query, Consumer<String> changed) {
		EditBox search = new DesignerTheme.Input(font, left + 4, y + 5, w - 8, 20, tr("search"));
		search.setMaxLength(128);
		search.setHint(tr("search"));
		search.setTooltip(Tooltip.create(tr("search_pinyin_help")));
		search.setValue(query);
		search.setResponder(changed);
		return addRenderableWidget(search);
	}

	protected void text(String label, String value, Consumer<String> changed) {
		rows.add(new Row(label, (x, y, w) -> {
			EditBox field = new DesignerTheme.Input(font, x + 4, y + 5, w - 8, 20, tr(label));
			field.setMaxLength(4096);
			field.setValue(value);
			if (label.equals("name_zh") || label.equals("name_en")) {
				field.setHint(tr("inherit_name"));
			}
			field.setResponder(changed);
			addRenderableWidget(field);
		}));
	}

	protected void field(String label, JsonObject object, String key, String fallback) {
		field(label, object, key, fallback, () -> {});
	}

	protected void field(String label, JsonObject object, String key, String fallback, Runnable changed) {
		anchor(key);
		if (key.endsWith("Color")) {
			rows.add(new Row(label, (x, y, w) -> {
				String color = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback);
				addRenderableWidget(new DesignerTheme.Swatch(x, y, 34,
						() -> com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback),
						() -> minecraft.setScreen(new DesignerColorScreen(this, object, key))))
						.setTooltip(Tooltip.create(tr(label)));
				EditBox input = new DesignerTheme.Input(font, x + 44, y + 5, w - 48, 20, tr(label));
				input.setValue(color);
				input.setMaxLength(7);
				input.setResponder(value -> {
					object.addProperty(key, value);
					((DesignerTheme.Input) input).invalid(!value.matches("#[0-9a-fA-F]{6}"));
					changed.run();
				});
				addRenderableWidget(input);
			}));
			return;
		}
		text(label, com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback), value -> {
			if (value.isBlank() && fallback.isEmpty()) {
				object.remove(key);
			} else {
				object.addProperty(key, value);
			}
			changed.run();
		});
	}

	protected void number(String label, JsonObject object, String key, String fallback) {
		number(label, object, key, fallback, DesignerNumberRange.forField(label, key));
	}

	protected void number(String label, JsonObject object, String key, String fallback, DesignerNumberRange range) {
		number(label, object, key, fallback, range, value -> {});
	}

	protected void number(String label, JsonObject object, String key, String fallback, DesignerNumberRange range, Consumer<String> changed) {
		anchor(key);
		boolean compact = key.equals("min") || key.equals("max") || key.toLowerCase(java.util.Locale.ROOT).contains("chance")
				|| label.equals("fluid_amount") || label.equals("processing_time") || label.equals("machine_amount")
				|| label.equals("machine_processing_time") || label.equals("machine_eu") || label.equals("machine_priority")
				|| key.equals("size") || key.equals("pollinatedSize") || key.equals("speed") || key.equals("attack")
				|| key.equals("breedingItemCount");
		rows.add(new Row(label, (x, y, w) -> {
			int controlWidth = Math.min(w, 160);
			EditBox input = new DesignerTheme.Input(font, x + 28, y + 5, Math.max(20, controlWidth - 56), 20, tr(label));
			((DesignerTheme.Input) input).bind(object, key);
			input.setValue(com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback));
			input.setTooltip(Tooltip.create(range.description()));
			((DesignerTheme.Input) input).invalid(!range.valid(input.getValue()));
			if (!compact) {
				labels.add(new Label(range.description(), x + 4, y + 22, Math.max(1, w - 8), 1, true));
			}
			input.setResponder(value -> {
				try {
					object.addProperty(key, new java.math.BigDecimal(value));
					((DesignerTheme.Input) input).invalid(!range.valid(value));
				} catch (NumberFormatException exception) {
					object.addProperty(key, value);
					((DesignerTheme.Input) input).invalid(true);
				}
				changed.accept(value);
			});
			addRenderableWidget(input);
			for (int sign : new int[]{-1, 1}) {
				button(sign < 0 ? x : x + controlWidth - 20, y, 20, Component.literal(sign < 0 ? "−" : "+"), () -> {
					try {
						java.math.BigDecimal step = hasShiftDown() && !range.integer() ? new java.math.BigDecimal("0.1") : java.math.BigDecimal.ONE;
						var candidate = new java.math.BigDecimal(input.getValue()).add(step.multiply(java.math.BigDecimal.valueOf(sign)));
						input.setValue(range.clamp(candidate).stripTrailingZeros().toPlainString());
					} catch (NumberFormatException exception) {
						status = "designer.error.number";
					}
				});
			}
		}, compact ? 24 : 36, false, 124));
	}

	protected void toggle(String label, JsonObject object, String key, boolean fallback) {
		anchor(key);
		rows.add(new Row(label, (x, y, w) -> {
			boolean value = object.has(key) ? object.get(key).getAsBoolean() : fallback;
			DesignerTheme.Switch control = addRenderableWidget(new DesignerTheme.Switch(x, y, Math.min(w, 74), value, () -> {
				boolean current = object.has(key) ? object.get(key).getAsBoolean() : fallback;
				object.addProperty(key, !current);
			}));
			control.setTooltip(Tooltip.create(tr(label)));
		}, 20, false, 74));
	}

	protected void choice(String label, JsonObject object, String key, String fallback, String... values) {
		choice(label, object, key, fallback, () -> {}, values);
	}

	protected void choice(String label, JsonObject object, String key, String fallback, Runnable changed, String... values) {
		anchor(key);
		rows.add(new Row(label, (x, y, w) -> {
			String selected = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback);
			dropdown(x, y, w, selected, values, value -> {
				if (!value.equals(selected)) {
					object.addProperty(key, value);
					changed.run();
				}
			});
		}));
	}

	void gene(String label, JsonObject definition, String key, String... values) {
		anchor("attributes/" + key);
		rows.add(new Row(label, (x, y, w) -> {
			String[] choices = new String[values.length + 1];
			choices[0] = "random";
			System.arraycopy(values, 0, choices, 1, values.length);
			JsonObject genes = definition.has("attributes") ? definition.getAsJsonObject("attributes") : new JsonObject();
			dropdown(x, y, w, com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(genes, key, "random"), choices, value -> {
				if (value.equals("random")) {
					if (definition.has("attributes")) {
						definition.getAsJsonObject("attributes").remove(key);
					}
				} else {
					BeeDesignerDocument.object(definition, "attributes").addProperty(key, value);
				}
			});
		}));
	}

	protected Button dropdown(int x, int y, int w, String current, String[] values, Consumer<String> selected) {
		return dropdown(x, y, w, current, values, value -> true, selected);
	}

	protected Button dropdown(int x, int y, int w, String current, String[] values, Predicate<String> enabled, Consumer<String> selected) {
		Button control = addRenderableWidget(new DesignerDropdown.Control(x, y, w, option(current), anchor -> {
			openOptions(anchor, current, values, enabled, selected);
		}));
		control.setTooltip(Tooltip.create(option(current)));
		return control;
	}

	protected final Button menu(int x, int y, int width, String label, String[] values, Predicate<String> enabled,
			Consumer<String> selected) {
		Button control = addRenderableWidget(new DesignerTheme.Action(x, y, width, Component.literal("⋯"), anchor ->
				openOptions(anchor, values[0], values, enabled, selected)));
		control.setTooltip(Tooltip.create(tr(label)));
		return control;
	}

	private void openOptions(Button anchor, String current, String[] values, Predicate<String> enabled, Consumer<String> selected) {
		scroll.stop();
		popup = new DesignerDropdown(anchor.getX(), anchor.getY(), anchor.getWidth(), current, values, enabled, value -> {
			popup = null;
			selected.accept(value);
			if (minecraft.screen == this) {
				rebuild();
			}
		}, layoutWidth, layoutHeight);
	}

	static Component option(String value) {
		if (value.equals("weather_tolerance.rain") || value.equals("weather_tolerance.none") || value.equals("weather_tolerance.any")) {
			return tr("option." + value);
		}
		String gene = "productivebees.information.attribute." + value;
		if (net.minecraft.locale.Language.getInstance().has(gene)) {
			return Component.translatable(gene);
		}
		gene = "gui.productivebeesgenesis.bee_tooltip." + value;
		if (net.minecraft.locale.Language.getInstance().has(gene)) {
			return Component.translatable(gene);
		}
		String key = "designer.option." + value;
		if (net.minecraft.locale.Language.getInstance().has(key)) {
			return Component.translatable(key);
		}
		key = "designer.tab." + value;
		if (net.minecraft.locale.Language.getInstance().has(key)) {
			return Component.translatable(key);
		}
		key = "designer." + value;
		return net.minecraft.locale.Language.getInstance().has(key) ? Component.translatable(key) : Component.translatable(value);
	}

	protected void item(String label, JsonObject object, String key, String fallback) {
		anchor(key);
		rows.add(new Row(label, (x, y, w) -> {
			String id = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback);
			com.google.gson.JsonObject item = new com.google.gson.JsonObject();
			item.addProperty("id", id);
			Consumer<ItemStack> chosen = stack -> {
				if (!stack.getComponentsPatch().isEmpty()) {
					throw new IllegalArgumentException("designer.error.flower_components");
				}
				object.addProperty(key, net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			};
			virtualItem(x, y, w, DesignerStackButton.decode(item), id, chosen);
		}));
	}

	protected void bee(String label, JsonObject object, String key, String fallback) {
		anchor(key);
		rows.add(new Row(label, (x, y, w) -> {
			String id = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback);
			beeButton(x, y, Math.max(30, w - 28), id, () -> minecraft.setScreen(
					new DesignerBeePicker(this, value -> object.addProperty(key, value))));
			button(x + w - 24, y, 24, Component.literal("ID"), () -> minecraft.setScreen(
					new DesignerChoiceScreen(this, id, value -> object.addProperty(key, value))))
					.setTooltip(Tooltip.create(tr("edit_bee_id")));
			ghost(x, y, Math.max(30, w - 28), value -> value instanceof ItemStack, value -> {
				var selected = BeeDesignerEggs.bee((ItemStack) value);
				if (selected == null) {
					throw new IllegalArgumentException("designer.error.invalid_bee_egg");
				}
				object.addProperty(key, selected.toString());
			});
		}));
	}

	void blockFlower(JsonObject definition) {
		anchor("flowerBlock");
		rows.add(new Row("flower", (x, y, w) -> {
			String id = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(definition, "flowerBlock", "");
			var key = net.minecraft.resources.ResourceLocation.tryParse(id);
			var block = key == null ? net.minecraft.world.level.block.Blocks.AIR : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(key);
			addRenderableWidget(new DesignerStackButton(x, y, w, new ItemStack(block), id, () -> minecraft.setScreen(
					DesignerResourcePicker.open(this, "block", value -> BeeDesignerSelections.flower(definition, value, true, minecraft.level.registryAccess())))));
			ghost(x, y, w, value -> value instanceof ItemStack, value -> BeeDesignerSelections.flower(definition,
					selection(value), true, minecraft.level.registryAccess()));
		}));
	}

	protected void finishRows() {
		bands = DesignerFormLayout.arrange(rows, contentWidth);
		bandOffsets = new int[bands.size() + 1];
		for (int index = 0; index < bands.size(); index++) {
			bandOffsets[index + 1] = bandOffsets[index] + bands.get(index).height();
		}
		List<GroupSurface> surfaces = new ArrayList<>();
		int first = 0;
		for (int index = 1; index < bands.size(); index++) {
			if (groupStarts.contains(bands.get(index).source())) {
				surfaces.add(new GroupSurface(bandOffsets[first], bandOffsets[index]));
				first = index;
			}
		}
		if (!bands.isEmpty()) {
			surfaces.add(new GroupSurface(bandOffsets[first], bandOffsets[bands.size()]));
		}
		groupSurfaces = List.copyOf(surfaces);
		scroll.extent(bandOffsets[bands.size()], editorBottom() - contentTop);
		if (restoredSource >= 0 && restoredWidth != contentWidth) {
			page = bandAt(restoredSource);
			scroll.jump(bandOffsets[page]);
		} else if (page != laidOutPage) {
			scroll.jump(bandOffsets[Math.max(0, Math.min(page, bands.size()))]);
		}
		restoredSource = -1;
		realizeRows();
		addRenderableOnly(this::renderRows);
	}

	private int bandAtPixel(double pixel) {
		int low = 0;
		int high = bands.size();
		while (low < high) {
			int middle = (low + high) / 2;
			if (bandOffsets[middle + 1] <= pixel) {
				low = middle + 1;
			} else {
				high = middle;
			}
		}
		return Math.min(low, Math.max(0, bands.size() - 1));
	}

	private void realizeRows() {
		if (bands.isEmpty()) {
			return;
		}
		page = bandAtPixel(scroll.position());
		laidOutPage = page;
		int first = Math.max(0, page - 1);
		int last = Math.min(bands.size() - 1, bandAtPixel(scroll.position() + editorBottom() - contentTop) + 1);
		var iterator = rowViews.entrySet().iterator();
		while (iterator.hasNext()) {
			var entry = iterator.next();
			if (entry.getKey() < first || entry.getKey() > last) {
				RowView view = entry.getValue();
				for (PositionedWidget positioned : view.widgets) {
					if (getFocused() == positioned.widget) {
						setFocused(null);
					}
					removeWidget(positioned.widget);
					scrollingControls.remove(positioned.widget);
				}
				labels.removeAll(view.labels);
				sections.removeAll(view.sections);
				targets.removeAll(view.targets);
				recipeHeaders.removeAll(view.headers);
				iterator.remove();
			}
		}
		for (int index = first; index <= last; index++) {
			if (!rowViews.containsKey(index)) {
				createBand(index);
			}
		}
		int shift = (int) Math.floor(scroll.position());
		for (RowView view : rowViews.values()) {
			for (PositionedWidget positioned : view.widgets) {
				positioned.widget.setY(positioned.y - shift);
			}
		}
	}
	private void createBand(int index) {
		int childStart = children().size();
		int labelStart = labels.size();
		int sectionStart = sections.size();
		int targetStart = targets.size();
		int headerStart = recipeHeaders.size();
		var band = bands.get(index);
		int y = contentTop + bandOffsets[index];
		creatingRows = true;
		try {
			for (var cell : band.cells()) {
				placeRow(cell.row(), left + cell.x(), y, cell.width());
			}
		} finally {
			creatingRows = false;
		}
		List<PositionedWidget> widgets = new ArrayList<>();
		for (var child : children().subList(childStart, children().size())) {
			if (child instanceof AbstractWidget widget) {
				widgets.add(new PositionedWidget(widget, widget.getY()));
				scrollingControls.add(widget);
			}
		}
		rowViews.put(index, new RowView(y, band.height(), List.copyOf(widgets), List.copyOf(labels.subList(labelStart, labels.size())),
				List.copyOf(sections.subList(sectionStart, sections.size())), List.copyOf(targets.subList(targetStart, targets.size())),
				List.copyOf(recipeHeaders.subList(headerStart, recipeHeaders.size()))));
	}

	protected final boolean isLeaving() {
		return transition.leaving();
	}

	private void placeRow(Row row, int x, int y, int width) {
		int childStart = children().size();
		Component help = fieldHelp(row.label);
		if (row.label.isBlank()) {
			row.widget.create(x, y, width);
		} else if (DesignerFormLayout.stacked(row, width)) {
			labels.add(new Label(tr(row.label), x + 3, y + 2, width - 6, 1, true, help));
			row.widget.create(x, y + 16, Math.min(360, width - 4));
		} else {
			int labelWidth = DesignerFormLayout.labelWidth(row, width);
			labels.add(new Label(tr(row.label), x + 3, y + 4, labelWidth - 6, 1, true, help));
			row.widget.create(x + labelWidth, y, Math.min(360, width - labelWidth - 4));
		}
		if (help != null && DesignerPreferences.enabled("context_hints")) {
			for (var child : children().subList(childStart, children().size())) {
				if (child instanceof DesignerTheme.Input || child instanceof DesignerTheme.Switch || child instanceof DesignerDropdown.Control) {
					((AbstractWidget) child).setTooltip(Tooltip.create(tr(row.label).copy().append("\n").append(help)));
				}
			}
		}
	}

	static Component fieldHelp(String key) {
		String translation = "designer.field_help." + key;
		return net.minecraft.locale.Language.getInstance().has(translation) ? Component.translatable(translation) : null;
	}

	private int bandAt(int row) {
		for (int index = 0; index < bands.size(); index++) {
			if (bands.get(index).source() + bands.get(index).cells().size() > row) {
				return index;
			}
		}
		return Math.max(0, bands.size() - 1);
	}

	private int maxScrollRow() {
		return scroll.maximum() > 0 ? bandAtPixel(scroll.maximum()) + 1 : 0;
	}

	protected boolean usesRowScrollbar() {
		return true;
	}

	protected final double scrollFraction() {
		return scroll.maximum() <= 0 ? 0 : scroll.position() / scroll.maximum();
	}

	protected final double scrollOffset() {
		return scroll.position();
	}

	protected final int rowOffset(int source) {
		return bandOffsets[bandAt(source)];
	}

	protected final double contentOffsetX() {
		return transition.contentOffsetX();
	}

	protected final void scrollToFraction(double fraction) {
		scroll.jump(Math.max(0, Math.min(1, fraction)) * scroll.maximum());
		realizeRows();
	}

	protected final void pagination(int current, int total, boolean catalog, Runnable previous, Runnable next) {
		int x = catalog ? left + contentWidth - 112 : left;
		int y = footerY();
		button(x, y, 20, Component.literal("‹"), previous).active = current > 0;
		button(x + 24, y, 20, Component.literal("›"), next).active = current + 1 < total;
		labels.add(new Label(Component.literal((current + 1) + "/" + total), x + 48, y + 6, 58));
	}

	protected final boolean compactHeight() {
		return frameHeight < 192;
	}

	protected final int editorBottom() {
		return footerY() - statusHeight() - 6;
	}

	private int statusHeight() {
		return reservedStatusHeight;
	}

	private void updateStatusHeight() {
		if (status.isBlank() || DesignerFeedback.transientStatus(status)) {
			reservedStatusHeight = idleStatus().getString().isBlank() ? 0 : 14;
		} else {
			reservedStatusHeight = frameHeight >= 200 ? 24 : 14;
		}
	}

	protected Component idleStatus() {
		return Component.empty();
	}

	protected final int footerY() {
		return frameTop + frameHeight - footerHeight() - 3;
	}

	private int footerHeight() {
		return frameHeight < 180 ? 22 : Math.max(24, Math.min(28, frameHeight / 14));
	}

	protected void rebuild() {
		EditBox focused = getFocused() instanceof EditBox input ? input : null;
		Component message = focused == null ? Component.empty() : focused.getMessage();
		String value = focused == null ? "" : focused.getValue();
		int cursor = focused == null ? 0 : focused.getCursorPosition();
		DesignerTheme.Input.State inputState = focused instanceof DesignerTheme.Input prior ? prior.state() : null;
		restoredSource = page < bands.size() ? bands.get(page).source() : -1;
		restoredWidth = contentWidth;
		setFocused(null);
		rebuilding = true;
		try {
			init();
		} finally {
			rebuilding = false;
		}
		if (focused != null) {
			for (var child : children()) {
				boolean sameField = focused instanceof DesignerTheme.Input prior && prior.bound()
						? child instanceof DesignerTheme.Input candidate && prior.sameField(candidate)
						: child instanceof EditBox unbound && unbound.getMessage().equals(message) && unbound.getValue().equals(value);
				if (child instanceof EditBox input && sameField) {
					setFocused(input);
					input.setCursorPosition(Math.min(cursor, input.getValue().length()));
					if (input instanceof DesignerTheme.Input target && inputState != null) {
						target.restore(inputState);
					} else {
						input.setHighlightPos(input.getCursorPosition());
					}
					break;
				}
			}
		}
	}

	@Override
	protected void repositionElements() {
		WINDOW.release();
		rebuild();
	}

	@Override
	public final void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		long now = System.nanoTime();
		if (feedback.observe(status, net.minecraft.Util.getMillis())) {
			status = "";
		}
		updateStatusHeight();
		scroll.extent(bandOffsets[bandOffsets.length - 1], Math.max(1, editorBottom() - contentTop));
		if (outputTransfer != null && lastScrollFrame != 0 && mouseX / uiScale >= left && mouseX / uiScale < left + contentWidth) {
			double y = mouseY / uiScale;
			int direction = 0;
			if (y < contentTop + 18) {
				direction = -1;
			} else if (y > editorBottom() - 18) {
				direction = 1;
			}
			scroll.by(direction * Math.min(0.05, (now - lastScrollFrame) / 1_000_000_000.0) * 180);
		}
		if (lastScrollFrame != 0 && popup == null && !scrollbarDragging && !transition.leaving()) {
			scroll.advance((now - lastScrollFrame) / 1_000_000_000.0, DesignerPreferences.animate());
			realizeRows();
		}
		lastScrollFrame = now;
		graphics.flush();
		graphics.pose().pushPose();
		graphics.pose().scale(uiScale, uiScale, 1);
		try {
			renderContent(graphics, (int) (mouseX / uiScale), (int) (mouseY / uiScale), partialTick);
			DesignerTutorialScreen guide = DesignerTutorialScreen.current(this);
			if (guide != null) {
				guide.render(this, graphics, (int) (mouseX / uiScale), (int) (mouseY / uiScale));
			}
		} finally {
			graphics.flush();
			graphics.pose().popPose();
		}
	}

	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		var titleFont = DesignerFonts.title();
		graphics.drawString(titleFont, titleFont.plainSubstrByWidth(title.getString(), Math.max(1, titleWidth)),
				frameLeft + (returnButton ? 54 : 28), frameTop + 9, FOREGROUND, false);
		for (Label label : labels) {
			if (label.scrolling) {
				continue;
			}
			if (!label.text.getString().equals("designer.")) {
				var lines = label.lines;
				for (int index = 0; index < Math.min(label.maxLines, lines.size()); index++) {
					graphics.drawString(font, lines.get(index), label.x, label.y + index * 10, MUTED, false);
				}
				if (mouseX >= label.x && mouseX < label.x + label.width && mouseY >= label.y - 3 && mouseY < label.y + 12
						&& font.width(label.text) > label.width) {
					graphics.renderTooltip(font, font.split(label.text, Math.min(300, layoutWidth - 30)), mouseX, mouseY);
				}
			}
		}
		for (var widget : children()) {
			if (outputTransfer == null && widget instanceof DesignerStackButton stack && stack.isHovered() && visibleControl(stack, mouseY)) {
				stack.renderDetails(graphics, mouseX, mouseY);
			} else if (widget instanceof DesignerMachineEntryButton entry && entry.isHovered() && visibleControl(entry, mouseY)) {
				entry.renderDetails(graphics, mouseX, mouseY);
			}
		}
		inventory.render(graphics, mouseX, mouseY);
		int maximum = maxScrollRow();
		if (maximum > 0 && usesRowScrollbar()) {
			Rect2i thumb = scrollThumb();
			DesignerTheme.scrollbar(graphics, left + contentWidth + 2, contentTop, 7, editorBottom() - contentTop,
					thumb.getY(), thumb.getHeight(), scrollbarDragging || mouseX >= left + contentWidth && mouseX < left + contentWidth + 10);
		}
		Component message = status.isBlank() ? idleStatus() : statusMessage(status);
		if (!message.getString().isBlank()) {
			int statusY = footerY() - statusHeight() - 3;
			if (status.isBlank()) {
				DesignerTheme.badge(graphics, message, frameLeft + 8, statusY,
						Math.min(frameWidth - 16, font.width(message) + 26), false);
			} else {
				DesignerTheme.panel(graphics, "card", frameLeft + 6, statusY, frameWidth - 12, statusHeight());
				boolean error = DesignerFeedback.error(status);
				int color = error ? DesignerTheme.colors().error() : FOREGROUND;
				if (error) {
					DesignerTheme.errorFrame(graphics, frameLeft + 6, statusY, frameWidth - 12, statusHeight());
				}
				markInvalidFields(error);
				DesignerTheme.statusIcon(graphics, frameLeft + 13, statusY + 4,
						error ? DesignerTheme.Symbol.WARNING : DesignerTheme.Symbol.DOT, error ? color : ACCENT);
				var lines = font.split(message, frameWidth - 42);
				for (int index = 0; index < Math.min(statusHeight() >= 24 ? 2 : 1, lines.size()); index++) {
					graphics.drawString(font, lines.get(index), frameLeft + 28, statusY + 3 + index * 9, color, false);
				}
			}
			if (mouseX >= frameLeft + 6 && mouseX < frameLeft + frameWidth - 6 && mouseY >= statusY && mouseY < statusY + statusHeight()) {
				graphics.renderTooltip(font, font.split(message, Math.min(400, width - 20)), mouseX, mouseY);
			}
		}
		if (popup != null) {
			popup.render(graphics, mouseX, mouseY);
		}
		DesignerCursor.update(minecraft.getWindow().getWindow(), popup == null ? WINDOW.edgeAt(mouseX, mouseY) : 0);
		if (inventory.isDragging()) {
			for (GhostTarget target : targets) {
				Rect2i area = visibleTargetArea(target);
				boolean accepted = target.accepts.test(inventory.dragged());
				DesignerTheme.dropTarget(graphics, area.getX(), area.getY(), area.getWidth(), area.getHeight(), accepted,
						target.replacing, area.contains(mouseX, mouseY));
				if (DesignerPreferences.enabled("context_hints") && area.contains(mouseX, mouseY)) {
					String hint = "drag_reject";
					if (accepted) {
						hint = target.replacing ? "drag_replace" : "drag_add";
					}
					graphics.renderTooltip(font, tr(hint), mouseX, mouseY);
				}
			}
		}
		if (outputTransfer != null) {
			for (GhostTarget target : targets) {
				if (target.accepts.test(outputTransfer)) {
					Rect2i area = visibleTargetArea(target);
					if (area.getHeight() > 0 && area.getWidth() > 0) {
						graphics.renderOutline(area.getX(), area.getY(), area.getWidth(), area.getHeight(), ACCENT);
					}
				}
			}
			graphics.pose().pushPose();
			graphics.pose().translate(0, 0, 450);
			int previewWidth = Math.min(230, font.width(draggedOutput) + 16);
			int previewX = Math.min(mouseX + 10, layoutWidth - previewWidth - 4);
			DesignerTheme.panel(graphics, "card", previewX, mouseY + 8, previewWidth, 24);
			graphics.drawString(font, font.plainSubstrByWidth(draggedOutput.getString(), previewWidth - 12), previewX + 6, mouseY + 16, FOREGROUND, false);
			graphics.pose().popPose();
		}
		if (!WINDOW.pinned()) {
			int right = frameLeft + frameWidth - 3;
			int bottom = frameTop + frameHeight - 3;
			for (int offset = 0; offset < 3; offset++) {
				graphics.fill(right - offset * 3 - 1, bottom - 1, right - offset * 3, bottom, MUTED);
				graphics.fill(right - 1, bottom - offset * 3 - 1, right, bottom - offset * 3, MUTED);
			}
			if (DesignerPreferences.enabled("context_hints") && popup == null && mouseX >= frameLeft + 28 && mouseX < frameLeft + 28 + titleWidth
					&& mouseY >= frameTop + 4 && mouseY < frameTop + 25
					&& children().stream().noneMatch(child -> child.isMouseOver(mouseX, mouseY))) {
				graphics.renderTooltip(font, font.split(tr("window_move_help"), Math.min(300, layoutWidth - 30)), mouseX, mouseY);
			}
		}
		feedback.render(graphics, left, editorBottom(), contentWidth, net.minecraft.Util.getMillis());
	}

	private void renderRows(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (rowViews.isEmpty()) {
			return;
		}
		int revealBottom = contentTop + (int) Math.ceil((editorBottom() - contentTop) * transition.reveal());
		DesignerTheme.scissor(graphics, left, contentTop, left + contentWidth, revealBottom);
		int offset = (int) Math.floor(scroll.position());
		boolean inside = mouseX >= left && mouseX < left + contentWidth && mouseY >= contentTop && mouseY < revealBottom;
		float animatedX = transition.contentOffsetX();
		int contentMouseX = (int) (mouseX - animatedX);
		graphics.pose().pushPose();
		graphics.pose().translate(animatedX, Math.floor(scroll.position()) - scroll.position(), 0);
		try {
			for (GroupSurface group : groupSurfaces) {
				int top = contentTop + group.top - offset;
				int bottom = contentTop + group.bottom - offset - DesignerPreferences.rowGap();
				if (bottom >= contentTop && top < editorBottom()) {
					DesignerTheme.panel(graphics, "card", left, top, contentWidth, bottom - top);
				}
			}
			for (var entry : rowViews.entrySet()) {
				RowView view = entry.getValue();
				int y = view.y - offset;
				if (DesignerPreferences.enabled("alternating_rows") && entry.getKey() % 2 == 0) {
					graphics.fill(left + 3, y + 2, left + contentWidth - 3, y + view.height - 2,
							DesignerPalette.mix(DesignerTheme.colors().card(), DesignerTheme.colors().panel(), 0.08F));
				}
				for (Rect2i section : view.sections) {
					graphics.fill(section.getX(), section.getY() + section.getHeight() - offset - 1,
							section.getX() + section.getWidth(), section.getY() + section.getHeight() - offset, DesignerTheme.colors().border());
				}
				for (RecipeHeader header : view.headers) {
					if (header.y == view.y) {
						boolean removing = header.remove != null && inside && header.remove.isMouseOver(contentMouseX, mouseY);
						int color = removing ? DesignerTheme.colors().error() : DesignerTheme.colors().accent();
						graphics.fill(left + 3, y + 2, left + contentWidth - 3, y + 24,
								DesignerPalette.mix(DesignerTheme.colors().card(), color, removing ? 0.14F : 0.05F));
						graphics.fill(left + 3, y + 3, left + 5, y + 23, color);
					}
				}
				var band = bands.get(entry.getKey());
				var highlighted = band.cell(highlightedSource);
				if (highlighted != null
						&& net.minecraft.Util.getMillis() < highlightUntil) {
					int columnWidth = highlighted.width();
					int columnX = left + highlighted.x();
					int color = highlightedError ? DesignerTheme.colors().error() : ACCENT;
					float strength = Math.min(1, (highlightUntil - net.minecraft.Util.getMillis()) / 600.0F);
					DesignerGlyphs.rounded(graphics, columnX, y, columnWidth, view.height - 2, 4,
							DesignerPalette.mix(DesignerTheme.colors().panel(), color, strength * 0.045F));
					DesignerGlyphs.rounded(graphics, columnX, y + 3, 2, Math.max(6, view.height - 8), 1,
							DesignerPalette.mix(DesignerTheme.colors().border(), color, strength * 0.65F));
					for (PositionedWidget positioned : view.widgets) {
						if (positioned.widget instanceof DesignerTheme.Action action
								&& action.getX() >= columnX && action.getX() < columnX + columnWidth) {
							action.highlight(highlightedError, highlightUntil);
						}
					}
				}
				for (PositionedWidget positioned : view.widgets) {
					positioned.widget.render(graphics, inside ? contentMouseX : -1, inside ? mouseY : -1, partialTick);
				}
				for (Label label : view.labels) {
					var lines = label.lines;
					for (int index = 0; index < Math.min(label.maxLines, lines.size()); index++) {
						boolean errorLabel = highlightedError && net.minecraft.Util.getMillis() < highlightUntil
								&& highlighted != null && label.x >= left + highlighted.x()
								&& label.x < left + highlighted.x() + highlighted.width();
						graphics.drawString(font, lines.get(index), label.x, label.y - offset + index * 10,
								errorLabel ? DesignerTheme.colors().error() : MUTED, false);
					}
				}
			}
		} finally {
			graphics.flush();
			graphics.pose().popPose();
			graphics.disableScissor();
		}
		if (DesignerPreferences.animate()) {
			for (int row = 0; row < 7; row++) {
				int topAlpha = (int) (scroll.topFeedback() * (7 - row) * 9);
				int bottomAlpha = (int) (scroll.bottomFeedback() * (7 - row) * 9);
				graphics.fill(left, contentTop + row, left + contentWidth, contentTop + row + 1, topAlpha << 24 | ACCENT & 0xFFFFFF);
				graphics.fill(left, editorBottom() - row - 1, left + contentWidth, editorBottom() - row, bottomAlpha << 24 | ACCENT & 0xFFFFFF);
			}
		}
		if (inside) {
			for (RowView view : rowViews.values()) {
				for (Label label : view.labels) {
					if (contentMouseX >= label.x && contentMouseX < label.x + label.width && mouseY >= label.y - offset - 3
							&& mouseY < label.y - offset + label.maxLines * 10
							&& (font.width(label.text) > label.width || label.help != null && DesignerPreferences.enabled("context_hints"))) {
						Component hint = label.help == null ? label.text : label.text.copy().append("\n").append(label.help);
						graphics.renderTooltip(font, font.split(hint, Math.min(300, layoutWidth - 30)), mouseX, mouseY);
					}
				}
			}
		}
	}

	private boolean visibleControl(GuiEventListener control, double mouseY) {
		return !scrollingControls.contains(control)
				|| mouseY >= contentTop && mouseY < contentTop + (editorBottom() - contentTop) * transition.reveal();
	}

	@Override
	public java.util.Optional<GuiEventListener> getChildAt(double x, double y) {
		return children().stream().filter(child -> child.isMouseOver(x, y) && visibleControl(child, y))
				.map(child -> (GuiEventListener) child).findFirst();
	}

	static Component statusMessage(String status) {
		String[] parts = status.split("\\|", -1);
		if (parts.length == 4 && parts[0].equals("designer.error.field_range")) {
			return Component.translatable(parts[0], tr(parts[1]), parts[2], parts[3]);
		}
		if (parts.length == 2 && parts[0].startsWith("designer.error.field_")) {
			return Component.translatable(parts[0], tr(parts[1]));
		}
		if (parts.length == 3 && parts[0].equals("designer.error.field_reference")) {
			return Component.translatable(parts[0], tr(parts[1]), parts[2]);
		}
		if (parts.length == 2 && parts[0].equals("designer.located")) {
			return Component.translatable(parts[0], parts[1]);
		}
		if (parts.length == 2 && parts[0].startsWith("designer.error.preview_")) {
			return Component.translatable(parts[0], parts[1]);
		}
		if (parts.length == 2 && parts[0].equals("designer.error.flower_entity_tag")) {
			return Component.translatable(parts[0], parts[1]);
		}
		if (parts.length == 2 && parts[0].equals("designer.error.browser_read")) {
			return Component.translatable(parts[0], parts[1]);
		}
		if (parts.length == 4 && parts[0].equals("designer.error.setting_range")) {
			return Component.translatable(parts[0], tr(parts[1]), parts[2], parts[3]);
		}
		if (parts.length == 3 && parts[0].equals("designer.error.json_line")) {
			return Component.translatable(parts[0], parts[1], parts[2]);
		}
		if (parts.length == 2 && parts[0].equals("designer.error.setting")) {
			return Component.translatable(parts[0], tr(parts[1]));
		}
		if (parts.length == 2 && parts[0].equals("designer.error.machine_migration_field")) {
			return Component.translatable(parts[0], parts[1]);
		}
        if (parts.length == 2 && parts[0].equals("designer.error.shared_recipe_conflict")) {
            return Component.translatable(parts[0], parts[1]);
        }
		if (parts.length == 2 && parts[0].startsWith("designer.error.theme_")) {
			String label = "designer.theme.color." + parts[1];
			if (!net.minecraft.locale.Language.getInstance().has(label)) {
				label = "designer.theme.field." + parts[1];
			}
			Component field = net.minecraft.locale.Language.getInstance().has(label) ? tr(label) : Component.literal(parts[1]);
			return Component.translatable(parts[0], field);
		}
		if (parts.length == 2 && parts[0].equals("designer.error.preference_field")) {
			String label = "designer.settings_" + parts[1];
			Component field = net.minecraft.locale.Language.getInstance().has(label) ? tr(label) : Component.literal(parts[1]);
			return Component.translatable(parts[0], field);
		}
		if (status.startsWith("Invalid setting: ")) {
			return Component.translatable("designer.error.setting", tr(status.substring("Invalid setting: ".length())));
		}
		if (parts[0].startsWith("designer.error.") && parts.length > 1) {
			return Component.translatable(parts[0], (Object[]) java.util.Arrays.copyOfRange(parts, 1, parts.length));
		}
		return status.startsWith("designer.") ? tr(status) : Component.literal(status);
	}

	private void markInvalidFields(boolean error) {
		if (!error || status.equals(markedStatus)) {
			return;
		}
		markedStatus = status;
		String[] parts = status.split("\\|", -1);
		if (parts.length < 2) {
			return;
		}
		Component field = tr(parts[0].startsWith("designer.error.theme_") ? "theme.color." + parts[1] : parts[1]);
		for (var child : children()) {
			if (child instanceof DesignerTheme.Input input && input.getMessage().equals(field)) {
				input.invalid(true);
			}
		}
	}

	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, layoutWidth, layoutHeight, ((int) (DesignerPreferences.number("backdrop") * 255) << 24) | 0x080D15);
		DesignerTheme.panel(graphics, "panel", frameLeft, frameTop, frameWidth, frameHeight);
		DesignerTheme.panel(graphics, "card", frameLeft + 4, frameTop + 4, frameWidth - 8, 23);
		graphics.renderItem(new ItemStack(ModItems.BEE_DESIGNER.get()), frameLeft + (returnButton ? 32 : 8), frameTop + 8);
		graphics.fill(frameLeft + 6, footerY() - 5, frameLeft + frameWidth - 6, footerY() - 4, DesignerTheme.colors().border());

	}

	protected void note(String label, Component message) {
		int available = label.isBlank() ? contentWidth - 8 : Math.max(60, contentWidth * 60 / 100 - 8);
		int rowHeight = Math.max(20, font.split(message, Math.max(1, available)).size() * 10 + 6);
		rows.add(new Row(label, (x, y, w) -> labels.add(new Label(message, x + 4, y + 4, w - 8, Math.max(1, rowHeight / 10))), rowHeight, true));
	}

	final void fixedNote(Component message, int x, int y, int width, int height) {
		labels.add(new Label(message, x + 4, y + 4, width - 8, Math.max(1, (height - 6) / 10), creatingRows));
	}

	protected void section(String key) {
		buildingGroup = key;
		anchor(key);
		int rowHeight = compactHeight() ? 14 : 24;
		groupStarts.add(rows.size());
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, rowHeight - 2));
			labels.add(new Label(option(key).copy().withColor(FOREGROUND & 0xFFFFFF), x + 4, y + (compactHeight() ? 1 : 6), w - 8, 1, true));
		}, rowHeight));
	}

	protected final void group(String key, Runnable fields) {
		group(key, false, fields);
	}

	protected final void group(String key, boolean collapsed, Runnable fields) {
		if (!DesignerPreferences.enabled("collapse_sections")) {
			section(key);
			fields.run();
			return;
		}
		buildingGroup = key;
		anchor(key);
		String identity = locationScope + "/" + key;
		if (initializedGroups.add(identity) && collapsed) {
			collapsedGroups.add(identity);
		}
		int heading = rows.size();
		groupStarts.add(heading);
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 22));
			Component title = Component.literal(collapsedGroups.contains(identity) ? "›  " : "⌄  ").append(option(key));
			addRenderableWidget(new DesignerTheme.GroupHeader(x, y, w, title, () -> {
				if (!collapsedGroups.remove(identity)) {
					collapsedGroups.add(identity);
				}
				rebuild();
			}));
		}, 24));
		fields.run();
		if (collapsedGroups.contains(identity)) {
			rows.subList(heading + 1, rows.size()).clear();
			groupStarts.tailSet(heading + 1, true).clear();
			locationGroups.forEach((path, group) -> {
				if (group.equals(identity)) {
					locations.put(path, heading);
				}
			});
		}
	}

	void recipeHeading(Component title, String id, Runnable edit, Runnable remove) {
		anchor(id);
		groupStarts.add(rows.size());
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 24));
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 4, y + 6, Math.max(40, w - 110), 1, true));
			button(x + w - 100, y + 1, 68, tr("edit_entry"), edit).setTooltip(Tooltip.create(Component.literal(id)));
			Button delete = addRenderableWidget(new DesignerTheme.Icon(x + w - 26, y + 1, DesignerTheme.Symbol.DELETE, remove));
			delete.setTooltip(Tooltip.create(Component.translatable("designer.remove_named_recipe", title.getString()).append("\n" + id)));
			recipeHeaders.add(new RecipeHeader(y, delete));
		}, 26));
	}

	void overviewHeading(Component title, Runnable edit) {
		groupStarts.add(rows.size());
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 22));
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 7, y + 6, Math.max(40, w - 84), 1, true));
			button(x + w - 72, y + 1, 68, tr("edit_entry"), edit);
		}, 24));
	}

	void outputHeading(Component title, JsonArray outputs, JsonObject output) {
		groupStarts.add(rows.size());
		int deleteWidth = Math.max(68, Math.min(120, font.width(tr("remove_output")) + 16));
		int actionsWidth = deleteWidth + 62;
		boolean twoRows = contentWidth < actionsWidth + 180;
		int height = twoRows ? 52 : 30;
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, height - 2));
			int labelWidth = twoRows ? w - 34 : w - actionsWidth - 42;
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 32, y + 7, Math.max(10, labelWidth), 1, true));
			button(x + 3, y + 2, 24, Component.literal("⋮⋮"), () -> beginOutputDrag(outputs, output, title))
					.setTooltip(Tooltip.create(tr("output_drag_help")));
			int actionY = twoRows ? y + 27 : y + 3;
			int actionX = x + w - actionsWidth - 3;
			int index = DesignerOutputList.index(outputs, output);
			Button up = button(actionX, actionY, 26, Component.literal("↑"), () -> moveOutput(outputs, output, -1));
			up.active = index > 0;
			up.setTooltip(Tooltip.create(tr("output_move_up")));
			Button down = button(actionX + 30, actionY, 26, Component.literal("↓"), () -> moveOutput(outputs, output, 1));
			down.active = index >= 0 && index < outputs.size() - 1;
			down.setTooltip(Tooltip.create(tr("output_move_down")));
			Button delete = button(actionX + 60, actionY, deleteWidth, tr("remove_output"), () -> removeOutput(outputs, output));
			delete.setTooltip(Tooltip.create(Component.translatable("designer.remove_named_output", title.getString())));
			recipeHeaders.add(new RecipeHeader(y, delete));
			ghost(x, y, w, height, value -> value instanceof DesignerOutputList.Transfer transfer && transfer.accepts(outputs),
					value -> dropOutput((DesignerOutputList.Transfer) value, output));
		}, height, true));
	}

	private void beginOutputDrag(JsonArray outputs, JsonObject output, Component title) {
		outputTransfer = new DesignerOutputList.Transfer(outputs, output);
		draggedOutput = title.copy();
		status = "designer.output_drag_help";
	}

	private void dropOutput(DesignerOutputList.Transfer transfer, JsonObject target) {
		if (transfer.drop(transfer.outputs(), target)) {
			status = "designer.status.output_moved";
		}
	}

	private void moveOutput(JsonArray outputs, JsonObject output, int direction) {
		if (DesignerOutputList.move(outputs, output, DesignerOutputList.index(outputs, output) + direction)) {
			status = "designer.status.output_moved";
			rebuild();
		}
	}

	private void removeOutput(JsonArray outputs, JsonObject output) {
		if (DesignerOutputList.remove(outputs, output)) {
			status = "designer.status.output_removed";
			rebuild();
		}
	}

	void virtualOutputItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen, JsonArray outputs, JsonObject output) {
		DesignerStackButton button = addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback,
				() -> minecraft.setScreen(new BeeDesignerItemPicker(this, chosen))).fitted());
		button.outputActions(() -> removeOutput(outputs, output), () -> beginOutputDrag(outputs, output, button.getMessage()));
		ghost(x, y, button.getWidth(), value -> value instanceof ItemStack || value instanceof DesignerOutputList.Transfer transfer && transfer.accepts(outputs), value -> {
			if (value instanceof ItemStack item) {
				chosen.accept(item.copyWithCount(1));
			} else {
				dropOutput((DesignerOutputList.Transfer) value, output);
			}
		});
	}

	protected final void scope(String path) {
		locationScope = path;
	}

	protected final void anchor(String key) {
		String path = locationScope;
		if (!key.isEmpty()) {
			path = locationScope.isEmpty() ? key : locationScope + "/" + key;
		}
		locations.putIfAbsent(path, rows.size());
		if (!buildingGroup.isBlank()) {
			locationGroups.put(path, locationScope + "/" + buildingGroup);
		}
	}

	final boolean focusLocation(String key, boolean error) {
		String group = locationGroups.get(key);
		if (group != null && collapsedGroups.remove(group)) {
			rebuild();
		}
		Integer row = locations.get(key);
		if (row == null) {
			row = locations.get(key.substring(key.lastIndexOf('/') + 1));
		}
		if (row == null) {
			String path = key;
			while (row == null && path.contains("/")) {
				path = path.substring(0, path.lastIndexOf('/'));
				row = locations.get(path);
			}
		}
		if (row == null || bands.isEmpty()) {
			return false;
		}
		transition.settle();
		int index = 0;
		while (index + 1 < bands.size() && bands.get(index + 1).source() <= row) {
			index++;
		}
		scroll.jump(Math.max(0, bandOffsets[index] - 12));
		realizeRows();
		highlightedSource = row;
		highlightedError = error;
		highlightUntil = !error && DesignerTutorialScreen.current(this) != null ? 0 : net.minecraft.Util.getMillis() + 8000;
		if (error) {
			var band = bands.get(index);
			var cell = band.cell(row);
			int columnWidth = cell == null ? contentWidth : cell.width();
			int columnX = left + (cell == null ? 0 : cell.x());
			for (PositionedWidget positioned : rowViews.get(index).widgets) {
				if (positioned.widget instanceof DesignerTheme.Input input && input.getX() >= columnX && input.getX() < columnX + columnWidth) {
					input.invalid(true);
					setFocused(input);
				}
			}
		}
		return true;
	}

	final Rect2i locationBounds(String key) {
		Integer row = locations.get(key);
		if (row == null) {
			return null;
		}
		for (int index = 0; index < bands.size(); index++) {
			var cell = bands.get(index).cell(row);
			if (cell != null) {
				int top = Math.max(contentTop, contentTop + bandOffsets[index] - (int) scroll.position());
				int bottom = Math.min(editorBottom(), contentTop + bandOffsets[index + 1] - (int) scroll.position());
				return bottom > top ? new Rect2i(left + cell.x(), top, cell.width(), bottom - top) : null;
			}
		}
		return null;
	}

	final void expandGroup(String key) {
		initializedGroups.add("/" + key);
		if (collapsedGroups.remove("/" + key)) {
			rebuild();
		}
	}

	protected void virtualItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen) {
		virtualItem(x, y, w, stack, fallback, chosen, false);
	}

	protected void virtualItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen, boolean nestsFirst) {
		DesignerStackButton button = addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback,
				() -> minecraft.setScreen(new BeeDesignerItemPicker(this, chosen, nestsFirst))).fitted());
		ghost(x, y, button.getWidth(), 20, !stack.isEmpty(), value -> value instanceof ItemStack,
				value -> chosen.accept(((ItemStack) value).copyWithCount(1)));
	}

	void resourceView(JsonObject output, boolean fluid) {
		rows.add(new Row("preview", (x, y, w) -> addRenderableWidget(new DesignerResourceButton(x, y, w, output, fluid, () -> {}))));
	}

	protected void ghost(int x, int y, int w, Predicate<Object> accepts, Consumer<Object> chosen) {
		ghost(x, y, w, 20, accepts, chosen);
	}

	protected void ghost(int x, int y, int w, int h, Predicate<Object> accepts, Consumer<Object> chosen) {
		ghost(x, y, w, h, true, accepts, chosen);
	}

	protected void ghost(int x, int y, int w, int h, boolean replacing, Predicate<Object> accepts, Consumer<Object> chosen) {
		targets.add(new GhostTarget(new Rect2i(x, y, w, h), accepts, value -> {
			try {
				chosen.accept(value);
				if (minecraft.screen == this) {
					rebuild();
				}
			} catch (RuntimeException exception) {
				status = exception.getMessage();
			}
		}, replacing));
	}

	public record GhostTarget(Rect2i area, Predicate<Object> accepts, Consumer<Object> chosen, boolean replacing) {
		public GhostTarget(Rect2i area, Predicate<Object> accepts, Consumer<Object> chosen) {
			this(area, accepts, chosen, true);
		}
	}

	public List<GhostTarget> ghostTargets() {
		return popup == null && !transition.leaving() ? targets.stream().map(target -> new GhostTarget(screenArea(visibleTargetArea(target)), target.accepts, target.chosen, target.replacing))
				.filter(target -> target.area.getWidth() > 0 && target.area.getHeight() > 0).toList() : List.of();
	}

	private Rect2i targetArea(GhostTarget target) {
		for (RowView view : rowViews.values()) {
			if (view.targets.contains(target)) {
				int top = Math.max(contentTop, target.area.getY() - (int) Math.floor(scroll.position()));
				int bottom = Math.min(editorBottom(), target.area.getY() + target.area.getHeight() - (int) Math.floor(scroll.position()));
				return new Rect2i(target.area.getX(), top, target.area.getWidth(), Math.max(0, bottom - top));
			}
		}
		return target.area;
	}

	private Rect2i visibleTargetArea(GhostTarget target) {
		Rect2i area = targetArea(target);
		for (RowView view : rowViews.values()) {
			if (view.targets.contains(target)) {
				float movement = transition.contentOffsetX();
				int x = Math.max(left, (int) Math.floor(area.getX() + movement));
				int right = Math.min(left + contentWidth, (int) Math.ceil(area.getX() + area.getWidth() + movement));
				return new Rect2i(x, area.getY(), Math.max(0, right - x), area.getHeight());
			}
		}
		return area;
	}

	protected final boolean hasDropdown() {
		return popup != null;
	}

	protected final void renderDropdown(GuiGraphics graphics, int mouseX, int mouseY) {
		if (popup != null) {
			popup.render(graphics, mouseX, mouseY);
		}
	}

	public Rect2i editorBounds() {
		return screenArea(new Rect2i(frameLeft, frameTop, frameWidth, frameHeight));
	}

	public Rect2i inventoryBounds() {
		return screenArea(inventory.bounds());
	}

	public Rect2i inventorySlotBounds(int index) {
		return screenArea(inventory.slotBounds(index));
	}

	private Rect2i screenArea(Rect2i area) {
		int x = (int) Math.floor(area.getX() * uiScale);
		int y = (int) Math.floor(area.getY() * uiScale);
		int right = (int) Math.ceil((area.getX() + area.getWidth()) * uiScale);
		int bottom = (int) Math.ceil((area.getY() + area.getHeight()) * uiScale);
		return new Rect2i(x, y, right - x, bottom - y);
	}

	protected final boolean isInventoryDragging() {
		return inventory.isDragging();
	}

	protected final boolean isWindowDragging() {
		return WINDOW.dragging();
	}

	static JsonObject selection(Object ingredient) {
		var registries = net.minecraft.client.Minecraft.getInstance().level.registryAccess();
		if (ingredient instanceof ItemStack stack) {
			return BeeDesignerSelections.item(stack, registries);
		}
		if (ingredient instanceof FluidStack stack) {
			return BeeDesignerSelections.fluid(stack, registries);
		}
		if (ingredient instanceof ChemicalStack stack) {
			return BeeDesignerSelections.chemical(stack);
		}
		throw new IllegalArgumentException("Unsupported selection");
	}

	@Override
	public final boolean mouseClicked(double x, double y, int button) {
		DesignerTutorialScreen guide = DesignerTutorialScreen.current(this);
		if (guide != null && guide.click(this, x / uiScale, y / uiScale, button)) {
			return true;
		}
		if (transition.leaving()) {
			return true;
		}
		double localY = y / uiScale;
		transition.settle();
		scroll.stop();
		return clickContent(contentX(x / uiScale, localY), localY, button);
	}

	protected boolean clickContent(double x, double y, int button) {
		if (popup != null) {
			DesignerDropdown current = popup;
			current.click(x, y, button);
			if (current.closed()) {
				popup = null;
			}
			return true;
		}
		if (button == 0 && !inventory.isDragging()) {
			int edge = WINDOW.edgeAt(x, y);
			if (edge != 0 && WINDOW.press(edge, x, y)) {
				return true;
			}
			boolean control = children().stream().anyMatch(child -> child.isMouseOver(x, y));
			if (!control && x >= frameLeft + 4 && x < frameLeft + frameWidth - 4
					&& y >= frameTop + 4 && y < frameTop + 27 && WINDOW.press(DesignerWindowLayout.MOVE, x, y)) {
				return true;
			}
		}
		if (button == 0 && usesRowScrollbar() && maxScrollRow() > 0 && x >= left + contentWidth && x < left + contentWidth + 9
				&& y >= contentTop && y < editorBottom()) {
			scrollbarDragging = true;
			Rect2i thumb = scrollThumb();
			scrollbarOffset = thumb.contains((int) x, (int) y) ? y - thumb.getY() : thumb.getHeight() / 2.0;
			scrollTo(y);
			return true;
		}
		if (inventory.press(x, y, button)) {
			return true;
		}
		var control = getChildAt(x, y);
		if (control.isPresent() && control.get().mouseClicked(x, y, button)) {
			if (minecraft.screen != this || !children().contains(control.get())) {
				return true;
			}
			setFocused(control.get());
			if (button == 0) {
				setDragging(true);
			}
			return true;
		}
		return false;
	}

	@Override
	public final boolean mouseReleased(double x, double y, int button) {
		return releaseContent(contentX(x / uiScale, y / uiScale), y / uiScale, button);
	}

	protected boolean releaseContent(double x, double y, int button) {
		if (button == 0 && outputTransfer != null) {
			DesignerOutputList.Transfer transfer = outputTransfer;
			outputTransfer = null;
			setDragging(false);
			for (GhostTarget target : List.copyOf(targets)) {
				if (targetArea(target).contains((int) x, (int) y) && target.accepts.test(transfer)) {
					target.chosen.accept(transfer);
					return true;
				}
			}
			status = "designer.status.output_no_target";
			return true;
		}
		if (button == 0 && getFocused() instanceof DesignerStackButton stack && stack.outputPressed()) {
			setDragging(false);
			return stack.mouseReleased(x, y, button);
		}
		if (button == 0 && WINDOW.dragging()) {
			WINDOW.release();
			return true;
		}
		scrollbarDragging = false;
		if (button == 0 && inventory.isDragging()) {
			ItemStack copy = inventory.release();
			for (GhostTarget target : List.copyOf(targets)) {
				if (targetArea(target).contains((int) x, (int) y) && target.accepts.test(copy)) {
					target.chosen.accept(copy);
					return true;
				}
			}
			status = "designer.status.drag_no_target";
			return true;
		}
		return super.mouseReleased(x, y, button);
	}

	@Override
	public final boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		if (transition.leaving()) {
			return true;
		}
		double localY = y / uiScale;
		transition.settle();
		return scrollContent(contentX(x / uiScale, localY), localY, horizontal, vertical);
	}

	protected boolean scrollContent(double x, double y, double horizontal, double vertical) {
		if (popup != null) {
			popup.scroll(vertical);
			return true;
		}
		for (var child : children()) {
			if (child instanceof net.minecraft.client.gui.components.AbstractScrollWidget widget && widget.isMouseOver(x, y)) {
				return widget.mouseScrolled(x, y, horizontal, vertical);
			}
		}
		if (x >= left && x < left + contentWidth + 10 && y >= contentTop && y < editorBottom() && vertical != 0) {
			if (scroll.by(-vertical * (20 + DesignerPreferences.rowGap()))) {
				return true;
			}
		}
		return super.mouseScrolled(x, y, horizontal, vertical);
	}

	protected boolean scrollRows(int delta) {
		return scroll.by(delta * (20 + DesignerPreferences.rowGap()));
	}

	protected final void scrollViewport(int direction) {
		scroll.by(direction * Math.max(1, editorBottom() - contentTop));
	}

	private Rect2i scrollThumb() {
		int track = Math.max(1, editorBottom() - contentTop);
		int total = bandOffsets[bandOffsets.length - 1];
		int thumb = Math.min(track, Math.max(18, track * track / Math.max(track, total)));
		int y = contentTop + (int) Math.round((track - thumb) * scroll.position() / Math.max(1, scroll.maximum()));
		return new Rect2i(left + contentWidth + 2, y, 7, thumb);
	}

	@Override
	public final void mouseMoved(double x, double y) {
		super.mouseMoved(contentX(x / uiScale, y / uiScale), y / uiScale);
	}

	private void scrollTo(double y) {
		int travel = editorBottom() - contentTop - scrollThumb().getHeight();
		scroll.jump(Math.max(0, Math.min(1, (y - contentTop - scrollbarOffset) / Math.max(1, travel))) * scroll.maximum());
		realizeRows();
	}

	@Override
	public final boolean mouseDragged(double x, double y, int button, double dx, double dy) {
		if (transition.leaving()) {
			return true;
		}
		return dragContent(contentX(x / uiScale, y / uiScale), y / uiScale, button, dx / uiScale, dy / uiScale);
	}

	private double contentX(double x, double y) {
		return popup == null && x >= left && x < left + contentWidth && y >= contentTop && y < editorBottom()
				? x - transition.contentOffsetX() : x;
	}

	protected boolean dragContent(double x, double y, int button, double dx, double dy) {
		if (button == 0 && outputTransfer != null) {
			return true;
		}
		if (button == 0 && WINDOW.dragging()) {
			if (WINDOW.drag(x, y)) {
				rebuild();
			}
			return true;
		}
		if (scrollbarDragging && button == 0) {
			scrollTo(y);
			return true;
		}
		return popup != null || super.mouseDragged(x, y, button, dx, dy);
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (outputTransfer != null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
			outputTransfer = null;
			setDragging(false);
			status = "designer.status.output_no_target";
			return true;
		}
		if (transition.leaving()) {
			return true;
		}
		transition.settle();
		DesignerTutorialScreen guide = DesignerTutorialScreen.current(this);
		if (guide != null && popup == null && guide.key(this, key, modifiers)) {
			return true;
		}
		if (hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_COMMA) {
			leaveTo(() -> minecraft.setScreen(new DesignerSettingsScreen(this)));
			return true;
		}
		if (popup == null && key == org.lwjgl.glfw.GLFW.GLFW_KEY_F2) {
			DesignerTheme.toggle();
			rebuild();
			return true;
		}
		if (popup == null && hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_0) {
			WINDOW.reset();
			rebuild();
			return true;
		}
		if (popup == null && hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_F) {
			for (var child : children()) {
				if (child instanceof EditBox input && input.getMessage().equals(tr("search"))) {
					setFocused(input);
					input.moveCursorToEnd(false);
					input.setHighlightPos(0);
					return true;
				}
			}
		}
		if (popup != null) {
			DesignerDropdown current = popup;
			boolean handled = current.key(key);
			if (current.closed()) {
				popup = null;
			}
			return handled || super.keyPressed(key, scan, modifiers);
		}
		if (!(getFocused() instanceof EditBox)) {
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
				return scrollRows(key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? visibleRowCount() : -visibleRowCount());
			}
		}
		return super.keyPressed(key, scan, modifiers);
	}

	@Override
	public boolean charTyped(char character, int modifiers) {
		if (transition.leaving()) {
			return true;
		}
		if (popup != null) {
			popup.type(character);
			return true;
		}
		return super.charTyped(character, modifiers);
	}

	protected final int visibleRowCount() {
		return Math.max(1, (editorBottom() - contentTop) / 20);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void added() {
		transition.enter();
		lastScrollFrame = 0;
	}

	@Override
	public void removed() {
		transition.cancel();
		scroll.stop();
		WINDOW.release();
		inventory.release();
		DesignerCursor.clear(minecraft.getWindow().getWindow());
	}

	protected record Row(String label, WidgetFactory widget, int height, boolean wide, int minimumControlWidth, int flowWidth, boolean joinsPrevious) {
		protected Row(String label, WidgetFactory widget, int height, boolean wide, int minimumControlWidth) {
			this(label, widget, height, wide, minimumControlWidth, 0, false);
		}

		protected Row(String label, WidgetFactory widget, int height, boolean wide) {
			this(label, widget, height, wide, 108);
		}

		protected Row(String label, WidgetFactory widget) {
			this(label, widget, 20, false, 108);
		}

		protected Row(String label, WidgetFactory widget, int height) {
			this(label, widget, height, true, 108);
		}

		Row flow(int width) {
			return new Row(label, widget, height, wide, minimumControlWidth, width, joinsPrevious);
		}

		Row joined() {
			return new Row(label, widget, height, wide, minimumControlWidth, flowWidth, true);
		}
	}

	private static final class Label {
		private final Component text;
		private final int x;
		private final int y;
		private final int width;
		private final int maxLines;
		private final boolean scrolling;
		private final Component help;
		private final List<net.minecraft.util.FormattedCharSequence> lines;

		private Label(Component text, int x, int y, int width, int maxLines, boolean scrolling, Component help) {
			this.text = text.copy();
			this.x = x;
			this.y = y;
			this.width = width;
			this.maxLines = maxLines;
			this.scrolling = scrolling;
			this.help = help;
			lines = List.copyOf(DesignerFonts.body().split(this.text, Math.max(1, width)));
		}
		private Label(Component text, int x, int y, int width, int maxLines, boolean scrolling) {
			this(text, x, y, width, maxLines, scrolling, null);
		}
		private Label(Component text, int x, int y, int width) {
			this(text, x, y, width, 1, false);
		}

		private Label(Component text, int x, int y, int width, int maxLines) {
			this(text, x, y, width, maxLines, true);
		}
	}

	private record PositionedWidget(AbstractWidget widget, int y) {
	}

	private record RecipeHeader(int y, Button remove) {
	}

	private record GroupSurface(int top, int bottom) {}

	private record RowView(int y, int height, List<PositionedWidget> widgets, List<Label> labels,
			List<Rect2i> sections, List<GhostTarget> targets, List<RecipeHeader> headers) {
	}

	@FunctionalInterface
	protected interface WidgetFactory {
		void create(int x, int y, int width);
	}
}
