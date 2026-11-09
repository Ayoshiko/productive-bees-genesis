package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BeeDataAccessor;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceMixinContract;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** 只暂停万象居住者；紧急释放和取回仍保留，不能清空原蜜蜂或重置其周期。 */
@Mixin(AdvancedBeehiveBlockEntityAbstract.class)
public abstract class MyriadDisabledHiveMixin {
	@WrapOperation(method = PbResourceMixinContract.TICK, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/entity/BeehiveBlockEntity$BeeData;tick()Z"), require = 1)
	private static boolean productivebeesgenesis$disabledMyriadClock(
			@Coerce Object data, Operation<Boolean> original) {
		if (MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
			return original.call(data);
		}
		String type = ((BeeDataAccessor) data).productivebeesgenesis$toOccupant()
				.entityData().getUnsafe().getString("type");
		return !MyriadCreationsEventHandler.isMyriadCreationsDisabled(ResourceLocation.tryParse(type))
				&& original.call(data);
	}

	@Inject(method = PbResourceMixinContract.SIMULATE, at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$disabledMyriadSimulation(
			ServerLevel level, BlockPos pos, BlockState state,
			AdvancedBeehiveBlockEntityAbstract hive, BeehiveBlockEntity.Occupant occupant,
			CallbackInfoReturnable<Entity> result) {
		if (MyriadCreationsEventHandler.isMyriadCreationsDisabled(
				ResourceLocation.tryParse(occupant.entityData().getUnsafe().getString("type")))) {
			result.setReturnValue(null);
		}
	}

	@Inject(method = PbResourceMixinContract.RELEASE, at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$disabledMyriadDelivery(Level level, BlockPos pos, BlockState state,
			BeehiveBlockEntity.Occupant occupant, AdvancedBeehiveBlockEntityAbstract hive, List<Entity> released,
			BeehiveBlockEntity.BeeReleaseStatus status, CallbackInfoReturnable<Boolean> result) {
		if (status == BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED
				&& MyriadCreationsEventHandler.isMyriadCreationsDisabled(
						ResourceLocation.tryParse(occupant.entityData().getUnsafe().getString("type")))) {
			result.setReturnValue(false);
		}
	}
}
