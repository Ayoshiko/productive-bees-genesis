package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory;
import com.jdte.common.blockentities.CoalescedAcceleratedMachine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * JDTE {@code CoalescedAcceleratedMachine} 合并接口注入 — 通用机械离心机工厂基类
 * （覆盖原版工厂变体 {@code TileEntityMekCentrifugeFactory} 及其子类，通过继承；
 * ME/EME 工厂继承各自基类不在此列，JDTE 对其按普通 ticker 路径加速，行为与未实现接口一致）。
 * <br/>
 * 仅当 JDTE 加载时由 {@code MixinConfigPlugin} 条件应用。
 * <p>
 * <b>类加载安全（IllegalClassLoadError 防护）</b>：目标类为本模组自有抽象类；
 * 方法体只引用目标类自身方法（{@code productivebeesgenesis$xxx}）、本模组工具类与原始类型，
 * 不引用任何 JDTE 类型；{@code CoalescedAcceleratedMachine} 由应用类加载器加载，
 * MixinClassLoader 委托父加载器解析，与目标类解析到同一 Class 实例；
 * jdte 未安装时插件拒绝应用，接口类型不会被解析。
 */
@Mixin(AbstractMekCentrifugeFactory.class)
public abstract class JdteCentrifugeFactoryCoalescedMixin implements CoalescedAcceleratedMachine {

	@Override
	public void accumulateAcceleratedTicks(int ticks) {
		((AbstractMekCentrifugeFactory) (Object) this).productivebeesgenesis$accumulateAcceleratedTicks(ticks);
	}

	@Override
	public void flushAcceleratedTicks() {
		((AbstractMekCentrifugeFactory) (Object) this).productivebeesgenesis$flushAcceleratedTicks();
	}
}
