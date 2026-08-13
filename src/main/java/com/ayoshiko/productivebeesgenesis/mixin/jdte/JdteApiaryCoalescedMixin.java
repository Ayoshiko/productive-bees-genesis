package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.jdte.common.blockentities.CoalescedAcceleratedMachine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * JDTE {@code CoalescedAcceleratedMachine} 合并接口注入 — 通用机械蜂箱（含全部工厂变体，通过继承覆盖）。
 * <br/>
 * 仅当 JDTE 加载时由 {@code MixinConfigPlugin} 条件应用（jdte 未安装时本 Mixin 不应用，无任何影响）。
 * JDTE 检测到接口后不再循环调用 ticker，而是在批量 pass 内调用
 * {@code accumulateAcceleratedTicks} 累计、pass 结束时调用一次 {@code flushAcceleratedTicks}，
 * 把 N 次 ticker 调用（含 N 次 super 能量填充/ejector）降为 1 次完整批量。
 * <p>
 * <b>类加载安全（IllegalClassLoadError 防护）</b>：
 * <ul>
 *   <li>目标类为本模组自有类（应用类加载器）；本 Mixin 方法体只引用目标类自身方法与原始类型，
 *       不引用任何 JDTE 类型——合并到目标类后不存在跨类加载器引用</li>
 *   <li>{@code CoalescedAcceleratedMachine} 由应用类加载器（NeoForge mod classloader）加载，
 *       MixinClassLoader 委托父加载器解析，与目标类解析到同一 Class 实例，无加载约束冲突</li>
 *   <li>jdte 未安装时 {@code MixinConfigPlugin.shouldApplyMixin} 返回 false，
 *       Mixin 框架不会定义本类字节码，接口类型不会被解析</li>
 * </ul>
 */
@Mixin(TileEntityMekApiary.class)
public abstract class JdteApiaryCoalescedMixin implements CoalescedAcceleratedMachine {

	@Override
	public void accumulateAcceleratedTicks(int ticks) {
		((TileEntityMekApiary) (Object) this).productivebeesgenesis$accumulateAcceleratedTicks(ticks);
	}

	@Override
	public void flushAcceleratedTicks() {
		((TileEntityMekApiary) (Object) this).productivebeesgenesis$flushAcceleratedTicks();
	}
}
