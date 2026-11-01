package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/** 从本机实例目录选择来源，后台读取结果只交回仍存活的当前页面。 */
final class DesignerInstanceScreen extends DesignerFormScreen {
	private final BeeDesignerScreen editor;
	private final JsonObject options = new JsonObject();
	private List<Path> instances = List.of();
	private boolean reading;
	private boolean started;
	private long generation;

	DesignerInstanceScreen(BeeDesignerScreen editor) {
		super(tr("browse_instances"));
		this.editor = editor;
		editorParent = editor;
		Path game = net.minecraft.client.Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
		Path directory = game.getParent();
		options.addProperty("path", directory == null ? game.toString() : directory.toString());
	}

	@Override
	protected void init() {
		begin();
		note("", tr("instance_browser_help"));
		field("instance_directory", options, "path", "");
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("refresh"), this::refresh).active = !reading));
		for (Path instance : instances) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, Component.literal(instance.getFileName().toString()),
					() -> minecraft.setScreen(new DesignerInstanceBeesScreen(editor, this, instance)))));
		}
		if (reading) {
			note("", tr("status.loading"));
		}
		finishRows();
		button(left, footerY(), 90, tr("back"), this::onClose);
		if (!started) {
			started = true;
			refresh();
		}
	}

	private void refresh() {
		try {
			Path path = Path.of(options.get("path").getAsString());
			reading = true;
			long requested = ++generation;
			DesignerProjectFiles.instances(path, result -> {
				if (requested != generation) {
					return;
				}
				instances = result;
				reading = false;
				if (minecraft.screen == this) {
					rebuild();
				}
			}, message -> {
				if (requested == generation) {
					reading = false;
					status = message;
					if (minecraft.screen == this) {
						rebuild();
					}
				}
			});
			if (minecraft.screen == this) {
				rebuild();
			}
		} catch (RuntimeException failure) {
			reading = false;
			status = failure.getMessage();
		}
	}

	@Override
	public void onClose() {
		generation++;
		minecraft.setScreen(editor);
	}
}
