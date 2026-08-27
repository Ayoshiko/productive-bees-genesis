package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.screen.CompactStackCountScreen;
import com.ayoshiko.productivebeesgenesis.client.screen.LargeStackCountRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.slot.InventoryContainerSlot;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 将紧凑数量渲染严格限定到本模组机器容器的输入和输出槽。
 * <br/>
 * 本 Mixin 只包裹单个槽位内容中的物品装饰调用，不修改全局 {@link GuiGraphics}；
 * 玩家背包槽、能量槽、光标物品、tooltip、JEI 叠加层以及其他界面均保留原版行为。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerSlotCountMixin {

	/**
	 * 只为本模组机器的 INPUT/OUTPUT 槽替换数量文字，同时保留原版耐久条等装饰。
	 */
	@WrapOperation(
		method = "renderSlotContents",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/gui/GuiGraphics;renderItemDecorations("
					+ "Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;"
					+ "IILjava/lang/String;)V"
		),
		require = 1
	)
	private void productivebeesgenesis$renderMachineSlotCount(GuiGraphics instance,
			Font font, ItemStack stack, int x, int y, String countText,
			Operation<Void> original, GuiGraphics methodGraphics, ItemStack methodStack,
			Slot slot, String methodCountText) {
		if (!productivebeesgenesis$isMachineInputOrOutput(slot)
				|| countText != null || stack.isEmpty() || stack.getCount() < 2) {
			original.call(instance, font, stack, x, y, countText);
			return;
		}
		// 空字符串保留原版耐久条等装饰，但抑制原版数量文字，随后绘制统一字号标签。
		original.call(instance, font, stack, x, y, "");
		LargeStackCountRenderer.renderCountAt(instance, font, x, y, stack.getCount());
	}

	private boolean productivebeesgenesis$isMachineInputOrOutput(Slot slot) {
		if (!((Object) this instanceof CompactStackCountScreen)
				|| !(slot instanceof InventoryContainerSlot mekanismSlot)) {
			return false;
		}
		ContainerSlotType slotType = mekanismSlot.getSlotType();
		return slotType == ContainerSlotType.INPUT || slotType == ContainerSlotType.OUTPUT;
	}
}
