package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.logistics.ExternalLogisticsSettings;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 产物直通 per-tile 开关的服务端处理器（蜂箱与离心机共用）。
 * <br/>
 * 不引用任何 AE2 类型：直通写的是相邻容器的物品能力，AE2 缺失时同样可用
 * （与 {@link SmeltingCompatPayloadHandler} 同一形态）。
 * <p>
 * 校验顺序（与其他 GUI 交互包一致）：服务端玩家 → 打开的容器与坐标匹配 →
 * 交互距离 → 全局总开关。全局关闭时拒绝切换，避免玩家在灰显按钮上改出无效状态。
 */
final class DirectContainerOutputPayloadHandler {

	private DirectContainerOutputPayloadHandler() {
	}

	static void handle(ToggleDirectContainerOutputPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer serverPlayer) || serverPlayer.level() == null) return;
		// 必须是当前打开的机器 GUI，防止伪造坐标远程改别人机器
		if (!(serverPlayer.containerMenu instanceof MekanismTileContainer<?> tileContainer)
				|| !tileContainer.getTileEntity().getBlockPos().equals(payload.pos())) return;
		if (serverPlayer.distanceToSqr(payload.pos().getCenter())
				> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("direct_container_output_distance",
					"玩家 {} 尝试在超出交互距离处切换产物直通开关",
					serverPlayer.getName().getString());
			return;
		}
		// 全局总开关关闭时不允许改 per-tile 值（客户端按钮同样灰显）
		if (!ExternalLogisticsSettings.directContainerOutput(serverPlayer.level().getGameTime())) return;

		BlockEntity blockEntity = serverPlayer.level().getBlockEntity(payload.pos());
		if (blockEntity == null || blockEntity.isRemoved()) return;
		if (blockEntity instanceof TileEntityMekApiary apiary) {
			apiary.toggleDirectContainerOutput();
		} else if (blockEntity instanceof IMekCentrifugeTile
				&& blockEntity instanceof IAe2OutputHostBase host
				&& host.productivebeesgenesis$getAe2StateHolder() != null) {
			host.productivebeesgenesis$getAe2StateHolder().toggleDirectContainerOutputEnabled();
		} else {
			return;
		}
		if (blockEntity instanceof TileEntityMekanism mekanismTile) mekanismTile.markForSave();
	}
}
