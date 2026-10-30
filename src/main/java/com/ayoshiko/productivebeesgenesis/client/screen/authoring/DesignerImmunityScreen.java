package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** PB 按 DamageType 的消息 ID 判断免疫；本页副本确认后才写入蜂种。 */
final class DesignerImmunityScreen extends DesignerFormScreen {
	private final DesignerFormScreen parent;
	private final JsonObject definition;
	private final boolean keepEmpty;
	private final List<String> values = new ArrayList<>();
	private String query = "";
	private boolean invalidSource;

	DesignerImmunityScreen(DesignerFormScreen parent, JsonObject definition) {
		this(parent, definition, false);
	}

	DesignerImmunityScreen(DesignerFormScreen parent, JsonObject definition, boolean keepEmpty) {
		super(tr("invulnerability"));
		this.parent = parent;
		this.editorParent = parent;
		this.definition = definition;
		this.keepEmpty = keepEmpty;
		if (definition.has("invulnerability")) {
			invalidSource = !definition.get("invulnerability").isJsonArray();
			if (!invalidSource) {
				for (var value : definition.getAsJsonArray("invulnerability")) {
					if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
						values.add(value.getAsString());
					} else {
						invalidSource = true;
					}
				}
			}
		}
	}

	@Override
	protected void init() {
		begin();
		if (invalidSource) {
			note("error", tr("error.invulnerability"));
			rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("edit_definition"), () ->
					minecraft.setScreen(new DesignerJsonScreen(parent, definition, "PB definition")))));
		}
		note("", tr("invulnerability_help"));
		text("search", query, value -> query = value);
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("search"), this::rebuild)));
		if (!values.isEmpty()) {
			section("immunity_selected");
			for (String value : List.copyOf(values)) {
				rows.add(new Row("", (x, y, w) -> {
					fixedNote(Component.literal(value), x, y, w - 28, 20);
					button(x + w - 24, y, 24, Component.literal("×"), () -> {
						values.remove(value);
						rebuild();
					});
				}));
			}
		}
		section("immunity_available");
		String search = query.toLowerCase(java.util.Locale.ROOT).strip();
		minecraft.level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).stream()
				.map(type -> type.msgId()).distinct().sorted().filter(value -> value.toLowerCase(java.util.Locale.ROOT).contains(search))
				.forEach(value -> rows.add(new Row("", (x, y, w) -> {
					fixedNote(Component.literal(value), x, y, Math.max(30, w - 88), 20);
					addRenderableWidget(new DesignerTheme.Switch(x + w - 78, y, 78, values.contains(value), () -> {
							if (!values.remove(value) && values.size() < 128) {
								values.add(value);
							}
							rebuild();
						}));
				})));
		finishRows();
		int cell = (contentWidth - 6) / 2;
		primaryButton(left, footerY(), cell, Component.translatable("gui.done"), () -> {
			JsonArray array = new JsonArray();
			values.forEach(array::add);
			if (array.isEmpty() && !keepEmpty) {
				definition.remove("invulnerability");
			} else {
				definition.add("invulnerability", array);
			}
			minecraft.setScreen(parent);
		}).active = !invalidSource;
		button(left + cell + 6, footerY(), cell, Component.translatable("gui.cancel"), this::onClose);
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (key == GLFW.GLFW_KEY_ENTER) {
			rebuild();
			return true;
		}
		return super.keyPressed(key, scan, modifiers);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
