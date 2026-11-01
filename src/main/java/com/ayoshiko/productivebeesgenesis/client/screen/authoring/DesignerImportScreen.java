package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 导入只载入草稿；已有身份必须先读取服务端基准，再由作者显式编辑或复制。 */
final class DesignerImportScreen extends DesignerFormScreen {
	private final BeeDesignerScreen parent;
	private final JsonObject options = new JsonObject();
	private Map<String, String> projects = Map.of();
	private boolean reading;

	DesignerImportScreen(BeeDesignerScreen parent) {
		super(tr("import_project"));
		this.parent = parent;
		editorParent = parent;
		options.addProperty("path", "");
	}

	@Override
	protected void init() {
		begin();
		note("", tr("import_help"));
		field("local_path", options, "path", "");
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("browse_file"), () -> {
			minecraft.setScreen(new DesignerFileBrowserScreen(this, options.get("path").getAsString(), false,
					selected -> options.addProperty("path", selected.toString())));
		}).active = !reading));
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("import_action"), () -> {
			try {
				read(Path.of(options.get("path").getAsString()));
			} catch (RuntimeException exception) {
				status = exception.getMessage();
			}
		})
				.active = !reading));
		if (projects.size() > 1) {
			rows.add(new Row("", (x, y, w) -> primaryButton(x, y, w, tr("batch_import"), () ->
					minecraft.setScreen(new DesignerBatchImportScreen(parent, this, projects))).active = !reading));
		}
		for (var entry : projects.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			JsonObject document = JsonParser.parseString(entry.getValue()).getAsJsonObject();
			rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, entry.getKey(),
					DesignerCollectionScreen.localizedName(document), false, () -> minecraft.setScreen(new DesignerImportReviewScreen(parent, this, document))))));
			DesignerCollectionScreen.addCredits(this, document);
		}
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.cancel"), this::onClose);
	}

	private void read(Path path) {
		if (reading) {
			return;
		}
		reading = true;
		status = "designer.status.loading";
		DesignerProjectFiles.read(path, loaded -> {
			reading = false;
			projects = loaded;
			if (minecraft.screen == this) {
				rebuild();
			}
		}, message -> {
			reading = false;
			status = message;
			if (minecraft.screen == this) {
				rebuild();
			}
		});
		rebuild();
	}

	@Override
	public void onFilesDrop(List<Path> paths) {
		if (paths.size() == 1) {
			options.addProperty("path", paths.get(0).toString());
			read(paths.get(0));
		} else {
			status = "designer.error.one_import";
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
