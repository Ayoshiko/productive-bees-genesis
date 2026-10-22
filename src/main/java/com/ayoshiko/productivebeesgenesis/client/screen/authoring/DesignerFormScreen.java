package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;

import com.google.gson.JsonObject;
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
	private int[] bandOffsets = new int[]{0};
	private int laidOutPage;
	private boolean creatingRows;
	private long lastScrollFrame;
	private String markedStatus = "";
	private final Map<String, Integer> locations = new LinkedHashMap<>();
	protected net.minecraft.client.gui.screens.Screen editorParent;
	private String locationScope = "";
	private int highlightedSource = -1;
	private boolean highlightedError;
	private long highlightUntil;
	private final List<RecipeHeader> recipeHeaders = new ArrayList<>();
	private final Map<String, DesignerMotionValue> tabIndicators = new LinkedHashMap<>();

	protected DesignerFormScreen(Component title) {
		super(title);
	}

	static Component tr(String key) {
		return Component.translatable(key.startsWith("designer.") ? key : "designer." + key);
	}

	protected void begin() {
		DesignerPreferences.load();
		DesignerTheme.apply();
		if (!rebuilding) {
			transition.enter();
		}
		clearWidgets();
		rowViews.clear();
		markedStatus = "";
		lastScrollFrame = 0;
		rows.clear();
		locations.clear();
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
		int cell = Math.max(1, (available - (values.length - 1) * 3) / values.length);
		int currentIndex = Math.max(0, java.util.Arrays.asList(values).indexOf(current));
		String indicatorKey = locationScope + "|" + String.join(",", values);
		if (!tabIndicators.containsKey(indicatorKey) && tabIndicators.size() >= 64) {
			tabIndicators.remove(tabIndicators.keySet().iterator().next());
		}
		DesignerMotionValue indicator = tabIndicators.computeIfAbsent(indicatorKey, key -> {
			DesignerMotionValue value = new DesignerMotionValue();
			value.snap(currentIndex);
			return value;
		});
		addRenderableWidget(new DesignerTabIndicator(x, y, cell, values.length, currentIndex, indicator));
		for (int index = 0; index < values.length; index++) {
			String value = values[index];
			Component label = tr("tab." + value);
			if (!net.minecraft.locale.Language.getInstance().has("designer.tab." + value)) {
				label = option(value);
			}
			int nextIndex = index;
			addRenderableWidget(new DesignerTheme.Tab(x + index * (cell + 3), y, cell, label,
					value.equals(current), () -> {
					selected.accept(value);
					if (minecraft.screen == this && nextIndex != currentIndex) {
						transition.enter(Integer.compare(nextIndex, currentIndex));
					}
				})).active = enabled.test(value);
		}
		return y + 24;
	}

	protected Button beeButton(int x, int y, int w, String id, Runnable action) {
		return addRenderableWidget(new DesignerBeeButton(x, y, w, id, action));
	}

	protected Button stackButton(int x, int y, int w, ItemStack stack, String fallback, Runnable action) {
		return addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback, action));
	}

	protected EditBox searchField(int y, String query, Consumer<String> changed) {
		return searchField(y, contentWidth, query, changed);
	}

	protected EditBox searchField(int y, int w, String query, Consumer<String> changed) {
		EditBox search = new DesignerTheme.Input(font, left + 4, y + 5, w - 8, 20, tr("search"));
		search.setMaxLength(128);
		search.setHint(tr("search"));
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
		rows.add(new Row(label, (x, y, w) -> {
			int controlWidth = Math.min(w, 160);
			EditBox input = new DesignerTheme.Input(font, x + 28, y + 5, Math.max(20, controlWidth - 56), 20, tr(label));
			input.setValue(com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback));
			input.setTooltip(Tooltip.create(range.description()));
			((DesignerTheme.Input) input).invalid(!range.valid(input.getValue()));
			labels.add(new Label(range.description(), x + 4, y + 22, Math.max(1, w - 8), 1, true));
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
		}, 36, false));
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
		}));
	}

	protected void choice(String label, JsonObject object, String key, String fallback, String... values) {
		anchor(key);
		rows.add(new Row(label, (x, y, w) -> {
			String selected = com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(object, key, fallback);
			dropdown(x, y, w, selected, values, value -> object.addProperty(key, value));
		}));
	}

	void gene(String label, JsonObject genes, String key, String... values) {
		anchor("attributes/" + key);
		rows.add(new Row(label, (x, y, w) -> {
			String[] choices = new String[values.length + 1];
			choices[0] = "random";
			System.arraycopy(values, 0, choices, 1, values.length);
			dropdown(x, y, w, com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(genes, key, "random"), choices, value -> {
				if (value.equals("random")) {
					genes.remove(key);
				} else {
					genes.addProperty(key, value);
				}
			});
		}));
	}

	protected Button dropdown(int x, int y, int w, String current, String[] values, Consumer<String> selected) {
		return dropdown(x, y, w, current, values, value -> true, selected);
	}

	protected Button dropdown(int x, int y, int w, String current, String[] values, Predicate<String> enabled, Consumer<String> selected) {
		Button control = addRenderableWidget(new DesignerDropdown.Control(x, y, w, option(current), anchor -> {
			scroll.stop();
			popup = new DesignerDropdown(anchor.getX(), anchor.getY(), w, current, values, enabled, value -> {
				popup = null;
				selected.accept(value);
				if (minecraft.screen == this) {
					rebuild();
				}
			}, layoutWidth, layoutHeight);
		}));
		control.setTooltip(Tooltip.create(option(current)));
		return control;
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
			if (band.second() == null) {
				int rowWidth = contentWidth >= 300 && DesignerFormLayout.compact(band.first()) ? (contentWidth - 8) / 2 : contentWidth;
				placeRow(band.first(), left, y, rowWidth);
			} else {
				int columnWidth = (contentWidth - 8) / 2;
				placeRow(band.first(), left, y, columnWidth);
				placeRow(band.second(), left + columnWidth + 8, y, columnWidth);
			}
		} finally {
			creatingRows = false;
		}
		List<PositionedWidget> widgets = new ArrayList<>();
		for (var child : children().subList(childStart, children().size())) {
			if (child instanceof AbstractWidget widget) {
				widgets.add(new PositionedWidget(widget, widget.getY()));
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
		} else {
			int labelWidth = Math.min(Math.min(140, width * 40 / 100), Math.max(70, font.width(tr(row.label)) + 10));
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
			if (bands.get(index).source() >= row) {
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
		return frameHeight >= 200 ? 24 : 14;
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
				if (child instanceof EditBox input && input.getMessage().equals(message) && input.getValue().equals(value)) {
					setFocused(input);
					input.setCursorPosition(Math.min(cursor, input.getValue().length()));
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
		} finally {
			graphics.flush();
			graphics.pose().popPose();
		}
	}

	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawString(font, font.plainSubstrByWidth(title.getString(), Math.max(1, titleWidth)),
				frameLeft + (returnButton ? 54 : 28), frameTop + 11, FOREGROUND, false);
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
			if (widget instanceof DesignerStackButton stack && stack.isHovered() && visibleControl(stack, mouseY)) {
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
			DesignerTheme.panel(graphics, "card", frameLeft + 6, statusY, frameWidth - 12, statusHeight());
			boolean error = status.startsWith("designer.error.") || status.startsWith("Invalid") || status.startsWith("Unsupported");
			int color = error ? DesignerTheme.colors().error() : FOREGROUND;
			if (error) {
				DesignerTheme.errorFrame(graphics, frameLeft + 6, statusY, frameWidth - 12, statusHeight());
			}
			markInvalidFields(error);
			var lines = font.split(message, frameWidth - 28);
			for (int index = 0; index < Math.min(statusHeight() >= 24 ? 2 : 1, lines.size()); index++) {
				graphics.drawString(font, lines.get(index), frameLeft + 14, statusY + 3 + index * 9, color, false);
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
				graphics.renderOutline(area.getX(), area.getY(), area.getWidth(), area.getHeight(), accepted ? ACCENT : DesignerTheme.colors().error());
				if (DesignerPreferences.enabled("context_hints") && area.contains(mouseX, mouseY)) {
					graphics.renderTooltip(font, tr(accepted ? "drag_accept" : "drag_reject"), mouseX, mouseY);
				}
			}
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
	}

	private void renderRows(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		if (rowViews.isEmpty()) {
			return;
		}
		DesignerTheme.scissor(graphics, left, contentTop, left + contentWidth, editorBottom());
		int offset = (int) Math.floor(scroll.position());
		boolean inside = mouseX >= left && mouseX < left + contentWidth && mouseY >= contentTop && mouseY < editorBottom();
		float animatedX = transition.contentOffsetX();
		int contentMouseX = (int) (mouseX - animatedX);
		graphics.pose().pushPose();
		graphics.pose().translate(animatedX, Math.floor(scroll.position()) - scroll.position(), 0);
		try {
			for (var entry : rowViews.entrySet()) {
				RowView view = entry.getValue();
				int y = view.y - offset;
				if (DesignerPreferences.enabled("alternating_rows") && entry.getKey() % 2 == 0) {
					graphics.fill(left, y, left + contentWidth, y + view.height,
							DesignerPalette.mix(DesignerTheme.colors().panel(), DesignerTheme.colors().card(), 0.4F));
				}
				for (Rect2i section : view.sections) {
					graphics.fill(section.getX(), section.getY() + section.getHeight() - offset - 1,
							section.getX() + section.getWidth(), section.getY() + section.getHeight() - offset, DesignerTheme.colors().border());
				}
				for (RecipeHeader header : view.headers) {
					if (header.y == view.y) {
						boolean removing = header.remove != null && inside && header.remove.isMouseOver(contentMouseX, mouseY);
						int color = removing ? DesignerTheme.colors().error() : DesignerTheme.colors().accent();
						graphics.fill(left, y, left + contentWidth, y + 26,
								DesignerPalette.mix(DesignerTheme.colors().card(), color, removing ? 0.14F : 0.05F));
						graphics.fill(left, y + 3, left + 2, y + 23, color);
					}
				}
				var band = bands.get(entry.getKey());
				if (highlightedSource >= band.source() && highlightedSource <= band.source() + (band.second() == null ? 0 : 1)
						&& net.minecraft.Util.getMillis() < highlightUntil) {
					int columnWidth = band.second() == null ? contentWidth : (contentWidth - 8) / 2;
					int columnX = left + (highlightedSource > band.source() ? columnWidth + 8 : 0);
					int color = highlightedError ? DesignerTheme.colors().error() : ACCENT;
					graphics.fill(columnX, y, columnX + columnWidth, y + view.height - 2,
							DesignerPalette.mix(DesignerTheme.colors().panel(), color, 0.12F));
					graphics.renderOutline(columnX, y, columnWidth, view.height - 2, color);
				}
				for (PositionedWidget positioned : view.widgets) {
					positioned.widget.render(graphics, inside ? contentMouseX : -1, inside ? mouseY : -1, partialTick);
				}
				for (Label label : view.labels) {
					var lines = label.lines;
					for (int index = 0; index < Math.min(label.maxLines, lines.size()); index++) {
						boolean errorLabel = highlightedError && net.minecraft.Util.getMillis() < highlightUntil
								&& highlightedSource >= band.source() && highlightedSource <= band.source() + (band.second() == null ? 0 : 1)
								&& label.x >= left + (highlightedSource > band.source() ? (contentWidth - 8) / 2 + 8 : 0)
								&& label.x < left + (highlightedSource > band.source() ? contentWidth : band.second() == null ? contentWidth : (contentWidth - 8) / 2);
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
		for (RowView view : rowViews.values()) {
			for (PositionedWidget positioned : view.widgets) {
				if (positioned.widget == control) {
					return mouseY >= contentTop && mouseY < editorBottom();
				}
			}
		}
		return true;
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
		graphics.fill(frameLeft + 2, frameTop + 3, frameLeft + frameWidth + 2, frameTop + frameHeight + 3, 0x28000000);
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
		anchor(key);
		int rowHeight = compactHeight() ? 14 : 24;
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, rowHeight - 2));
			labels.add(new Label(option(key).copy().withColor(FOREGROUND & 0xFFFFFF), x + 4, y + (compactHeight() ? 1 : 6), w - 8, 1, true));
		}, rowHeight));
	}

	void recipeHeading(Component title, String id, Runnable edit, Runnable remove) {
		anchor(id);
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 24));
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 4, y + 6, Math.max(40, w - 110), 1, true));
			button(x + w - 100, y + 1, 68, tr("edit_entry"), edit).setTooltip(Tooltip.create(Component.literal(id)));
			Button delete = button(x + w - 26, y + 1, 24, Component.literal("×"), remove);
			delete.setTooltip(Tooltip.create(Component.translatable("designer.remove_named_recipe", title.getString()).append("\n" + id)));
			recipeHeaders.add(new RecipeHeader(y, delete));
		}, 26));
	}

	void overviewHeading(Component title, Runnable edit) {
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 22));
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 7, y + 6, Math.max(40, w - 84), 1, true));
			button(x + w - 72, y + 1, 68, tr("edit_entry"), edit);
		}, 24));
	}

	void outputHeading(Component title, Runnable remove) {
		rows.add(new Row("", (x, y, w) -> {
			sections.add(new Rect2i(x, y, w, 26));
			labels.add(new Label(title.copy().withColor(FOREGROUND & 0xFFFFFF), x + 6, y + 7, Math.max(10, w - 38), 1, true));
			Button delete = addRenderableWidget(new DesignerTheme.Icon(x + w - 26, y + 2, DesignerTheme.Symbol.DELETE, remove));
			delete.setTooltip(Tooltip.create(Component.translatable("designer.remove_named_output", title.getString())));
			recipeHeaders.add(new RecipeHeader(y, delete));
		}, 28));
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
	}

	final boolean focusLocation(String key, boolean error) {
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
		highlightUntil = net.minecraft.Util.getMillis() + 8000;
		if (error) {
			var band = bands.get(index);
			int columnWidth = band.second() == null ? contentWidth : (contentWidth - 8) / 2;
			int columnX = left + (row > band.source() ? columnWidth + 8 : 0);
			for (PositionedWidget positioned : rowViews.get(index).widgets) {
				if (positioned.widget instanceof DesignerTheme.Input input && input.getX() >= columnX && input.getX() < columnX + columnWidth) {
					input.invalid(true);
					setFocused(input);
				}
			}
		}
		return true;
	}

	protected void virtualItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen) {
		virtualItem(x, y, w, stack, fallback, chosen, false);
	}

	protected void virtualItem(int x, int y, int w, ItemStack stack, String fallback, Consumer<ItemStack> chosen, boolean nestsFirst) {
		addRenderableWidget(new DesignerStackButton(x, y, w, stack, fallback, () -> minecraft.setScreen(new BeeDesignerItemPicker(this, chosen, nestsFirst))));
		ghost(x, y, w, value -> value instanceof ItemStack, value -> chosen.accept(((ItemStack) value).copyWithCount(1)));
	}

	void resourceView(JsonObject output, boolean fluid) {
		rows.add(new Row("preview", (x, y, w) -> addRenderableWidget(new DesignerResourceButton(x, y, w, output, fluid, () -> {}))));
	}

	protected void ghost(int x, int y, int w, Predicate<Object> accepts, Consumer<Object> chosen) {
		ghost(x, y, w, 20, accepts, chosen);
	}

	protected void ghost(int x, int y, int w, int h, Predicate<Object> accepts, Consumer<Object> chosen) {
		targets.add(new GhostTarget(new Rect2i(x, y, w, h), accepts, value -> {
			try {
				chosen.accept(value);
				if (minecraft.screen == this) {
					rebuild();
				}
			} catch (RuntimeException exception) {
				status = exception.getMessage();
			}
		}));
	}

	public record GhostTarget(Rect2i area, Predicate<Object> accepts, Consumer<Object> chosen) {
	}

	public List<GhostTarget> ghostTargets() {
		return popup == null && !transition.leaving() ? targets.stream().map(target -> new GhostTarget(screenArea(visibleTargetArea(target)), target.accepts, target.chosen))
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
		if (transition.leaving()) {
			return true;
		}
		transition.settle();
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

	protected record Row(String label, WidgetFactory widget, int height, boolean wide) {
		protected Row(String label, WidgetFactory widget) {
			this(label, widget, 20, false);
		}

		protected Row(String label, WidgetFactory widget, int height) {
			this(label, widget, height, true);
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
			lines = List.copyOf(Minecraft.getInstance().font.split(this.text, Math.max(1, width)));
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

	private record RowView(int y, int height, List<PositionedWidget> widgets, List<Label> labels,
			List<Rect2i> sections, List<GhostTarget> targets, List<RecipeHeader> headers) {
	}

	@FunctionalInterface
	protected interface WidgetFactory {
		void create(int x, int y, int width);
	}
}
