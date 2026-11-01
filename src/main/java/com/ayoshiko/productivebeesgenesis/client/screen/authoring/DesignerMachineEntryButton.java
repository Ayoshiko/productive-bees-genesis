package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import com.ayoshiko.productivebeesgenesis.port.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import com.ayoshiko.productivebeesgenesis.port.SizedFluidIngredient;

/** 卡片只缓存该次页面构建的预览，未知材料始终保留可点击的原始声明。 */
final class DesignerMachineEntryButton extends DesignerTheme.Action {
	private ItemStack item = ItemStack.EMPTY;
	private FluidStack fluid = FluidStack.EMPTY;
	private ResourceProductionRecipe.Output resource;
	private final Component name;
	private Component label;

	DesignerMachineEntryButton(int x, int y, int width, BeeDesignerMachineRecipes.Entry entry, Runnable action) {
		super(x, y, width, Component.empty(), action);
		setHeight(20);
		Component name = Component.translatable("designer.option." + entry.kind());
		try {
			var registries = Minecraft.getInstance().level.registryAccess();
			if (entry.kind().equals("item")) {
				item = BeeDesignerMachineRecipes.decodeItem(entry, registries);
				name = item.isEmpty() ? name : BeeDesignerClient.itemName(item);
			} else if (entry.kind().equals("fluid")) {
				JsonObject preview = entry.value().deepCopy();
				preview.addProperty("amount", 1);
				fluid = SizedFluidIngredient.FLAT_CODEC.parse(JsonOps.INSTANCE, preview)
						.getOrThrow(false, message -> {}).getFluids()[0];
				name = fluid.getDisplayName();
			} else if (entry.kind().equals("chemical") || entry.kind().equals("energy")) {
				JsonObject output = new JsonObject();
				output.addProperty("kind", entry.kind());
				output.addProperty("amount", "1");
				if (entry.kind().equals("chemical")) {
					output.addProperty("id", chemicalId(entry.value()));
				} else {
					output.addProperty("energy_type", "FE");
				}
				resource = ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, output).getOrThrow(false, message -> {});
				name = ResourceVisuals.name(resource.key());
			}
		} catch (RuntimeException failure) {
			name = Component.translatable("designer.machine_invalid_material");
		}
		String amount = BeeDesignerDocument.text(entry.value(), entry.amountKey(), "1");
		String chance = BeeDesignerDocument.text(entry.node(), entry.chanceKey(), "1");
		this.name = name.copy();
		label = name.copy().append(" × " + amount).append(chance.equals("1") ? "" : " · " + chance);
		setMessage(label);
		if (item.isEmpty()) {
			setTooltip(Tooltip.create(label));
		}
	}

	void renderDetails(GuiGraphics graphics, int mouseX, int mouseY) {
		if (!item.isEmpty()) {
			ItemStack display = item.copy();
			com.ayoshiko.productivebeesgenesis.port.ForgeComponents.set(display, DataComponents.CUSTOM_NAME, BeeDesignerClient.itemName(item));
			graphics.renderTooltip(DesignerFonts.body(), display, mouseX, mouseY);
		}
	}

	Component summary() {
		return label;
	}

	DesignerMachineEntryButton fitted() {
		setWidth(Math.min(getWidth(), Math.max(104, Math.min(300, DesignerFonts.body().width(label) + 34))));
		return this;
	}

	DesignerMachineEntryButton identityOnly() {
		label = name;
		setMessage(label);
		if (item.isEmpty()) {
			setTooltip(Tooltip.create(label));
		}
		return this;
	}

	private static String chemicalId(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject object = value.getAsJsonObject();
			if (object.has("chemical") && object.get("chemical").isJsonPrimitive()) {
				return object.get("chemical").getAsString();
			}
			for (var entry : object.entrySet()) {
				String id = chemicalId(entry.getValue());
				if (!id.isEmpty()) {
					return id;
				}
			}
		}
		return "";
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "input", getX(), getY(), getWidth(), 20);
		if (!item.isEmpty()) {
			graphics.renderItem(item, getX() + 2, getY() + 2);
		} else if (!fluid.isEmpty()) {
			DesignerResourceButton.drawFluid(graphics, fluid, getX() + 2, getY() + 2);
		} else if (resource != null) {
			graphics.pose().pushPose();
			graphics.pose().translate(getX() + 2, getY() + 2, 0);
			ResourceVisuals.draw(graphics, resource.key());
			graphics.pose().popPose();
		}
		var font = DesignerFonts.body();
		graphics.drawString(font, font.plainSubstrByWidth(label.getString(), getWidth() - 26), getX() + 23, getY() + 6,
				DesignerFormScreen.FOREGROUND, false);
	}
}
