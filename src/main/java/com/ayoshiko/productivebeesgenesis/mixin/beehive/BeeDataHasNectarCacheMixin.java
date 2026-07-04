package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * BeehiveBlockEntity$BeeData 的 hasNectar() 结果缓存 Mixin。
 * <p>
 * 原实现每次调用 {@code hasNectar()} 都会从 Occupant 的 entityData NBT 中读取 {@code HasNectar} 布尔值。
 * {@code AdvancedBeehiveBlockEntityAbstract.tickBees()} 对同一只蜜蜂可能在一 tick 内多次调用
 * {@code hasNectar()}，成为热点。
 * <p>
 * 本 Mixin 直接作用于 {@code BeeData}（通过 {@code targets} 避免在代码中引用包私有类），
 * 在每个 BeeData 实例中缓存最近一次 {@code hasNectar()} 的返回值，并在 {@link #tick()} 开始时失效，
 * 保证结果新鲜的同时把每个 BeeData 每 tick 的 hasNectar 调用从多次降为一次。
 * <p>
 * 实现方式：
 * <ul>
 *   <li>在 {@code tick()Z} 头部将缓存标记置为失效；</li>
 *   <li>在 {@code hasNectar()Z} 头部命中缓存时直接返回；</li>
 *   <li>在 {@code hasNectar()Z} 返回时捕获原方法结果并写入缓存。</li>
 * </ul>
 * 这样无需访问 BeeData 内部的 {@code occupant} 字段或 {@code Occupant} 记录，避免混淆字段名问题。
 * <p>
 * 该缓存为默认开启且不可关闭的内部性能优化，不再提供配置项。
 */
@Mixin(targets = "net.minecraft.world.level.block.entity.BeehiveBlockEntity$BeeData")
public abstract class BeeDataHasNectarCacheMixin {

	/** 当前 tick 是否已经缓存过 hasNectar 结果 */
	@Unique
	private boolean productivebeesgenesis$hasNectarCached;

	/** 缓存的 hasNectar 结果 */
	@Unique
	private boolean productivebeesgenesis$hasNectarValue;

	/**
	 * 每次 tick 开始时失效缓存，确保 NBT 发生变化后能重新读取。
	 * <br/>
	 * BeeData.tick() 每游戏刻对每个蜂箱内的蜜蜂调用一次，因此最多每 tick 重新计算一次，
	 * 而 tickBees() 内部可能对同一只蜜蜂多次调用 hasNectar()，这些调用都会命中缓存。
	 * <br/>
	 * 注意：实际签名为 {@code boolean tick()}（返回蜜蜂是否该离巢），不是 {@code void tick()}。
	 */
	@Inject(method = "tick()Z", at = @At("HEAD"), remap = true)
	private void productivebeesgenesis$invalidateHasNectarCache(CallbackInfoReturnable<Boolean> cir) {
		productivebeesgenesis$hasNectarCached = false;
	}

	/**
	 * 命中缓存时直接返回结果，避免重复调用原版 hasNectar()。
	 */
	@Inject(method = "hasNectar()Z", at = @At("HEAD"), cancellable = true, remap = true)
	private void productivebeesgenesis$cachedHasNectarHead(CallbackInfoReturnable<Boolean> cir) {
		if (productivebeesgenesis$hasNectarCached) {
			cir.setReturnValue(productivebeesgenesis$hasNectarValue);
		}
	}

	/**
	 * 首次调用原版 hasNectar() 后捕获返回值并写入缓存。
	 */
	@Inject(method = "hasNectar()Z", at = @At("RETURN"), remap = true)
	private void productivebeesgenesis$cachedHasNectarTail(CallbackInfoReturnable<Boolean> cir) {
		if (!productivebeesgenesis$hasNectarCached) {
			productivebeesgenesis$hasNectarValue = cir.getReturnValue();
			productivebeesgenesis$hasNectarCached = true;
		}
	}
}
