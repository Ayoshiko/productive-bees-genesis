package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.client.MyriadCreationsClientEventHandler;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * 客户端Mixin：为万象创世蜜蜂提供慢速彩虹颜色循环
	 * <p>
	 * 原理：productivebees 内置的 colorCycle 使用硬编码的 25tick（1.25秒）周期，
	 * 颜色闪烁过快。此 Mixin 拦截 getColor/getTertiaryColor/getParticleColor，
	 * 使用我们的 RAINBOW_CYCLE_MS（8秒）周期代替。
	 * <p>
	 * 同时配合 myriadcreations.json 中 colorCycle=false，完全禁用PB内建颜色循环，
	 * 所有颜色由本 Mixin 统一控制。
	 * <p>
	 * 注意：getParticleColor 控制采蜜后花粉层的颜色（renderNectarLayer），
	 * 也用于粒子颜色。发光效果由 onRenderLivingPost 事件处理。
	 */
@Mixin(ConfigurableBee.class)
public abstract class ConfigurableBeeColorMixin {

	/**
	 * 拦截 getColor 方法，为万象创世蜜蜂返回自定义彩虹颜色
	 */
	@Inject(method = "getColor", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$onGetColor(int tintIndex, float partialTicks, CallbackInfoReturnable<Integer> cir) {
		// 服务端配置禁用万象创世时，使用原始逻辑
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return;

		ConfigurableBee self = productivebeesgenesis$getSelf();
		if (!PBConstants.MYRIADCREATIONS_TYPE.equals(self.getBeeType())) {
			return; // 非万象创世蜜蜂，使用原始逻辑
		}

		// 使用我们自定义的慢速彩虹颜色
		float[] rainbow = MyriadCreationsClientEventHandler.getRainbowColor(System.currentTimeMillis());
		int color = productivebeesgenesis$floatToArgb(rainbow);
		cir.setReturnValue(color);
	}

	/**
	 * 拦截 getTertiaryColor 方法，为万象创世蜜蜂返回自定义彩虹颜色（水晶渲染器用）
	 */
	@Inject(method = "getTertiaryColor", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$onGetTertiaryColor(float partialTicks, CallbackInfoReturnable<Integer> cir) {
		// 服务端配置禁用万象创世时，使用原始逻辑
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return;

		ConfigurableBee self = productivebeesgenesis$getSelf();
		if (!PBConstants.MYRIADCREATIONS_TYPE.equals(self.getBeeType())) {
			return;
		}

		float[] rainbow = MyriadCreationsClientEventHandler.getRainbowColor(System.currentTimeMillis());
		int color = productivebeesgenesis$floatToArgb(rainbow);
		cir.setReturnValue(color);
	}

	/**
	 * 拦截 getParticleColor 方法，为万象创世蜜蜂返回彩虹粒子/花粉颜色
	 * <p>
	 * PB 的 BeeBodyLayer.renderNectarLayer() 在蜜蜂采蜜后（hasNectar=true）
	 * 使用此方法颜色渲染身体上的花粉层。拦截后花粉层颜色会随彩虹循环变化。
	 */
	@Inject(method = "getParticleColor", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$onGetParticleColor(CallbackInfoReturnable<Integer> cir) {
		// 服务端配置禁用万象创世时，使用原始逻辑
		if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) return;

		ConfigurableBee self = productivebeesgenesis$getSelf();
		if (!PBConstants.MYRIADCREATIONS_TYPE.equals(self.getBeeType())) {
			return;
		}

		float[] rainbow = MyriadCreationsClientEventHandler.getRainbowColor(System.currentTimeMillis());
		int color = productivebeesgenesis$floatToArgb(rainbow);
		cir.setReturnValue(color);
	}

	/** 获取当前 Mixin 目标实例 */
	@Unique
	private ConfigurableBee productivebeesgenesis$getSelf() {
		return (ConfigurableBee) (Object) this;
	}

	/** 将 float[] {r, g, b} (0-1) 转换为 ARGB int */
	@Unique
	private static int productivebeesgenesis$floatToArgb(float[] rgb) {
		// 防御性检查：避免 null 或长度不足导致数组越界崩溃
		if (rgb == null || rgb.length < 3) return 0xFFFFFFFF;
		int color = 0xFF000000; // 完全不透明
		color |= ((int) (rgb[0] * 255) << 16);
		color |= ((int) (rgb[1] * 255) << 8);
		color |= (int) (rgb[2] * 255);
		return color;
	}
}