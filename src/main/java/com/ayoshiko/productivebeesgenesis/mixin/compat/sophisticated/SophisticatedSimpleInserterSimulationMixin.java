package com.ayoshiko.productivebeesgenesis.mixin.compat.sophisticated;

import com.ayoshiko.productivebeesgenesis.logistics.ItemPushHelper;
import com.ayoshiko.productivebeesgenesis.logistics.WholeInventoryItemSimulation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** 将精妙已有的整体模拟能力接到自有接口，不把可选类型带入常驻物流类。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.IItemHandlerSimpleInserter", remap = false)
public interface SophisticatedSimpleInserterSimulationMixin extends WholeInventoryItemSimulation {

	@Shadow(remap = false)
	ItemStack insertItem(ItemStack stack, boolean simulate);

	@Override
	default int productivebeesgenesis$simulateWholeItemInsert(ItemStack stack) {
		return ItemPushHelper.accepted(stack, insertItem(stack.copy(), true));
	}
}
