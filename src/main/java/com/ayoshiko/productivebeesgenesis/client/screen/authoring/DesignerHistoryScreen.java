package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

final class DesignerHistoryScreen extends DesignerFormScreen {
	private final BeeDesignerScreen parent;
	private JsonObject history;
	private int historyPage;
	private long request;
	private boolean loading;
	private long requestedAt;

	DesignerHistoryScreen(BeeDesignerScreen parent) {
		super(tr("history"));
		this.parent = parent;
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("history_help"));
		if (history == null) {
			if (!loading) {
				query();
			}
			note("", tr(loading ? "history_loading" : "history_retry"));
		} else {
			if (history.getAsJsonArray("entries").isEmpty()) {
				note("", tr("history_empty"));
			}
			for (var value : history.getAsJsonArray("entries")) {
				JsonObject entry = value.getAsJsonObject();
				rows.add(new Row("", (x, y, w) -> button(x, y, w,
						Component.literal(entry.get("modified").getAsString() + " · " + entry.get("revision").getAsString()), () ->
								minecraft.setScreen(new DesignerRevisionScreen(this, parent, entry.get("revision").getAsString())))));
			}
		}
		finishRows();
		button(left, footerY(), 24, Component.literal("‹"), () -> {
			historyPage--;
			query();
		}).active = historyPage > 0;
		button(left + 30, footerY(), 24, Component.literal("›"), () -> {
			historyPage++;
			query();
		}).active = history != null && historyPage + 1 < history.get("pages").getAsInt();
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.done"), this::onClose);
		button(left + 60, footerY(), 80, tr("refresh"), () -> {
			query();
			rebuild();
		}).active = !loading;
	}

	private void query() {
		JsonObject query = new JsonObject();
		query.addProperty("page", historyPage);
		request = BeeDesignerClient.request("history", query);
		loading = request >= 0;
		requestedAt = System.currentTimeMillis();
		status = loading ? "designer.history_loading" : "designer.error.designer_session";
	}

	void receive(long sequence, JsonObject data) {
		if (sequence == request) {
			loading = false;
			status = "";
			history = data;
			historyPage = data.get("page").getAsInt();
			rebuild();
		}
	}

	void failed(long sequence, String message) {
		if (sequence == request) {
			loading = false;
			status = message;
			rebuild();
		}
	}

	@Override
	public void tick() {
		if (loading && System.currentTimeMillis() - requestedAt > 10000) {
			failed(request, "designer.history_retry");
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
