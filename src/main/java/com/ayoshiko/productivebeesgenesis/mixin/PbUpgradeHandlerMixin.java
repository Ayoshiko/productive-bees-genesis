package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.CollectValidUpgradesEventHandler;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = InventoryHandlerHelper.BlockEntityItemStackHandler.class, remap = false)
public abstract class PbUpgradeHandlerMixin {
    @Shadow protected BlockEntity blockEntity;

    @Inject(method = "isItemValid(ILnet/minecraft/world/item/ItemStack;Z)Z", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$validateFunctionalUpgrade(int slot, ItemStack stack, boolean internal,
            CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof InventoryHandlerHelper.UpgradeHandler)) return;
        Boolean valid = CollectValidUpgradesEventHandler.nativeUpgradeValidity(blockEntity, stack);
        if (valid != null) cir.setReturnValue(valid);
    }
}
