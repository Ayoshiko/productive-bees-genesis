package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入 BeeReloadListener：加载数据后应用配置覆盖
 * <p>
 * 原理：PB 从数据包加载蜜蜂 JSON 并存入 BEE_DATA 后，
 * 将配置中的值覆盖到万象创世蜜蜂的 CompoundTag 上。
 * <p>
 * 配置可能在 apply() 调用时尚未加载（首次启动），
 * 此时静默跳过，后续由 ModConfigEvent.Loading 事件重新触发覆盖。
 * 执行 /reload 时两个事件都会触发，保证配置生效。
 */
@Mixin(BeeReloadListener.class)
public class BeeConfigReloadMixin {

    @Inject(method = "apply", at = @At("TAIL"))
    private void applyConfigOverrides(CallbackInfo ci) {
        try {
            BeeConfigApplier.applyOverrides();
        } catch (IllegalStateException e) {
            // 配置文件尚未加载，跳过本次覆盖，后续由 ModConfigEvent 触发
            ProductiveBeesGenesis.LOGGER.info("蜜蜂配置尚未加载，跳过属性覆盖（首次启动正常行为）");
        }
    }
}