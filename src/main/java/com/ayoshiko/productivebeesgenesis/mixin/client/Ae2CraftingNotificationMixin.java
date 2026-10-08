package com.ayoshiko.productivebeesgenesis.mixin.client;

import appeng.api.stacks.AEKey;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.LinkedHashSet;
import java.util.UUID;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 沿 AE2 的真实 FINISHED 事件与同一 toast 入口扩展资格，不轮询任务或重发产物。 */
@Mixin(targets = "appeng.client.gui.me.common.PendingCraftingJobs", remap = false)
public abstract class Ae2CraftingNotificationMixin {
	@Unique private static final LinkedHashSet<UUID> productivebeesgenesis$notified = new LinkedHashSet<>();
	@Unique private static boolean productivebeesgenesis$enabled() { return ModConfig.CLIENT.terminalPreferences.notifyCraftingFinished.get(); }

	@ModifyExpressionValue(method = "jobStatus", at = @At(value = "INVOKE", target = "Lappeng/core/AEConfig;isNotifyForFinishedCraftingJobs()Z"), require = 1)
	private static boolean productivebeesgenesis$enableCompletion(boolean original) { return original || productivebeesgenesis$enabled(); }

	@ModifyExpressionValue(method = "jobStatus", at = @At(value = "INVOKE", target = "Lappeng/client/gui/me/common/PendingCraftingJobs;hasNotificationEnablingItem(Lnet/minecraft/client/player/LocalPlayer;)Z"), require = 1)
	private static boolean productivebeesgenesis$allowWithoutAeDevice(boolean original) { return original || productivebeesgenesis$enabled(); }

	@WrapOperation(method = "jobStatus", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/toasts/ToastComponent;addToast(Lnet/minecraft/client/gui/components/toasts/Toast;)V", remap = true), require = 1)
	private static void productivebeesgenesis$notifyOnce(ToastComponent manager, Toast toast, Operation<Void> original,
			UUID id, AEKey what, long requested, long remaining, CraftingJobStatusPacket.Status status) {
		if (productivebeesgenesis$enabled()) {
			if (id == null || requested <= 0 || remaining != 0 || status != CraftingJobStatusPacket.Status.FINISHED || productivebeesgenesis$notified.contains(id)) return;
			original.call(manager, toast);
			productivebeesgenesis$notified.add(id);
			// 只保留最近完成事件的 ID；不持有资源键、网格、菜单或世界。
			if (productivebeesgenesis$notified.size() > 256) productivebeesgenesis$notified.removeFirst();
		} else original.call(manager, toast);
	}

	@Inject(method = "clearPendingJobs", at = @At("HEAD"), require = 1)
	private static void productivebeesgenesis$clearCompletionHistory(CallbackInfo ci) { productivebeesgenesis$notified.clear(); }
}
