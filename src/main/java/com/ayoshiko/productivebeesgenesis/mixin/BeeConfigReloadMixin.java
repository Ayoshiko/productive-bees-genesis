package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BeeConfigApplier;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
public abstract class BeeConfigReloadMixin {

	/** 配置重载失败日志限流器（ms 模式，5 秒冷却）— 重载监听器无 Level 访问 */
	@Unique
	private static final LogThrottle productivebeesgenesis$CONFIG_RELOAD_THROTTLE = new LogThrottle(100L, 5000L);

	@Inject(method = "apply", at = @At("TAIL"))
	private void productivebeesgenesis$applyConfigOverrides(CallbackInfo ci) {
		try {
			BeeConfigApplier.applyOverrides();
		} catch (Exception e) {
			// 捕获所有异常（包括配置未加载的 IllegalStateException 及其他意外异常），
			// 跳过本次覆盖，后续由 ModConfigEvent 触发。
			// 首次启动配置未加载属正常行为，但以 ERROR + 堆栈记录便于诊断持续性故障；
			// LogThrottle 限流避免高频 reload 导致日志刷屏，并区分首次与重复（suppressed 计数）。
			productivebeesgenesis$CONFIG_RELOAD_THROTTLE.tryLogMs(System.currentTimeMillis(), suppressed ->
					ProductiveBeesGenesis.LOGGER.error("蜜蜂配置重载失败，跳过属性覆盖（已抑制 {} 次类似错误）",
							suppressed, e));
		}
	}
}