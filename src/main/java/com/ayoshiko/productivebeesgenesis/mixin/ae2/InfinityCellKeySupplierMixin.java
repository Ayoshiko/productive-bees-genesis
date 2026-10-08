package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.stacks.AEKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.GenerationMemoizedSupplier;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MEInfinityCell 的固定物品/流体配置只需每代解析一次 AEKey。
 * 不缓存 KeyList.contains 或任意脚本 Supplier，保留动态列表和动态供给语义。
 */
@Pseudo
@Mixin(targets = "net.yxiao233.meinfinitycell.common.compact.kubejs.helper.AEKeyHelper", remap = false)
public abstract class InfinityCellKeySupplierMixin {

	@Inject(method = {
			"item(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/function/Supplier;",
			"item(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/core/component/DataComponentMap;)Ljava/util/function/Supplier;",
			"fluid(Lnet/minecraft/resources/ResourceLocation;)Ljava/util/function/Supplier;",
			"fluid(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/core/component/DataComponentMap;)Ljava/util/function/Supplier;"
	}, at = @At("RETURN"), cancellable = true, require = 0)
	private static void productivebeesgenesis$cacheConfiguredKey(CallbackInfoReturnable<Supplier<AEKey>> cir) {
		cir.setReturnValue(new GenerationMemoizedSupplier<>(cir.getReturnValue(),
				ProductiveBeesGenesis.RECIPE_VERSION::get));
	}
}
