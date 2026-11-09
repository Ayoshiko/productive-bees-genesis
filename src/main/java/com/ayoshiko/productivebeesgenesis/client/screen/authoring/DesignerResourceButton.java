package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/** 原生资源与 AEKey 复用正式资源显示器；无效草稿仍可点击修正。 */
final class DesignerResourceButton extends DesignerTheme.Action {
	private final JsonObject output;
	private final boolean fluid;
	private JsonObject observed;
	private long recipeVersion;
	private Language language;
	private FluidStack fluidStack = FluidStack.EMPTY;
	private ResourceProductionRecipe.Output resource;
	private Component label = Component.empty();

	DesignerResourceButton(int x, int y, int width, JsonObject output, boolean fluid, Runnable action) {
		super(x, y, width, Component.empty(), action);
		setHeight(20);
		this.output = output;
		this.fluid = fluid;
	}

	DesignerResourceButton fitted() {
		refreshDisplay();
		setWidth(Math.min(getWidth(), Math.max(104, Math.min(260, DesignerFonts.body().width(label) + 38))));
		return this;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		DesignerTheme.panel(graphics, isHoveredOrFocused() ? "button_hover" : "input", getX(), getY(), getWidth(), 20);
		DesignerTheme.panel(graphics, "slot", getX() + 1, getY() + 1, 18, 18);
		refreshDisplay();
		if (!fluidStack.isEmpty()) {
			drawFluid(graphics, fluidStack, getX() + 2, getY() + 2);
		} else if (resource != null) {
			graphics.pose().pushPose();
			graphics.pose().translate(getX() + 2, getY() + 2, 0);
			ResourceVisuals.draw(graphics, resource.key());
			graphics.pose().popPose();
		}
		graphics.drawString(DesignerFonts.body(), DesignerFonts.body().plainSubstrByWidth(label.getString(), getWidth() - 28),
				getX() + 24, getY() + 6, DesignerFormScreen.FOREGROUND, false);
	}

	private void refreshDisplay() {
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		Language currentLanguage = Language.getInstance();
		if (output.equals(observed) && recipeVersion == version && language == currentLanguage) {
			return;
		}
		observed = output.deepCopy();
		recipeVersion = version;
		language = currentLanguage;
		fluidStack = FluidStack.EMPTY;
		resource = null;
		try {
			if (fluid) {
				fluidStack = SizedFluidIngredient.FLAT_CODEC.parse(Minecraft.getInstance().level.registryAccess()
						.createSerializationContext(JsonOps.INSTANCE), output).getOrThrow().getFluids()[0];
				label = fluidStack.getHoverName().copy().append(" · " + fluidStack.getAmount() + " mB");
			} else {
				resource = ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, output).getOrThrow();
				label = ResourceVisuals.name(resource.key()).copy().append(" · ")
						.append(ResourceVisuals.quantity(resource.key(), resource.exactAmount(), 1));
			}
		} catch (RuntimeException exception) {
			// 无效草稿保留身份供用户修正，避免每帧重复解码同一失败值。
			fluidStack = FluidStack.EMPTY;
			resource = null;
			label = Component.literal(BeeDesignerDocument.text(output, "kind", fluid ? "fluid" : "?") + " · "
					+ BeeDesignerDocument.text(output, "id", BeeDesignerDocument.text(output, "fluid", "")));
		}
		setMessage(label);
		setTooltip(Tooltip.create(label));
	}

	static void drawFluid(GuiGraphics graphics, FluidStack stack, int x, int y) {
		var extensions = IClientFluidTypeExtensions.of(stack.getFluid());
		ResourceLocation texture = extensions.getStillTexture(stack);
		if (texture == null) {
			return;
		}
		var sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
		int color = extensions.getTintColor(stack);
		graphics.setColor((color >> 16 & 255) / 255F, (color >> 8 & 255) / 255F, (color & 255) / 255F, 1);
		graphics.blit(x, y, 0, 16, 16, sprite);
		graphics.setColor(1, 1, 1, 1);
	}
}
