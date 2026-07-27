package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;

import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;

/**
 * EME 工厂离心机 AE2 接口注入 Mixin — 仅在 AE2 且 EME 加载时应用
 * <br/>
 * <b>原理</b>：通过 Mixin 接口注入，使 {@code TileEntityEMExtraMekCentrifugeFactory} 动态实现
 * {@link IAe2OutputHost} 接口，使 AE2 线缆能通过 capability 发现 EME 工厂离心机。
 * <p>
 * <b>targets 字符串</b>：使用 {@code targets} 字符串而非 {@code value} 类字面量，
 * 避免在 Mixin 类加载阶段触发目标类加载。目标类位于 {@code compat.emextras} 包，
 * 继承自 EME 的 {@code TileEntityEMExtraItemStackToItemStackFactory}，仅在 EME 已加载时才可加载。
 * MixinConfigPlugin 确保仅在 AE2 + EME 同时加载时才应用此 Mixin。
 * <p>
 * <b>方法实现</b>：{@link IAe2OutputHost} 的两个方法均为 default 方法，
 * 委托给目标类已实现的 {@code IAe2OutputHostBase.productivebeesgenesis$getAe2GridNode()}。
 * <p>
 * <b>独立 Mixin 原因</b>：EME 工厂离心机不继承 {@code AbstractMekCentrifugeFactory}
 * （因 Java 单继承限制，继承自 EME 的工厂基类），故需单独 Mixin 注入接口。
 *
 * @since 1.7.0
 * @author Ayoshiko
 */
@Mixin(targets = "com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory", remap = false)
public abstract class Ae2EMExtraCentrifugeFactoryMixin implements IAe2OutputHost {
	// Mixin 接口注入：default 方法委托给已实现的 IAe2OutputHostBase 方法
}
