package com.ayoshiko.productivebeesgenesis.mixin.multiblock;

import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineWorldService;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 静默放置也先撤销旧凭据；不依赖邻居通知或外部方块的回调。 */
@Mixin(LevelChunk.class)
public abstract class StructureMutationMixin {
	@Shadow @Final private Level level;
	@Shadow public abstract BlockState getBlockState(BlockPos pos);

	@Inject(method = "setBlockState", at = @At("HEAD"))
	private void pbg$invalidateBeforeMutation(BlockPos pos, BlockState next, boolean moved,
			CallbackInfoReturnable<BlockState> callback) {
		if (MachineWorldService.watches(level)) MachineWorldService.blockChanged(level, pos, getBlockState(pos), next);
	}
}
