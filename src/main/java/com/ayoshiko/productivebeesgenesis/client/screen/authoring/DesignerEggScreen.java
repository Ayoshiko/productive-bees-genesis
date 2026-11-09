package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

/** 蜂蛋在本地副本上编辑，确认时验证真实 ItemStack codec 与蜂种身份。 */
final class DesignerEggScreen extends DesignerFormScreen {

	private final Screen parent;
	private final Consumer<JsonObject> chosen;
	private JsonObject output;
	private final JsonObject options = new JsonObject();
	private final JsonObject originalOutput;
	private final String initial;

	DesignerEggScreen(Screen parent, JsonObject output, Consumer<JsonObject> chosen) {
		super(tr("egg_output"));
		this.parent = parent;
		editorParent = parent;
		this.chosen = chosen;
		originalOutput = output;
		this.output = BeeDesignerEggs.normalize(output);
		initial = this.output.toString();
		var bee = BeeDesignerEggs.bee(this.output);
		options.addProperty("bee", bee == null ? "" : bee.toString());
	}

	@Override
	protected void init() {
		begin();
		section("egg_identity");
		rows.add(new Row("egg_output", (x, y, w) -> virtualItem(x, y, w, DesignerStackButton.decode(output),
				tr("choose_bee_egg").getString(), stack -> {
			var bee = BeeDesignerEggs.bee(stack);
			if (bee == null) {
				throw new IllegalArgumentException("designer.error.invalid_bee_egg");
			}
			output = ItemStack.CODEC.encodeStart(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), stack).getOrThrow().getAsJsonObject();
			options.addProperty("bee", bee.toString());
		})));
		rows.add(new Row("bee_id", (x, y, w) -> button(x, y, w,
				BeeDesignerScreen.beeName(BeeDesignerDocument.text(options, "bee", "minecraft:bee")), () -> minecraft.setScreen(
						new DesignerBeePicker(this, id -> {
							output = BeeDesignerEggs.retarget(output, id);
							options.addProperty("bee", id);
						})))));
		field("bee_id", options, "bee", "");
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("use_bee_id"), () -> {
			try {
				output = BeeDesignerEggs.retarget(output, options.get("bee").getAsString());
				rebuild();
			} catch (RuntimeException failure) {
				status = failure.getMessage();
			}
		})));
		number("egg_count", output, "count", "1");
		rows.add(new Row("components", (x, y, w) -> button(x, y, w, tr("edit_json"), () ->
				minecraft.setScreen(new DesignerJsonScreen(this, output, tr("egg_components").getString())))));
		note("", tr("egg_edit_help"));
		finishRows();
		primaryButton(left, footerY(), 100, Component.translatable("gui.done"), () -> {
			try {
				JsonObject normalized = BeeDesignerEggs.normalize(output);
				BeeDesignerEggs.validate(normalized, minecraft.level.registryAccess());
				chosen.accept(normalized);
				minecraft.setScreen(parent);
			} catch (RuntimeException failure) {
				status = failure.getMessage();
			}
		});
		button(left + contentWidth - 90, footerY(), 90, Component.translatable("gui.cancel"), this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected boolean prepareWindowClose() {
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			try {
				JsonObject normalized = BeeDesignerEggs.normalize(output);
				BeeDesignerEggs.validate(normalized, minecraft.level.registryAccess());
				chosen.accept(normalized);
				continueWindowClose();
			} catch (RuntimeException failure) {
				status = failure.getMessage();
			}
		}), this::continueWindowClose, () -> resumePage(() -> {})));
		return false;
	}

	@Override
	protected String pendingDraftState() {
		return initial.equals(output.toString()) ? "" : output.toString();
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		if (originalOutput != null) {
			snapshot.replace(originalOutput, output);
			snapshot.bind(output, snapshot.target(originalOutput));
		}
	}
}
