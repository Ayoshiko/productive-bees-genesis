package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiTextureOnlyElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
	 * 蜂笼槽纹理叠加层 — 仅渲染 modularbees 风格的 16×16 纹理
	 * <br/>
	 * 不渲染任何槽位边框，叠加在 MEK dynamicSlots 自动渲染的槽位边框之上。
	 * 原理：dynamicSlots=true 时 MEK 的 addSlots() 已为 cageInSlot / cageOutSlot 自动创建
	 * GuiSlot(SlotType.INPUT / SlotType.OUTPUT)，此处仅叠加 modularbees 纹理实现视觉区分，
	 * 避免重复渲染槽位边框导致重叠。
	 * <br/>
	 * 渲染控制：仅当槽位为空时渲染纹理，避免遮挡槽位中的蜜蜂笼物品。
	 */
public class GuiCageSlotOverlay extends GuiTextureOnlyElement {

	/** modularbees 输入蜂笼槽位纹理（16×16，含灰色边框和向下箭头图案） */
	private static final ResourceLocation INPUT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
			ProductiveBeesGenesis.MOD_ID, "textures/gui/slot/cage_slot_input.png");

	/** modularbees 输出蜂笼槽位纹理（16×16，含灰色边框和向上箭头图案） */
	private static final ResourceLocation OUTPUT_TEXTURE = ResourceLocation.fromNamespaceAndPath(
			ProductiveBeesGenesis.MOD_ID, "textures/gui/slot/cage_slot_output.png");

	/** 纹理尺寸（16×16） */
	private static final int TEXTURE_SIZE = 16;

	/** 槽位空状态检查器 — 为空时才渲染纹理，避免遮挡槽内物品 */
	private final Supplier<Boolean> isEmptyChecker;

	/** 创建输入槽 Overlay（叠加在 cageInSlot 之上） */
	public static GuiCageSlotOverlay input(IGuiWrapper gui, int slotX, int slotY, Supplier<Boolean> isEmptyChecker) {
		return new GuiCageSlotOverlay(INPUT_TEXTURE, gui, slotX, slotY, isEmptyChecker);
	}

	/** 创建输出槽 Overlay（叠加在 cageOutSlot 之上） */
	public static GuiCageSlotOverlay output(IGuiWrapper gui, int slotX, int slotY, Supplier<Boolean> isEmptyChecker) {
		return new GuiCageSlotOverlay(OUTPUT_TEXTURE, gui, slotX, slotY, isEmptyChecker);
	}

	/** 私有构造：定位于槽位内部区域（slot.x, slot.y），即自动渲染的 18×18 槽位边框内的 16×16 区域 */
	private GuiCageSlotOverlay(ResourceLocation texture, IGuiWrapper gui, int slotX, int slotY,
								Supplier<Boolean> isEmptyChecker) {
		super(texture, gui, slotX, slotY, TEXTURE_SIZE, TEXTURE_SIZE);
		this.isEmptyChecker = isEmptyChecker;
	}

	/**
	 * 仅当槽位为空时渲染纹理
	 * <br/>
	 * 槽位有物品时跳过渲染，避免 16×16 纹理遮挡蜜蜂笼物品图标。
	 */
	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		if (isEmptyChecker.get()) {
			super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		}
	}
}
