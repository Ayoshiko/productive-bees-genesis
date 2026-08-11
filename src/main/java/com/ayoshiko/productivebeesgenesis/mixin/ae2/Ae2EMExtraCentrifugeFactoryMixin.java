package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.api.networking.IGridNode;
import appeng.api.util.AECableType;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

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
	 * <b>方法实现</b>：{@code getGridNode(Direction)} 为抽象方法（避免与 AE2
	 * {@code IGridConnectedBlockEntity} 的 default 方法冲突），本 Mixin 显式实现并委托
	 * {@link IAe2OutputHost#resolveGridNode}。
	 * <p>
	 * <b>独立 Mixin 原因</b>：EME 工厂离心机不继承 {@code AbstractMekCentrifugeFactory}
	 * （因 Java 单继承限制，继承自 EME 的工厂基类），故需单独 Mixin 注入接口。
	 *
	 * @since 1.5.3
	 * @author Ayoshiko
	 */
@Mixin(targets = "com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory", remap = false)
public abstract class Ae2EMExtraCentrifugeFactoryMixin implements IAe2OutputHost {
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
