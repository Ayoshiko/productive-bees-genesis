/**
 * AE2 接口动态注入 Mixin
 * <br/>
 * Task 4：在 AE2 已安装时，通过 Mixin 接口注入使 TileEntity 类实现
 * {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost} 接口
 * （继承 {@code IInWorldGridNodeHost}），使 AE2 线缆能通过 capability 发现并连接这些方块。
 * <br/>
 * <b>原理</b>：{@code IAe2OutputHost} 的 {@code getGridNode} 和 {@code getCableConnectionType}
 * 均为 default 方法，委托给 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase}
 * 中已实现的方法。Mixin 类仅需声明 {@code implements IAe2OutputHost}，无需提供额外方法实现。
 * <br/>
 * <b>条件控制</b>：所有 Mixin 由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin}
 * 控制，仅在 AE2 已安装时应用；引用 ME/EME 类的 Mixin 额外要求对应模组已加载。
 * <br/>
 * <b>覆盖范围</b>：
 * <ol>
 *   <li>{@link Ae2ApiaryMixin} — {@code TileEntityMekApiary}，覆盖全部蜂箱类（基础+工厂+ME工厂+EME工厂）</li>
 *   <li>{@link Ae2CentrifugeMixin} — {@code TileEntityMekCentrifuge}，基础离心机</li>
 *   <li>{@link Ae2CentrifugeFactoryMixin} — {@code AbstractMekCentrifugeFactory}，原版工厂离心机</li>
 *   <li>{@link Ae2ExtraCentrifugeFactoryMixin} — {@code TileEntityExtraMekCentrifugeFactory}，ME 工厂离心机</li>
 *   <li>{@link Ae2EMExtraCentrifugeFactoryMixin} — {@code TileEntityEMExtraMekCentrifugeFactory}，EME 工厂离心机</li>
 * </ol>
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import javax.annotation.ParametersAreNonnullByDefault;
