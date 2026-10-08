package com.ayoshiko.productivebeesgenesis.mixin.ae2;

import appeng.core.network.clientbound.CraftingJobStatusPacket;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingCompletions;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** 观察 AE2 发给在线作业所有者的原生状态，不用 link 或任务行消失猜测完成。 */
@Mixin(targets = "appeng.crafting.execution.CraftingCpuLogic", remap = false)
public abstract class Ae2CraftingCompletionMixin {
	@Shadow @Final private CraftingCPUCluster cluster;
	@Unique private boolean productivebeesgenesis$pinRecordingFailed;
	@WrapOperation(method = "notifyJobOwner", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", remap = true), require = 1)
	private void productivebeesgenesis$observeCompletion(ServerGamePacketListenerImpl connection, CustomPacketPayload payload, Operation<Void> original) {
		original.call(connection, payload);
		if (productivebeesgenesis$pinRecordingFailed || !(payload instanceof CraftingJobStatusPacket message)) return;
		try { AeCraftingCompletions.completed(connection.player, cluster.getGrid(), message); }
		catch (RuntimeException | LinkageError failure) {
			// 展示记录异常不能打断 AE2 随后的作业清理或成品回存，也不重试原生发送。
			productivebeesgenesis$pinRecordingFailed = true;
			com.mojang.logging.LogUtils.getLogger().warn("Completion pin recording disabled for CPU job {} owned by {}", message.jobId(), connection.player.getUUID(), failure);
		}
	}
}
