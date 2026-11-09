package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntityAbstract;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 模拟升级在开始新周期前停止已删除蜂种；已完成的正常交付继续使用原配方。 */
@Mixin(AdvancedBeehiveBlockEntityAbstract.class)
public abstract class DesignerRetiredSimulationMixin {

	@Inject(method = "simulateBee", at = @At("HEAD"), cancellable = true)
	private static void productivebeesgenesis$retiredSimulation(ServerLevel level, BlockPos pos, BlockState state,
			AdvancedBeehiveBlockEntityAbstract hive, BeehiveBlockEntity.Occupant occupant, CallbackInfoReturnable<Entity> result) {
		String type = occupant.entityData().getUnsafe().getString("type");
		if (BeeDesignerLifecycle.deleted(ResourceLocation.tryParse(type))) {
			result.setReturnValue(null);
		}
	}
}
