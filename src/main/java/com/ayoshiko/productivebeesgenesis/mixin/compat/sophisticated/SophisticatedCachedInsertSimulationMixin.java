package com.ayoshiko.productivebeesgenesis.mixin.compat.sophisticated;

import com.ayoshiko.productivebeesgenesis.logistics.WholeInventoryItemSimulation;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

/** 保留外部能力包装；每次读取当前过滤处理器，升级重建后不持有旧库存。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler", remap = false)
public abstract class SophisticatedCachedInsertSimulationMixin implements WholeInventoryItemSimulation {

	@Shadow(remap = false)
	@Final
	private Supplier<IItemHandler> wrappedHandlerGetter;

	@Override
	public int productivebeesgenesis$simulateWholeItemInsert(ItemStack stack) {
		// 未知子类可能在逐槽入口追加限制，不能继承本类已核对的模拟捷径。
		if (!getClass().getName().equals(
				"net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler")) {
			return UNAVAILABLE;
		}
		IItemHandler current = wrappedHandlerGetter.get();
		if (current instanceof WholeInventoryItemSimulation simulation) {
			return simulation.productivebeesgenesis$simulateWholeItemInsert(stack);
		}
		return UNAVAILABLE;
	}
}
