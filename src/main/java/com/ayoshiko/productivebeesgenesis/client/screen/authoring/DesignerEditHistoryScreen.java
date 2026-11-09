package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.Map;

/** 选择快照保留后续重做分支；只有再次编辑才截断该分支。 */
final class DesignerEditHistoryScreen extends DesignerFormScreen {
	private final Screen parent;
	private final DesignerDraftHistory history;
	private final Consumer<JsonObject> restored;

	DesignerEditHistoryScreen(Screen parent, DesignerDraftHistory history, Consumer<JsonObject> restored) {
		super(tr("edit_history"));
		this.parent = parent;
		editorParent = parent;
		this.history = history;
		this.restored = restored;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("edit_history_help"));
		var times = history.times();
		var format = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
		for (int index = times.size() - 1; index >= 0; index--) {
			int selected = index;
			var changes = history.changes(index);
			String summary = changes.isEmpty() ? tr("history_initial").getString() : changes.stream().limit(3)
					.map(change -> fieldName(change.path()) + ": " + valueName(change.before()) + " → " + valueName(change.after()))
					.collect(java.util.stream.Collectors.joining("；"));
			String label = (index == history.cursor() ? "● " : "") + (index + 1) + " · " + format.format(Instant.ofEpochMilli(times.get(index))) + " · " + summary;
			rows.add(new Row("", (x, y, w) -> {
				var button = addRenderableWidget(new DesignerTheme.Action(x, y, w, Component.literal(label), () -> {
					restored.accept(history.select(selected));
					minecraft.setScreen(parent);
				}));
				String details = changes.stream().map(change -> fieldName(change.path()) + ": " + valueName(change.before()) + " → " + valueName(change.after())
						+ "\n" + change.path())
						.collect(java.util.stream.Collectors.joining("\n"));
				button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(details.isEmpty() ? summary : details)));
			}));
		}
		finishRows();
		button(left, footerY(), 80, tr("back"), this::onClose);
	}

	private static String fieldName(String path) {
		String key = path.substring(path.lastIndexOf('.') + 1);
		if (path.startsWith("names.")) {
			return tr("localized_name").getString() + " " + key;
		}
		key = Map.ofEntries(Map.entry("weather_tolerance", "weatherTolerance"), Map.entry("attributes", "genes_section"),
				Map.entry("parentDeathChance", "parent_death"), Map.entry("processingTime", "processing_time"),
				Map.entry("flowerBlock", "flower"), Map.entry("flowerItem", "flower"), Map.entry("flowerFluid", "flower"),
				Map.entry("flowerTag", "tag_id"), Map.entry("duration", "machine_processing_time"), Map.entry("eu", "machine_eu"),
				Map.entry("time", "machine_processing_time"), Map.entry("priority", "machine_priority"),
				Map.entry("renderProgress", "machine_render_progress"), Map.entry("render_progress", "machine_render_progress"))
				.getOrDefault(key, key);
		String label = "designer." + key;
		if (net.minecraft.locale.Language.getInstance().has(label)) {
			return tr(key).getString();
		}
		String root = path.split("\\.", 2)[0];
		String historyKey = "designer.history.field." + key.replaceAll("\\[\\d+\\]", "");
		if (net.minecraft.locale.Language.getInstance().has(historyKey)) {
			return tr(historyKey).getString();
		}
		String rootKey = "designer.history.field." + root;
		return net.minecraft.locale.Language.getInstance().has(rootKey) ? tr(rootKey).getString() : path;
	}

	private static String valueName(String value) {
		if (value.equals("∅")) {
			return tr("history_unset").getString();
		}
		if (value.equals("{}") || value.equals("[]")) {
			return tr("history_empty_value").getString();
		}
		if (value.equals("true") || value.equals("false")) {
			return tr(value.equals("true") ? "on" : "off").getString();
		}
		Component translated = option(value);
		return translated.getString();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
