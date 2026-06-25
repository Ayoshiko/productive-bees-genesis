package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.client.InfinityCreationClientEventHandler;

import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 客户端 Mixin：为无尽·创世蜜蜂提供 cosmic 星空颜色循环
 * <p>
 * 原理：拦截 ConfigurableBee 的 getColor/getTertiaryColor/getParticleColor 方法，
 * 仅对 productivebees:infinitycreation 类型生效，调用
 * {@link InfinityCreationClientEventHandler#getCosmicColor(long)} 获取颜色。
 * <p>
 * 与 {@link ConfigurableBeeColorMixin} 完全独立、互不冲突：
 * <ul>
 *   <li>ConfigurableBeeColorMixin 仅处理 myriadcreations 类型</li>
 *   <li>本 Mixin 仅处理 infinitycreation 类型</li>
 *   <li>两者通过蜜蜂类型判断互斥，先检查类型再决定是否接管</li>
 * </ul>
 * <p>
 * Mixin 方法名使用 productivebeesgenesis$infinity$ 前缀，避免与现有 Mixin 冲突。
 */
@Mixin(ConfigurableBee.class)
public abstract class InfinityCreationColorMixin {

    /**
     * 拦截 getColor 方法，为无尽·创世蜜蜂返回 cosmic 颜色
     */
    @Inject(method = "getColor", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$infinity$onGetColor(int tintIndex, float partialTicks, CallbackInfoReturnable<Integer> cir) {
        ConfigurableBee self = productivebeesgenesis$infinity$getSelf();
        if (!InfinityCreationClientEventHandler.INFINITY_CREATION_TYPE.equals(self.getBeeType())) {
            return; // 非无尽·创世蜜蜂，使用原始逻辑
        }

        // 使用自定义的 cosmic 星空颜色
        float[] cosmic = InfinityCreationClientEventHandler.getCosmicColor(System.currentTimeMillis());
        int color = productivebeesgenesis$infinity$floatToArgb(cosmic);
        cir.setReturnValue(color);
    }

    /**
     * 拦截 getTertiaryColor 方法，为无尽·创世蜜蜂返回 cosmic 颜色（水晶渲染器用）
     */
    @Inject(method = "getTertiaryColor", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$infinity$onGetTertiaryColor(float partialTicks, CallbackInfoReturnable<Integer> cir) {
        ConfigurableBee self = productivebeesgenesis$infinity$getSelf();
        if (!InfinityCreationClientEventHandler.INFINITY_CREATION_TYPE.equals(self.getBeeType())) {
            return;
        }

        float[] cosmic = InfinityCreationClientEventHandler.getCosmicColor(System.currentTimeMillis());
        int color = productivebeesgenesis$infinity$floatToArgb(cosmic);
        cir.setReturnValue(color);
    }

    /**
     * 拦截 getParticleColor 方法，为无尽·创世蜜蜂返回 cosmic 粒子/花粉颜色
     * <p>
     * PB 的 BeeBodyLayer.renderNectarLayer() 在蜜蜂采蜜后（hasNectar=true）
     * 使用此方法颜色渲染身体上的花粉层。拦截后花粉层颜色会随 cosmic 循环变化。
     */
    @Inject(method = "getParticleColor", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$infinity$onGetParticleColor(CallbackInfoReturnable<Integer> cir) {
        ConfigurableBee self = productivebeesgenesis$infinity$getSelf();
        if (!InfinityCreationClientEventHandler.INFINITY_CREATION_TYPE.equals(self.getBeeType())) {
            return;
        }

        float[] cosmic = InfinityCreationClientEventHandler.getCosmicColor(System.currentTimeMillis());
        int color = productivebeesgenesis$infinity$floatToArgb(cosmic);
        cir.setReturnValue(color);
    }

    /** 获取当前 Mixin 目标实例 */
    @Unique
    private ConfigurableBee productivebeesgenesis$infinity$getSelf() {
        return (ConfigurableBee) (Object) this;
    }

    /** 将 float[] {r, g, b} (0-1) 转换为 ARGB int */
    @Unique
    private static int productivebeesgenesis$infinity$floatToArgb(float[] rgb) {
        int color = 0xFF000000; // 完全不透明
        color |= ((int) (rgb[0] * 255) << 16);
        color |= ((int) (rgb[1] * 255) << 8);
        color |= (int) (rgb[2] * 255);
        return color;
    }
}
