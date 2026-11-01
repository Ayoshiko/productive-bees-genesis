package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.resource.MyriadGuideLink;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 同一 PB 物品的不同蜂种必须重新匹配，避免热键沿用上一只蜂的页面。 */
@org.spongepowered.asm.mixin.Pseudo
@Mixin(targets = "guideme.internal.hotkey.OpenGuideHotkey", remap = false)
public abstract class MyriadGuideHotkeyMixin {
    @Shadow private static ResourceLocation previousItemId;
    @Unique private static ItemStack pbg$hovered = ItemStack.EMPTY;
    @Unique private static final ResourceLocation pbg$myriadPage =
            new ResourceLocation("productivebeesgenesis", "myriad.md");

    @Inject(method = "update", at = @At("HEAD"))
    private static void pbg$trackComponents(ItemStack stack, CallbackInfo ci) {
        if (!ItemStack.isSameItemSameTags(pbg$hovered, stack)) {
            pbg$hovered = stack.copyWithCount(1);
            previousItemId = null;
        }
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
            target = "Lguideme/indices/ItemIndex;get(Ljava/lang/Object;)Ljava/lang/Object;"))
    private static Object pbg$filterMyriad(@Coerce Object index, Object id, Operation<Object> original) {
        var result = original.call(index, id);
        if (result == null || MyriadGuideLink.matches(pbg$hovered)) return result;
        // 仅检查可选索引返回的锚点，不将 GuideME 类型写入本模组的类签名。
        if (result.getClass().getName().equals("guideme.PageAnchor")) {
            try {
                if (pbg$myriadPage.equals(result.getClass().getMethod("pageId").invoke(result))) return null;
            } catch (ReflectiveOperationException incompatible) {
                throw new IllegalStateException("Cannot read GuideME page identity", incompatible);
            }
        }
        return result;
    }
}
