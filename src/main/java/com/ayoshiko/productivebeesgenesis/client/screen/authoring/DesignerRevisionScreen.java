package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 历史修订只载入声明；覆盖当前世界仍需要新的基准、检查和显式发布。 */
final class DesignerRevisionScreen extends DesignerFormScreen {
	private final Screen parent;
	private final BeeDesignerScreen editor;
	private final String revision;
	private JsonObject entries;
	private int revisionPage;
	private long request;
	private long loadRequest;

	DesignerRevisionScreen(Screen parent, BeeDesignerScreen editor, String revision) {
		super(tr("revision_bees"));
		this.parent = parent;
		editorParent = parent;
		this.editor = editor;
		this.revision = revision;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("history_load_help"));
		if (entries == null && request == 0) {
			query();
		}
		if (entries != null) {
			for (var value : entries.getAsJsonArray("entries")) {
				JsonObject entry = value.getAsJsonObject();
				String id = entry.get("id").getAsString();
				JsonObject names = entry.getAsJsonObject("names");
				String name = BeeDesignerDocument.text(names, minecraft.options.languageCode, BeeDesignerDocument.text(names, "en_us", id));
				rows.add(new Row("", (x, y, w) -> button(x, y, w, Component.literal(name + "  ·  " + id), () -> {
					JsonObject data = new JsonObject();
					data.addProperty("revision", revision);
					data.addProperty("id", id);
					loadRequest = BeeDesignerClient.request("revision_bee", data);
					status = "designer.status.loading";
				})));
			}
		}
		finishRows();
		button(left, footerY(), 24, Component.literal("‹"), () -> {
			revisionPage--;
			query();
		}).active = revisionPage > 0;
		button(left + 30, footerY(), 24, Component.literal("›"), () -> {
			revisionPage++;
			query();
		}).active = entries != null && revisionPage + 1 < entries.get("pages").getAsInt();
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	private void query() {
		JsonObject data = new JsonObject();
		data.addProperty("revision", revision);
		data.addProperty("page", revisionPage);
		request = BeeDesignerClient.request("revision", data);
	}

	void receive(long sequence, String action, JsonObject data) {
		if (action.equals("revision") && sequence == request) {
			entries = data;
			revisionPage = data.get("page").getAsInt();
			rebuild();
		} else if (action.equals("revision_bee") && sequence == loadRequest) {
			editor.changeDocument(() -> editor.importDocument(data));
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
