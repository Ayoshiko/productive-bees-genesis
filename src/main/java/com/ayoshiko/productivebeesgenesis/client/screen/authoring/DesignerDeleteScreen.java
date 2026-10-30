package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 服务端分步确认，最后输入完整蜂种 ID；确认后先保存待删除草稿。 */
final class DesignerDeleteScreen extends DesignerFormScreen {

	private final BeeDesignerScreen parent;
	private final String id;
	private final DesignerFormScreen returnPage;
	private final boolean purging;
	private JsonObject review;
	private long request = -1;
	private boolean busy;
	private String phrase = "";

	DesignerDeleteScreen(BeeDesignerScreen parent, String id) {
		this(parent, parent, id, false);
	}

	DesignerDeleteScreen(BeeDesignerScreen parent, DesignerFormScreen returnPage, String id, boolean purging) {
		super(tr(purging ? "recycle_purge_world" : "delete_bee"));
		this.parent = parent;
		this.id = id;
		this.returnPage = returnPage;
		this.purging = purging;
		editorParent = returnPage;
	}

	@Override
	protected void init() {
		begin();
		if (request < 0) {
			JsonObject query = new JsonObject();
			query.addProperty("id", id);
			query.addProperty("purging", purging);
			request = BeeDesignerClient.request("delete_prepare", query);
			busy = true;
		}
		rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, id, () -> {}))));
		int step = review == null ? 0 : review.get("step").getAsInt();
		note("", Component.translatable("designer.delete_step", step + 1, 3));
		String[] help = purging ? new String[]{"recycle_purge_impact", "recycle_purge_assets", "delete_final_help"}
				: new String[]{"delete_impact", "delete_world_assets", "delete_final_help"};
		note("", tr(help[Math.min(step, help.length - 1)]));
		if (review != null) {
			note("", Component.translatable("designer.delete_recipe_count", review.get("retired_recipes").getAsInt(), review.get("total_recipes").getAsInt()));
		}
		note("", tr("delete_application_help"));
		if (step == 2) {
			rows.add(new Row("delete_identity", (x, y, w) -> {
				EditBox box = new EditBox(font, x, y, w, 20, tr("delete_identity"));
				box.setMaxLength(256);
				box.setValue(phrase);
				box.setResponder(value -> phrase = value);
				addRenderableWidget(box);
			}));
		}
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.cancel"), this::onClose);
		primaryButton(left + contentWidth - 156, footerY(), 156, tr(step < 2 ? "delete_next" : "delete_stage"), this::confirm).active = review != null && !busy;
	}

	private void confirm() {
		if (review == null || busy) {
			return;
		}
		int step = review.get("step").getAsInt();
		if (step == 2 && !phrase.equals(id)) {
			status = "designer.error.delete_phrase";
			return;
		}
		JsonObject query = review.deepCopy();
		query.addProperty("step", step + 1);
		query.addProperty("phrase", phrase);
		request = BeeDesignerClient.request("delete_confirm", query);
		busy = true;
		rebuild();
	}

	void receive(long sequence, String action, JsonObject data) {
		if (sequence != request) {
			return;
		}
		busy = false;
		if (action.equals("delete_review")) {
			review = data;
			rebuild();
		} else if (action.equals("delete_staged")) {
			parent.stageDeletion(data);
		} else if (action.equals("result")) {
			status = data.get("message").getAsString();
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(returnPage);
	}
}
