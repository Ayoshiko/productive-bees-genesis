package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerExportName;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 确认写入预览的同一封闭集合，导出名不能改变文件路径。 */
final class DesignerExportPreviewScreen extends DesignerFormScreen {
	private final DesignerFormScreen parent;
	private final DesignerProjectFiles.PreparedExport preview;
	private final Consumer<Path> exported;
	private final boolean installation;
	private final JsonObject options = new JsonObject();
	private final List<Map.Entry<String, String>> files;
	private final List<PreviewFile> fileDetails;
	private final List<PreviewBee> bees;
	private String query = "";
	private boolean writing;
	private boolean installationFailed;
	private Path result;
	private Button confirm;
	private String displayedName = "";

	DesignerExportPreviewScreen(DesignerFormScreen parent, DesignerProjectFiles.PreparedExport preview, String name, Consumer<Path> exported) {
		super(tr(preview.installation() == null ? "export_preview" : "install_preview"));
		this.parent = parent;
		editorParent = parent;
		this.preview = preview;
		this.exported = exported;
		installation = preview.installation() != null;
		if (!installation) {
			options.addProperty("name", name);
		}
		files = preview.files().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
		Map<String, String> operations = new HashMap<>();
		if (installation) {
			for (var change : preview.installation().changes()) {
				operations.put(change.path(), change.operation().toString().toLowerCase(Locale.ROOT));
			}
		}
		fileDetails = files.stream().map(file -> new PreviewFile(file.getKey(), file.getValue(),
				installation ? operations.get(file.getKey()) : "")).toList();
		bees = readBees(preview);
	}

	@Override
	protected boolean showsInventory() {
		return false;
	}

	@Override
	protected void init() {
		begin();
		note("", tr(installation ? "install_preview_help" : "export_preview_help"));
		if (installationFailed) {
			note("", tr("install_retry_preview"));
		}
		note("collection_format", tr("option." + preview.format()));
		DesignerCollectionScreen.addFormatHelp(this, preview.format());
		if (installation) {
			var plan = preview.installation();
			note("", Component.translatable("designer.install_target", plan.target().toString()));
			note("", Component.translatable("designer.install_plan_counts", plan.createCount(), plan.replaceCount(), plan.unchangedCount()));
			if (plan.replaceCount() > 0) {
				note("", Component.translatable("designer.install_backup_plan", plan.backupDirectory().toString()));
			}
		} else {
			field("export_file_name", options, "name", "");
			note("", tr("export_file_name_help"));
		}
		if (preview.format().endsWith("datapack")) {
			note("", tr("export_names_companion"));
		}
		note("", Component.translatable("designer.export_list_summary", bees.size(), files.size(), formatBytes(preview.bytes())));
		String language = minecraft.options.languageCode;
		String selected = String.join(" · ", bees.stream().limit(3).map(bee -> bee.label(language)).toList());
		if (bees.size() > 3) {
			selected += " · " + Component.translatable("designer.export_list_more", bees.size() - 3).getString();
		}
		String summary = selected;
		rows.add(new Row("", (x, y, w) -> {
			var names = addRenderableWidget(new DesignerTheme.InlineText(x + 4, y, w - 8, 20, Component.literal(summary)));
			names.setColor(FOREGROUND);
			names.setTooltip(Tooltip.create(Component.literal(summary)));
		}));
		if (bees.size() > 3) {
			group("export_list_selected", true, () -> {
				for (PreviewBee bee : bees) {
					note("", Component.literal(bee.label(language)));
				}
			});
		}
		if (result != null) {
			note("", Component.literal(result.toString()));
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr(installation ? "open_instance_folder" : "open_exports_folder"), () ->
					DesignerProjectFiles.openDirectory(result.getParent(), false, message -> status = message))));
		}
		section(installation ? "install_contents" : "export_contents");
		if (files.size() > 12) {
			rows.add(new Row("", (x, y, w) -> {
				EditBox search = searchField(y, w, query, value -> {
					query = value;
					page = 0;
					rebuild();
				});
				search.setHint(tr("export_list_search"));
				search.setTooltip(Tooltip.create(tr("export_list_search")));
			}, 30, true));
		}
		String filter = query.toLowerCase(Locale.ROOT).trim();
		var visible = fileDetails.stream().filter(file -> file.search().contains(filter)).toList();
		if (visible.isEmpty()) {
			note("", tr("export_list_empty"));
		}
		for (PreviewFile file : visible) {
			rows.add(new Row("", (x, y, w) -> addRenderableWidget(new FileRow(x, y, w, file,
					() -> openFile(file.path()))).active = !writing, 32, true));
		}
		finishRows();
		if (writing) {
			children().stream().filter(widget -> widget instanceof AbstractWidget)
					.map(widget -> (AbstractWidget) widget).forEach(widget -> widget.active = false);
		}
		if (result != null) {
			children().stream().filter(widget -> widget instanceof EditBox)
					.map(widget -> (EditBox) widget).forEach(widget -> widget.active = false);
		}
		int cell = (contentWidth - 6) / 2;
		confirm = primaryButton(left, footerY(), cell, installation ? tr("install_confirm") : Component.empty(), this::write);
		if (installation) {
			confirm.setTooltip(Tooltip.create(tr(installationFailed ? "install_retry_preview" : "install_confirm_help")));
			confirm.active = !writing && !installationFailed && result == null;
		}
		button(left + cell + 6, footerY(), cell, tr("back"), this::onClose).active = !writing;
		displayedName = "";
		if (!installation) {
			updateName();
		}
	}

	private void openFile(String path) {
		String source = preview.files().get(path);
		if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
			source = new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(source));
		}
		minecraft.setScreen(new DesignerJsonScreen(this, source, path));
	}

	private static List<PreviewBee> readBees(DesignerProjectFiles.PreparedExport preview) {
		String manifestPath = "data/productivebeesgenesis/bee_designer/credits.json";
		if (preview.format().equals("portable")) {
			manifestPath = "bee-collection.json";
		} else if (preview.format().endsWith("kubejs")) {
			manifestPath = "kubejs/" + manifestPath;
		}
		JsonObject manifest = JsonParser.parseString(preview.files().get(manifestPath)).getAsJsonObject();
		List<PreviewBee> selected = new ArrayList<>();
		for (var value : manifest.getAsJsonArray("bees")) {
			JsonObject bee = value.getAsJsonObject();
			Map<String, String> names = new HashMap<>();
			bee.getAsJsonObject("names").entrySet().forEach(name -> names.put(name.getKey(), name.getValue().getAsString()));
			selected.add(new PreviewBee(bee.get("id").getAsString(), Map.copyOf(names)));
		}
		return selected.stream().sorted(java.util.Comparator.comparing(PreviewBee::id)).toList();
	}

	private static String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		}
		if (bytes < 1024 * 1024) {
			return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
		}
		return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024));
	}

	private static String shorten(String text, int width) {
		var font = DesignerFonts.body();
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
	}

	private void updateName() {
		String name = options.get("name").getAsString();
		if (!displayedName.equals(name)) {
			displayedName = name;
			confirm.setMessage(Component.translatable("designer.export_named", name + ".zip"));
		}
		confirm.active = !writing && result == null;
	}

	@Override
	public void tick() {
		if (!installation) {
			updateName();
		}
	}

	private void write() {
		try {
			String name = installation ? "" : BeeDesignerExportName.validate(options.get("name").getAsString());
			writing = true;
			status = installation ? "designer.installing_client" : "designer.status.exporting";
			DesignerProjectFiles.writeExport(preview, name, path -> {
				writing = false;
				result = path;
				exported.accept(path);
				status = Component.translatable(installation ? "designer.install_complete" : "designer.status.exported", path.toString()).getString();
				if (minecraft.screen == this) {
					rebuild();
				}
			}, message -> {
				writing = false;
				installationFailed = installation;
				status = message;
				if (minecraft.screen == this) {
					rebuild();
				}
			});
		} catch (IllegalArgumentException failure) {
			status = failure.getMessage();
		}
		rebuild();
	}

	@Override
	public void onClose() {
		if (!writing) {
			minecraft.setScreen(parent);
		}
	}

	private record PreviewBee(String id, Map<String, String> names) {
		String label(String language) {
			String name = names.getOrDefault(language, names.getOrDefault("en_us", ""));
			return name.isBlank() ? id : name + " [" + id + "]";
		}
	}

	private record PreviewFile(String path, String name, String directory, String type, String glyph, long bytes, String search, String operation) {
		PreviewFile(String path, String source, String operation) {
			this(path, path.substring(path.lastIndexOf('/') + 1), directory(path), type(path), glyph(path),
					source.getBytes(StandardCharsets.UTF_8).length, path.toLowerCase(Locale.ROOT), operation);
		}

		private static String directory(String path) {
			int separator = path.lastIndexOf('/');
			return separator < 0 ? "" : path.substring(0, separator);
		}

		private static String type(String path) {
			if (path.contains("/productivebees/")) {
				return "bee";
			}
			if (path.contains("/recipe/")) {
				return "recipe";
			}
			if (path.contains("/lang/")) {
				return "names";
			}
			if (path.endsWith(".js")) {
				return "script";
			}
			if (path.endsWith("/index.json")) {
				return "project";
			}
			if (path.endsWith(".txt")) {
				return "text";
			}
			return "metadata";
		}

		private static String glyph(String path) {
			if (path.endsWith(".js")) {
				return "JS";
			}
			if (path.contains("/lang/")) {
				return "Aa";
			}
			return path.endsWith(".txt") ? "T" : "{}";
		}
	}

	private static final class FileRow extends DesignerTheme.Action {
		private final String filename;
		private final String detail;
		private final String size;
		private final String glyph;
		private final int sizeWidth;
		private final int glyphWidth;

		FileRow(int x, int y, int width, PreviewFile file, Runnable open) {
			super(x, y, width, Component.literal(file.path()), open);
			height = 32;
			var font = DesignerFonts.body();
			size = formatBytes(file.bytes());
			sizeWidth = font.width(size);
			filename = shorten(file.name(), width - sizeWidth - 48);
			String type = tr("export_list_type_" + file.type()).getString();
			if (!file.operation().isEmpty()) {
				type = tr("install_operation." + file.operation()).getString() + " · " + type;
			}
			String directory = file.directory();
			if (font.width(type + " · " + directory) > width - 40 && directory.contains("/")) {
				directory = directory.substring(0, directory.indexOf('/') + 1) + "…/" + directory.substring(directory.lastIndexOf('/') + 1);
			}
			detail = shorten(type + (directory.isEmpty() ? "" : " · " + directory), width - 40);
			glyph = file.glyph();
			glyphWidth = font.width(glyph);
			setTooltip(Tooltip.create(Component.literal(file.path()).append("\n")
					.append(Component.translatable("designer.export_list_file_details", type, file.bytes()))
					.append("\n").append(tr("export_list_open"))));
		}

		@Override
		protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			int offset = renderSurface(graphics);
			var palette = DesignerTheme.colors();
			var font = DesignerFonts.body();
			int iconX = getX() + 9;
			int iconY = getY() + 6 + offset;
			graphics.fill(iconX, iconY, iconX + 16, iconY + 20, palette.border());
			graphics.fill(iconX + 1, iconY + 1, iconX + 15, iconY + 19, palette.panel());
			graphics.drawString(font, glyph, iconX + (16 - glyphWidth) / 2, iconY + 6, active ? palette.accent() : palette.muted(), false);
			graphics.drawString(font, filename, getX() + 32, getY() + 5 + offset, textColor(), false);
			graphics.drawString(font, size, getRight() - sizeWidth - 8, getY() + 5 + offset, palette.muted(), false);
			graphics.drawString(font, detail, getX() + 32, getY() + 18 + offset, palette.muted(), false);
		}
	}
}
