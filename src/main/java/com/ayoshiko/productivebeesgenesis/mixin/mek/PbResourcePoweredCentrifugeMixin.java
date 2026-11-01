package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceCentrifuge;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceCentrifugeBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cy.jdkdigital.productivebees.common.block.entity.PoweredCentrifugeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 包住电力离心机的外层扣费，避免资源事务完成后 PB 再扣一次能量。 */
@Mixin(value=PoweredCentrifugeBlockEntity.class,remap=false)
public abstract class PbResourcePoweredCentrifugeMixin implements PbResourceCentrifugeBridge {
	@Invoker("getEnergyConsumptionModifier")
	public abstract double productivebeesgenesis$resourceEnergyModifier();
	@Override public boolean productivebeesgenesis$resourcePowerReady() { return true; }

	@WrapMethod(method = "tick", require = 1)
	private static void productivebeesgenesis$resourcePoweredTick(Level level, BlockPos pos, BlockState state,
			PoweredCentrifugeBlockEntity entity, Operation<Void> original) {
		if (!PbResourceCentrifuge.tick(entity)) original.call(level, pos, state, entity);
	}
}
