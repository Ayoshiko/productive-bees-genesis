package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.jdte.common.blockentities.CoalescedAcceleratedMachine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * JDTE {@code CoalescedAcceleratedMachine} 合并接口注入 — 通用机械离心机（单机版）。
 * <br/>
 * 仅当 JDTE 加载时由 {@code MixinConfigPlugin} 条件应用。
 * <p>
 * <b>类加载安全（IllegalClassLoadError 防护）</b>：目标类为本模组自有类；
 * 方法体只引用目标类自身方法与原始类型，不引用任何 JDTE 类型；
 * {@code CoalescedAcceleratedMachine} 由应用类加载器加载，MixinClassLoader 委托父加载器解析，
 * 与目标类解析到同一 Class 实例；jdte 未安装时插件拒绝应用，接口类型不会被解析。
 */
@Mixin(TileEntityMekCentrifuge.class)
public abstract class JdteCentrifugeCoalescedMixin implements CoalescedAcceleratedMachine {

	@Override
	public void accumulateAcceleratedTicks(int ticks) {
		((TileEntityMekCentrifuge) (Object) this).productivebeesgenesis$accumulateAcceleratedTicks(ticks);
	}

	@Override
	public void flushAcceleratedTicks() {
		((TileEntityMekCentrifuge) (Object) this).productivebeesgenesis$flushAcceleratedTicks();
	}
}
