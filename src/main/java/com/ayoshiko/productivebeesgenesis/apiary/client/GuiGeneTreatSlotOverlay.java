package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiTextureOnlyElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.function.BooleanSupplier;

/** 小食槽为空时显示灰色物品轮廓，槽框继续由 MEK 绘制。 */
final class GuiGeneTreatSlotOverlay extends GuiTextureOnlyElement {

	private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
			ProductiveBeesGenesis.MOD_ID, "textures/gui/slot/gene_treat.png");
	private final BooleanSupplier empty;

	GuiGeneTreatSlotOverlay(IGuiWrapper gui, BooleanSupplier empty) {
		super(TEXTURE, gui, ApiaryGuiLayoutHelper.GENE_TREAT_X,
				ApiaryGuiLayoutHelper.GENE_TREAT_Y, 16, 16);
		this.empty = empty;
		setTooltip(Tooltip.create(Component.translatable(
				"gui.productivebeesgenesis.apiary.gene_treat_slot.tooltip")));
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
		if (empty.getAsBoolean()) {
			super.drawBackground(graphics, mouseX, mouseY, partialTicks);
		}
	}
}
