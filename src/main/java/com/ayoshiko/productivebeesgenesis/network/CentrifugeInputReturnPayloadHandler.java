package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** 服务端离心机输入返还路由，不包含任何 AE2 API 类型。 */
final class CentrifugeInputReturnPayloadHandler {

	private CentrifugeInputReturnPayloadHandler() {
	}

	static void handle(ReturnCentrifugeInputPayload payload, IPayloadContext context) {
		if (!(context.player() instanceof ServerPlayer player) || player.level() == null) return;
		if (!(player.containerMenu instanceof MekanismTileContainer<?> tileContainer)
				|| !tileContainer.getTileEntity().getBlockPos().equals(payload.pos())) return;
		if (player.distanceToSqr(payload.pos().getCenter())
				> NetworkSecurityConstants.GUI_INTERACTION_DISTANCE_SQ) {
			LogThrottle.warn("centrifuge_input_return_distance",
					"玩家 {} 尝试远距离返还离心机输入物品：距离 {} 格",
					player.getName().getString(), Math.sqrt(player.distanceToSqr(payload.pos().getCenter())));
			return;
		}
		if (!PayloadRateLimiter.tryAccept(player,
				"centrifuge_input_return:" + payload.pos().asLong(),
				NetworkSecurityConstants.PAYLOAD_RATE_LIMIT_INTERVAL_MS)) return;

		BlockEntity blockEntity = player.level().getBlockEntity(payload.pos());
		if (!(blockEntity instanceof IMekCentrifugeTile centrifuge) || blockEntity.isRemoved()) return;
		List<IInventorySlot> inputSlots = collectInputSlots(centrifuge);
		if (countItems(inputSlots) <= 0L) {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.empty"));
			return;
		}

		CentrifugeAeReturnResult aeResult = tryReturnToAe(blockEntity, inputSlots);
		if (aeResult.online()) {
			sendAeResult(player, aeResult, countItems(inputSlots));
			return;
		}

		CentrifugeConfiguredOutputService.Result outputResult =
				CentrifugeConfiguredOutputService.transfer(
						player.level(), blockEntity, inputSlots);
		sendConfiguredOutputResult(player, outputResult, countItems(inputSlots));
	}

	private static CentrifugeAeReturnResult tryReturnToAe(BlockEntity blockEntity,
			List<IInventorySlot> inputSlots) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return CentrifugeAeReturnResult.offline();
		try {
			// 仅在确认 AE2 存在后触达含 appeng 类型的实现类，避免 Issue #8 类加载崩溃。
			return Ae2Access.transfer(blockEntity, inputSlots);
		} catch (LinkageError | RuntimeException e) {
			LogThrottle.warn("centrifuge_input_return_ae2_linkage",
					"离心机输入返还无法使用 AE2，降级到 Mekanism 输出面: {}", e.toString());
			return CentrifugeAeReturnResult.offline();
		}
	}

	private static List<IInventorySlot> collectInputSlots(IMekCentrifugeTile centrifuge) {
		int slotCount = Math.max(0, centrifuge.productivebeesgenesis$getInputSlotCount());
		List<IInventorySlot> inputSlots = new ArrayList<>(slotCount);
		for (int index = 0; index < slotCount; index++) {
			IInventorySlot slot = centrifuge.productivebeesgenesis$getInputSlot(index);
			if (slot != null) inputSlots.add(slot);
		}
		return inputSlots;
	}

	private static long countItems(List<IInventorySlot> inputSlots) {
		long total = 0L;
		for (IInventorySlot slot : inputSlots) {
			if (slot != null && !slot.isEmpty()) {
				long count = Math.max(0L, slot.getCount());
				total = Long.MAX_VALUE - total < count ? Long.MAX_VALUE : total + count;
			}
		}
		return total;
	}

	private static void sendAeResult(ServerPlayer player, CentrifugeAeReturnResult result,
			long remaining) {
		if (result.returnedToAe() > 0L || result.pending() > 0L) {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.ae_result",
					result.returnedToAe(), result.pending(), remaining));
		} else {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.ae_blocked"));
		}
	}

	private static void sendConfiguredOutputResult(ServerPlayer player,
			CentrifugeConfiguredOutputService.Result result, long remaining) {
		if (result.transferred() > 0L) {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.local_result",
					result.transferred(), remaining));
		} else if (result.configuredOutputSides() <= 0) {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.no_output_side"));
		} else if (result.targetContainers() <= 0) {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.no_container"));
		} else {
			player.sendSystemMessage(Component.translatable(
					"productivebeesgenesis.gui.centrifuge_input_return.local_blocked"));
		}
	}

	/** 真正的 AE2 服务符号只存在于此独立 class，AE2 未安装时不会加载。 */
	private static final class Ae2Access {
		private Ae2Access() {
		}

		private static CentrifugeAeReturnResult transfer(BlockEntity blockEntity,
				List<IInventorySlot> inputSlots) {
			return Ae2CentrifugeInputReturnService.transfer(blockEntity, inputSlots);
		}
	}
}
