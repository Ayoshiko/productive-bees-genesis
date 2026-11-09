package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import com.ayoshiko.productivebeesgenesis.inventory.BulkItemPullScope;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Create 创造板条箱仅在本模组拉取时批量返回；保留原有空模板、槽位和模拟规则。 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.crate.BottomlessItemHandler", remap = false)
public abstract class CreateBottomlessBulkPullMixin {

	@WrapOperation(method = "extractItem", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;getMaxStackSize()I"), require = 0)
	private int productivebeesgenesis$bulkPullLimit(ItemStack stack, Operation<Integer> original,
			int slot, int amount, boolean simulate) {
		if (slot == 0 && amount > 0
				&& getClass().getName().equals("com.simibubi.create.content.logistics.crate.BottomlessItemHandler")
				&& BulkItemPullScope.matchesTarget(stack)) return amount;
		return original.call(stack);
	}
}
