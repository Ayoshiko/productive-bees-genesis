package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 只观察显式删除或替换的旧实体；clearAllBlockEntities 的卸载路径不撤销账户。 */
@Mixin(LevelChunk.class)
public abstract class PbResourceHostRemovalMixin {
	@WrapOperation(method = {"removeBlockEntity", "setBlockEntity"},
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/BlockEntity;setRemoved()V"),
			require = 2)
	private void productivebeesgenesis$retireResourceHost(BlockEntity entity, Operation<Void> original) {
		try {
			var current = ((LevelChunk) (Object) this).getBlockEntities().get(entity.getBlockPos());
			if (!ResourceHiveHost.sameLiveReference(entity, current)) ResourceHiveHost.removed(entity);
		} finally {
			original.call(entity);
		}
	}
}
