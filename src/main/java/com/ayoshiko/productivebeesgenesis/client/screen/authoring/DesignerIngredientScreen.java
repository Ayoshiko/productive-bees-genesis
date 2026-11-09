package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerSelections;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.function.Consumer;

/** 材料匹配方式必须显式选择；组件材料不能降级成宽泛物品身份。 */
final class DesignerIngredientScreen extends DesignerFormScreen {
	private final Screen parent;
	private final Consumer<JsonElement> chosen;
	private final JsonObject options = new JsonObject();
	private ItemStack stack;
	private final boolean nestsFirst;
	private JsonObject draftOwner;
	private String draftField;
	private final JsonElement originalIngredient;
	private String initial;

	DesignerIngredientScreen(Screen parent, JsonElement ingredient, Consumer<JsonElement> chosen) {
		this(parent, ingredient, chosen, false);
	}

	DesignerIngredientScreen(Screen parent, JsonElement ingredient, Consumer<JsonElement> chosen, boolean nestsFirst) {
		super(tr("material"));
		this.parent = parent;
		editorParent = parent;
		this.chosen = chosen;
		this.nestsFirst = nestsFirst;
		originalIngredient = ingredient;
		this.stack = DesignerStackButton.decode(ingredient);
		String mode = stack.getComponentsPatch().isEmpty() ? "item" : "components";
		if (ingredient != null && ingredient.isJsonObject() && ingredient.getAsJsonObject().has("tag")) {
			mode = "tag";
		}
		options.addProperty("mode", mode);
		options.addProperty("tag", ingredient != null && ingredient.isJsonObject()
				? com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument.text(ingredient.getAsJsonObject(), "tag", "") : "");
		initial = draftState();
	}

	DesignerIngredientScreen(Screen parent, JsonObject owner, String field, Consumer<JsonElement> chosen, boolean nestsFirst) {
		this(parent, owner.get(field), chosen, nestsFirst);
		draftOwner = owner;
		draftField = field;
	}

	private String draftState() {
		return options + "\n" + stack;
	}

	@Override
	protected String pendingDraftState() {
		String state = draftState();
		return initial.equals(state) ? "" : state;
	}

	@Override
	protected void snapshotDraft(DesignerDraftSnapshot snapshot) {
		JsonElement selected;
		if (options.get("mode").getAsString().equals("tag")) {
			JsonObject tag = new JsonObject();
			tag.addProperty("tag", options.get("tag").getAsString());
			selected = tag;
		} else if (!stack.isEmpty()) {
			selected = BeeDesignerSelections.ingredient(selection(stack), minecraft.level.registryAccess(), options.get("mode").getAsString().equals("components"));
		} else {
			return;
		}
		JsonObject owner = draftOwner == null ? null : snapshot.target(draftOwner);
		if (owner != null) {
			owner.add(draftField, selected);
		} else {
			snapshot.replaceValue(originalIngredient, selected);
		}
	}

	@Override
	protected void init() {
		begin();
		rows.add(new Row("bee_material", (x, y, w) -> button(x, y, w, tr("choose_bee_egg"), () -> minecraft.setScreen(
				new DesignerBeePicker(this, id -> {
					stack = BeeDesignerEggs.egg(id);
					options.addProperty("mode", "components");
				})))));
		choice("match_mode", options, "mode", "components", "item", "components", "tag");
		if (options.get("mode").getAsString().equals("tag")) {
			rows.add(new Row("tag_id", (x, y, w) -> button(x, y, w, Component.literal(options.get("tag").getAsString()), () ->
					minecraft.setScreen(DesignerResourcePicker.open(this, "item_tag", value -> options.add("tag", value.get("id")))))));
			field("tag_id", options, "tag", "");
		} else {
			rows.add(new Row("material", (x, y, w) -> virtualItem(x, y, w, stack, tr("pick_item").getString(), selected -> {
				stack = selected.copyWithCount(1);
				if (!stack.getComponentsPatch().isEmpty()) {
					options.addProperty("mode", "components");
				}
			}, nestsFirst)));
		}
		rows.add(new Row("", (x, y, w) -> button(x, y, w, tr("clear_slot"), () -> {
			chosen.accept(null);
			minecraft.setScreen(parent);
		})));
		note("components", tr("component_material_help"));
		finishRows();
		button(left, footerY(), 90, Component.translatable("gui.done"), () -> {
			try {
				JsonElement result;
				if (options.get("mode").getAsString().equals("tag")) {
					JsonObject tag = new JsonObject();
					tag.addProperty("tag", options.get("tag").getAsString());
					Ingredient decoded = Ingredient.CODEC.parse(minecraft.level.registryAccess().createSerializationContext(JsonOps.INSTANCE), tag).getOrThrow();
					if (decoded.getItems().length == 0) {
						throw new IllegalArgumentException("designer.error.empty_tag");
					}
					result = tag;
				} else {
					result = BeeDesignerSelections.ingredient(selection(stack), minecraft.level.registryAccess(), options.get("mode").getAsString().equals("components"));
				}
				chosen.accept(result);
				minecraft.setScreen(parent);
			} catch (RuntimeException exception) {
				status = exception.getMessage();
			}
		});
		button(left + contentWidth - 80, footerY(), 80, Component.translatable("gui.cancel"), this::onClose);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	protected boolean prepareWindowClose() {
		minecraft.setScreen(new DesignerConfirmationScreen(tr("close_page_edits"), tr("close_page_edits_help"), () -> resumePage(() -> {
			try {
				JsonElement result;
				if (options.get("mode").getAsString().equals("tag")) {
					JsonObject tag = new JsonObject();
					tag.addProperty("tag", options.get("tag").getAsString());
					result = tag;
				} else {
					result = BeeDesignerSelections.ingredient(selection(stack), minecraft.level.registryAccess(), options.get("mode").getAsString().equals("components"));
				}
				chosen.accept(result);
				continueWindowClose();
			} catch (RuntimeException failure) {
				status = failure.getMessage();
			}
		}), this::continueWindowClose, () -> resumePage(() -> {})));
		return false;
	}
}
