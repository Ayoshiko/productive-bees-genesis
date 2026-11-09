package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.util.BeeCreator;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = BeeCreator.class, remap = false)
public abstract class BeeContainerFlowerDataMixin {
    @Inject(method = "create", at = @At("RETURN"))
    private static void productivebeesgenesis$containerFlower(ResourceLocation bee, JsonObject json,
                                                            CallbackInfoReturnable<CompoundTag> ci) {
        ContainerFlower.attach(bee, json, ci.getReturnValue());
    }
}
