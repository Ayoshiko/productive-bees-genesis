package com.ayoshiko.productivebeesgenesis.mixin.compat.sophisticated;

import com.ayoshiko.productivebeesgenesis.logistics.WholeInventoryItemSimulation;
import com.ayoshiko.productivebeesgenesis.logistics.ItemPushHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** 保留外部能力包装；每次读取当前过滤处理器，升级重建后不持有旧库存。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler", remap = false)
public abstract class SophisticatedCachedInsertSimulationMixin implements WholeInventoryItemSimulation {

	@Shadow(remap = false)
	@Final
	private Supplier<IItemHandler> wrappedHandlerGetter;

	@Shadow(remap = false)
	public abstract ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

	@Unique
	private ItemStack productivebeesgenesis$wholeInsertRequest;

	@Unique
	private boolean productivebeesgenesis$wholeInsertUnavailable;

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

	@Override
	public int productivebeesgenesis$executeWholeItemInsert(ItemStack stack) {
		if (!getClass().getName().equals(
				"net.p3pp3rf1y.sophisticatedcore.inventory.CachedFailedInsertInventoryHandler")) return UNAVAILABLE;
		productivebeesgenesis$wholeInsertRequest = stack;
		productivebeesgenesis$wholeInsertUnavailable = false;
		try {
			ItemStack remaining = insertItem(0, stack, false);
			return productivebeesgenesis$wholeInsertUnavailable ? UNAVAILABLE : ItemPushHelper.accepted(stack, remaining);
		} finally {
			productivebeesgenesis$wholeInsertRequest = null;
			productivebeesgenesis$wholeInsertUnavailable = false;
		}
	}

	/** 只替换当前整库存请求的内层调用，外层原缓存、当前 supplier 与升级过滤照常执行。 */
	@WrapOperation(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",
			at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"), remap = false)
	private ItemStack productivebeesgenesis$insertWholeThroughCurrentDelegate(IItemHandlerModifiable current,
			int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
		if (simulate || stack != productivebeesgenesis$wholeInsertRequest) return original.call(current, slot, stack, simulate);
		if (current instanceof WholeInventoryItemSimulation insertion) {
			int accepted = insertion.productivebeesgenesis$executeWholeItemInsert(stack);
			if (accepted != UNAVAILABLE) {
				if (accepted < 0 || accepted > stack.getCount()) throw new IllegalStateException("Invalid whole-inventory insert receipt");
				return accepted == 0 ? stack : stack.copyWithCount(stack.getCount() - accepted);
			}
		}
		productivebeesgenesis$wholeInsertUnavailable = true;
		// 原包装只按同一个栈引用记录拒收；回退不是拒收，不污染它的本刻失败缓存。
		return stack.copy();
	}
}
