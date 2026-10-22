package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** 语言代码来自游戏语言列表，也允许资源包使用的合法自定义代码。 */
final class DesignerNamesScreen extends DesignerFormScreen {
	private final Screen parent;
	private final JsonObject names;
	private String query = "";
	private String locale = "";
	private String name = "";

	DesignerNamesScreen(Screen parent, JsonObject names) {
		super(tr("add_language"));
		this.parent = parent;
		editorParent = parent;
		this.names = names;
	}

	@Override
	protected void init() {
		begin();
		text("language_code", locale, value -> locale = value.strip().toLowerCase(Locale.ROOT));
		text("localized_name", name, value -> name = value);
		rows.add(new Row("", (x, y, w) -> primaryButton(x, y, w, tr("add_language"), this::save)));
		section("supported_languages");
		names.keySet().stream().sorted().forEach(this::languageRow);
		section("available_languages");
		text("search", query, value -> query = value);
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("search"), this::rebuild)));
		minecraft.getLanguageManager().getLanguages().keySet().stream().filter(code -> !names.has(code)).forEach(this::languageRow);
		finishRows();
		button(left, footerY(), 80, tr("back"), this::onClose);
	}

	private void languageRow(String code) {
		var info = minecraft.getLanguageManager().getLanguages().get(code);
		String label = info == null ? code : info.toComponent().getString();
		if (!(code + " " + label).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
			return;
		}
		rows.add(new Row("", (x, y, w) -> {
			var message = Component.literal(code + " · " + label);
			addRenderableWidget(new DesignerTheme.Action(x, y, w, message, () -> {
				locale = code;
				name = names.has(code) ? names.get(code).getAsString() : "";
				page = 0;
				rebuild();
			}));
		}));
	}

	private void save() {
		if (!locale.matches("[a-z]{2,3}(?:_[a-z0-9]{2,8})?") || name.length() > 128 || name.contains("%")
				|| names.size() >= 64 && !names.has(locale)) {
			status = "designer.error.names";
			return;
		}
		names.addProperty(locale, name);
		minecraft.setScreen(parent);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
