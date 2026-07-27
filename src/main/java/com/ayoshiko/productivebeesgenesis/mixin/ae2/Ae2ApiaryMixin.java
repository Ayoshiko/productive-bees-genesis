package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;

/**
 * 蜂箱 AE2 接口注入 Mixin — 仅在 AE2 加载时应用
 * <br/>
 * <b>原理</b>：通过 Mixin 接口注入，使 {@link TileEntityMekApiary} 动态实现
 * {@link IAe2OutputHost} 接口（继承 {@code IInWorldGridNodeHost}），
 * 使 AE2 线缆能通过 {@code AECapabilities.IN_WORLD_GRID_NODE_HOST} capability 发现蜂箱。
 * <p>
 * <b>方法实现</b>：{@link IAe2OutputHost} 的 {@code getGridNode(Direction)} 和
 * {@code getCableConnectionType(Direction)} 均为 default 方法，委托给
 * {@code IAe2OutputHostBase.productivebeesgenesis$getAe2GridNode()}，
 * 该方法已在 {@link TileEntityMekApiary} 中实现。故 Mixin 类无需提供额外方法。
 * <p>
 * <b>继承覆盖</b>：所有蜂箱工厂类（{@code TileEntityMekApiaryFactory}、
 * {@code TileEntityExtraMekApiaryFactory}、{@code TileEntityEMExtraMekApiaryFactory}）
 * 均继承自 {@link TileEntityMekApiary}，Mixin 注入到父类后子类自动获得接口实现。
 * <p>
 * <b>类加载安全</b>：本 Mixin 类引用 {@link IAe2OutputHost}（含 AE2 类引用），
 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 控制，
 * 仅在 AE2 已安装时应用，避免 AE2 未安装时类加载失败。
 *
 * @since 1.7.0
 * @author Ayoshiko
 */
@Mixin(value = TileEntityMekApiary.class, remap = false)
public abstract class Ae2ApiaryMixin implements IAe2OutputHost {
	// Mixin 接口注入：IAe2OutputHost 的 getGridNode/getCableConnectionType 为 default 方法，
	// 委托给 TileEntityMekApiary 已实现的 IAe2OutputHostBase 方法，无需额外实现。
}
