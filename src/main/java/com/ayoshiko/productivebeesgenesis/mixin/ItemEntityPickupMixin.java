package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessPickupService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemEntity.class)
public abstract class ItemEntityPickupMixin {
    // 此调用点位于全部 Pre 监听器之后；拒绝拾取不会到达这里，部分背包接收也不追加直入。
    @WrapOperation(method = "playerTouch", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"))
    private boolean productivebeesgenesis$pickup(Inventory inventory, ItemStack stack, Operation<Boolean> original, Player player) {
        return original.call(inventory, stack) || WirelessPickupService.overflow(player, inventory, (ItemEntity) (Object) this, stack);
    }
}
