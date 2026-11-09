package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceCentrifuge;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceCentrifugeBridge;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 资源蜜脾在原计时与扣料前进入统一事务，普通 PB 输入继续使用原方法。 */
@Mixin(CentrifugeBlockEntity.class)
public abstract class PbResourceCentrifugeMixin implements PbResourceCentrifugeBridge {
	@Invoker("getProcessingTimeModifier")
	public abstract double productivebeesgenesis$resourceTimeModifier();

	@WrapMethod(method = "tick", require = 1)
	private static void productivebeesgenesis$resourceTick(Level level, BlockPos pos, BlockState state,
			CentrifugeBlockEntity entity, Operation<Void> original) {
		if (!PbResourceCentrifuge.tick(entity)) original.call(level, pos, state, entity);
	}

	@WrapMethod(method = "canProcessItemStack", require = 1)
	private boolean productivebeesgenesis$resourceInput(ItemStack stack, Operation<Boolean> original) {
		var self = (CentrifugeBlockEntity) (Object) this;
		return PbResourceCentrifuge.managed(self, stack) ? PbResourceCentrifuge.accepts(self, stack) : original.call(stack);
	}
}
