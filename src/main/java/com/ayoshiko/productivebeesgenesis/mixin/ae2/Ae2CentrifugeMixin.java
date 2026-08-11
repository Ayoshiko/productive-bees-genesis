package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
	 * 基础离心机 AE2 接口注入 Mixin — 仅在 AE2 加载时应用
	 * <br/>
	 * <b>原理</b>：通过 Mixin 接口注入，使 {@link TileEntityMekCentrifuge} 动态实现
	 * {@link IAe2OutputHost} 接口（继承 {@code IInWorldGridNodeHost}），
	 * 使 AE2 线缆能通过 capability 发现基础离心机。
	 * <p>
	 * <b>方法实现</b>：{@code getGridNode(Direction)} 为抽象方法（避免与 AE2
	 * {@code IGridConnectedBlockEntity} 的 default 方法冲突），本 Mixin 显式实现并委托
	 * {@link IAe2OutputHost#resolveGridNode}。
	 * <p>
	 * <b>类加载安全</b>：由 {@link com.ayoshiko.productivebeesgenesis.mixin.MixinConfigPlugin} 控制，
	 * 仅在 AE2 已安装时应用。
	 *
	 * @since 1.5.3
	 * @author Ayoshiko
	 */
@Mixin(value = TileEntityMekCentrifuge.class, remap = false)
public abstract class Ae2CentrifugeMixin implements IAe2OutputHost {
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
