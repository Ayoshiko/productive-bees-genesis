package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

final class DesignerStackButton extends Button {
	private final ItemStack stack;
	private final String fallback;
	private final Component name;
	private boolean square;

	DesignerStackButton(int x, int y, int width, ItemStack stack, String fallback, Runnable clicked) {
		super(x, y, width, 20, Component.empty(), ignored -> clicked.run(), DEFAULT_NARRATION);
		this.stack = stack.copyWithCount(1);
		this.fallback = fallback;
		name = stack.isEmpty() ? Component.literal(fallback) : BeeDesignerClient.itemName(stack).copy();
		setMessage(name);
		Component tooltip = stack.isEmpty() ? name : name.copy()
				.append("\n" + net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()));
		if (stack.isEmpty()) {
			setTooltip(Tooltip.create(tooltip));
		}
	}

	DesignerStackButton square() {
		square = true;
		setHeight(getWidth());
		return this;
	}

	static ItemStack decode(JsonElement value) {
		try {
			if (value == null || !value.isJsonObject() || Minecraft.getInstance().level == null) {
				return ItemStack.EMPTY;
			}
			JsonObject json = value.getAsJsonObject().deepCopy();
			if (json.has("tag") || json.has("type")) {
				var ingredient = net.minecraft.world.item.crafting.Ingredient.CODEC.parse(
						Minecraft.getInstance().level.registryAccess().createSerializationContext(JsonOps.INSTANCE), json).result();
				if (ingredient.isPresent() && ingredient.get().getItems().length > 0) {
					return ingredient.get().getItems()[0].copyWithCount(1);
				}
			}
			if (json.has("item") && json.get("item").isJsonPrimitive()) {
				json.add("id", json.remove("item"));
			} else if (json.has("items") && json.get("items").isJsonPrimitive()) {
				json.add("id", json.remove("items"));
			}
			return ItemStack.CODEC.parse(Minecraft.getInstance().level.registryAccess().createSerializationContext(JsonOps.INSTANCE),
					json).result().orElse(ItemStack.EMPTY);
		} catch (RuntimeException invalid) {
			return ItemStack.EMPTY;
		}
	}

	void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!stack.isEmpty()) {
			ItemStack display = stack.copy();
			display.set(DataComponents.CUSTOM_NAME, name);
			graphics.renderTooltip(Minecraft.getInstance().font, display, mouseX, mouseY);
		}
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "input", getX(), getY(), getWidth(), getHeight());
		if (square) {
			if (!stack.isEmpty()) {
				int size = Math.max(16, Math.min(24, width - 8));
				graphics.pose().pushPose();
				graphics.pose().translate(getX() + (width - size) / 2F, getY() + (height - size) / 2F, 0);
				graphics.pose().scale(size / 16F, size / 16F, 1);
				graphics.renderItem(stack, 0, 0);
				graphics.pose().popPose();
			} else {
				graphics.drawString(Minecraft.getInstance().font, "+", getX() + width / 2 - 2, getY() + height / 2 - 4,
						DesignerFormScreen.MUTED, false);
			}
			return;
		}
		DesignerTheme.panel(graphics, "slot", getX() + 1, getY() + 1, 18, 18);
		var font = Minecraft.getInstance().font;
		if (!stack.isEmpty()) {
			graphics.renderItem(stack, getX() + 3, getY() + 2);
		}
		graphics.drawString(font, font.plainSubstrByWidth(name.getString(), getWidth() - 30), getX() + 25, getY() + 6,
				DesignerFormScreen.FOREGROUND, false);
	}
}
