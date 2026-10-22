package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 常用加载条件使用明确字段编辑，其它条件完整保管并提供高级入口。 */
final class DesignerConditionsScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject owner;
	private final String field;

	DesignerConditionsScreen(Screen parent, JsonObject owner, String field) {
		super(tr("conditions"));
		this.parent = parent;
		editorParent = parent;
		this.owner = owner;
		this.field = field;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("conditions_help"));
		JsonArray conditions = BeeDesignerDocument.array(owner, field);
		for (int index = 0; index < conditions.size(); index++) {
			int selected = index;
			JsonObject condition = conditions.get(index).getAsJsonObject();
			String type = BeeDesignerDocument.text(condition, "type", "");
			section("condition_entry");
			note("kind", option(type));
			if (type.equals("neoforge:mod_loaded")) {
				field("mod_id", condition, "modid", "");
			} else if (type.equals("productivebees:bee_exists")) {
				bee("bee", condition, "bee", "minecraft:bee");
			} else {
				rows.add(new Row("advanced", (x, y, w) -> button(x, y, w, tr("edit_json"), () ->
						minecraft.setScreen(new DesignerJsonScreen(this, condition, type)))));
			}
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("remove_condition"), () -> {
				conditions.remove(selected);
				rebuild();
			})));
		}
		for (String type : new String[]{"neoforge:mod_loaded", "productivebees:bee_exists"}) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("add_condition").copy().append(" · ").append(option(type)), () -> {
				if (conditions.size() >= 32) {
					status = "designer.error.conditions";
					return;
				}
				JsonObject condition = new JsonObject();
				condition.addProperty("type", type);
				condition.addProperty(type.equals("neoforge:mod_loaded") ? "modid" : "bee",
						type.equals("neoforge:mod_loaded") ? "productivebees" : "minecraft:bee");
				conditions.add(condition);
				rebuild();
			})));
		}
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.done"), this::onClose);
	}

	@Override
	public void onClose() {
		if (BeeDesignerDocument.array(owner, field).isEmpty()) {
			owner.remove(field);
		}
		minecraft.setScreen(parent);
	}
}
