package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import org.spongepowered.asm.mixin.Mixin;

import net.minecraft.core.Direction;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;

import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;

/**
 * 原版工厂离心机 AE2 接口注入 Mixin — 仅在 AE2 加载时应用
 * <br/>
 * <b>原理</b>：通过 Mixin 接口注入，使 {@link AbstractMekCentrifugeFactory} 动态实现
 * {@link IAe2OutputHost} 接口。{@link AbstractMekCentrifugeFactory} 是抽象基类，
 * 其具体子类 {@code TileEntityMekCentrifugeFactory}（原版4等级工厂）通过继承自动获得接口。
 * <p>
 * <b>方法实现</b>：{@code getGridNode(Direction)} 为抽象方法（避免与 AE2
 * {@code IGridConnectedBlockEntity} 的 default 方法冲突），本 Mixin 显式实现并委托
 * {@link IAe2OutputHost#resolveGridNode}。
 * <p>
 * <b>类加载安全</b>：目标类继承自 Mekanism 的 {@code TileEntityItemToItemFactory}（始终可用），
 * 由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 控制，
 * 仅在 AE2 已安装时应用。
 *
 * @since 1.7.0
 * @author Ayoshiko
 */
@Mixin(value = AbstractMekCentrifugeFactory.class, remap = false)
public abstract class Ae2CentrifugeFactoryMixin implements IAe2OutputHost {
	// Mixin 接口注入：显式实现 getGridNode，避免与 AE2 IGridConnectedBlockEntity default 冲突
	@Override
	public @Nullable IGridNode getGridNode(Direction dir) {
		return IAe2OutputHost.resolveGridNode(this, dir);
	}

	@Override
	public AECableType getCableConnectionType(Direction dir) {
		return IAe2OutputHost.resolveCableConnectionType(dir);
	}
}
