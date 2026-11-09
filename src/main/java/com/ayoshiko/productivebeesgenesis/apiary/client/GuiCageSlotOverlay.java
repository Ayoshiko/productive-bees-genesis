package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiTextureOnlyElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/** 空蜂笼槽使用 PB 的运行时图标；上下箭头区分放入与取出，边框由机器菜单提供。 */
public class GuiCageSlotOverlay extends GuiTextureOnlyElement {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ProductiveBeesGenesis.MOD_ID, "runtime/slot/bee_cage");
    private static final ResourceLocation SOURCE = ResourceLocation.fromNamespaceAndPath(
            "productivebees", "textures/item/bee_cage/bee_cage.png");
    private final Supplier<Boolean> isEmptyChecker;
    private final boolean input;

    public static GuiCageSlotOverlay input(IGuiWrapper gui, int slotX, int slotY, Supplier<Boolean> isEmptyChecker) {
        GuiCageSlotOverlay overlay = new GuiCageSlotOverlay(gui, slotX, slotY, isEmptyChecker, true);
        overlay.setTooltip(Tooltip.create(
                Component.translatable("gui.productivebeesgenesis.apiary.cage_in_slot.tooltip")));
        return overlay;
    }

    public static GuiCageSlotOverlay output(IGuiWrapper gui, int slotX, int slotY, Supplier<Boolean> isEmptyChecker) {
        return new GuiCageSlotOverlay(gui, slotX, slotY, isEmptyChecker, false);
    }

    private GuiCageSlotOverlay(IGuiWrapper gui, int slotX, int slotY,
            Supplier<Boolean> isEmptyChecker, boolean input) {
        super(TEXTURE, gui, slotX, slotY, 16, 16);
        this.isEmptyChecker = isEmptyChecker;
        this.input = input;
        PbSlotHintTexture.register(TEXTURE, SOURCE);
    }

    @Override
    public void drawBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (!isEmptyChecker.get()) {
            return;
        }
        super.drawBackground(graphics, mouseX, mouseY, partialTicks);
        int center = relativeX + 12;
        int top = relativeY + 8;
        int color = 0xFF454545;
        graphics.fill(center, top + 1, center + 1, top + 6, color);
        for (int row = 0; row < 3; row++) {
            int halfWidth = input ? 2 - row : row;
            int y = top + (input ? 4 : 0) + row;
            graphics.fill(center - halfWidth, y, center + halfWidth + 1, y + 1, color);
        }
    }
}
