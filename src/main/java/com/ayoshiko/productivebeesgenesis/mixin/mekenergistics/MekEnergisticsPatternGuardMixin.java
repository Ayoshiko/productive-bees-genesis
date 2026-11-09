package com.ayoshiko.productivebeesgenesis.mixin.mekenergistics;

import appeng.api.inventories.InternalInventory;
import com.ayoshiko.productivebeesgenesis.compat.mekenergistics.MekEnergisticsBlockGuard;
import java.util.List;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mek Energistics 3.0.7 将 PatternContainer 注入 Mekanism 基类，即使没有安装 ME 升级，
 * 子类通过自有节点接网后也会暴露默认 72 槽。只关闭本模组机器继承来的样板入口；
 * 不改自有 AE2 节点、外部发配目标或第三方内部库存/NBT。
 */
@Pseudo
@Mixin(targets = "com.beipuo.mekenergistics.blockentity.api.MeAeMachine", remap = false)
public interface MekEnergisticsPatternGuardMixin {

	@Inject(method = "isVisibleInTerminal", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$hidePatternContainer(CallbackInfoReturnable<Boolean> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(false);
	}

	@Inject(method = "getTerminalPatternInventory", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$emptyTerminalInventory(CallbackInfoReturnable<InternalInventory> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(InternalInventory.empty());
	}

	@Inject(method = {"getPatternSlots", "getAvailablePatterns"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$emptyPatternView(CallbackInfoReturnable<List<?>> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(List.of());
	}

	@Inject(method = "withPatternSlots", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$keepMachineInventory(IInventorySlotHolder original,
			CallbackInfoReturnable<IInventorySlotHolder> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(original);
	}

	@Inject(method = "pushPattern", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$rejectPatternExecution(CallbackInfoReturnable<Boolean> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(false);
	}

	@Inject(method = "maxAcceptedPatternCopies", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void productivebeesgenesis$rejectPatternCopies(CallbackInfoReturnable<Long> cir) {
		if (MekEnergisticsBlockGuard.isProtectedMachineHost(this)) cir.setReturnValue(0L);
	}
}
