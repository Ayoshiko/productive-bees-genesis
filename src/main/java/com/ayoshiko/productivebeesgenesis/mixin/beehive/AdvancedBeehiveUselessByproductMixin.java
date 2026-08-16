package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.util.BeeHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.entity.BeehiveBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Applies useless-byproduct filtering to Productive Bees advanced hives. */
@Mixin(AdvancedBeehiveBlockEntity.class)
public abstract class AdvancedBeehiveUselessByproductMixin {

	@Redirect(
			method = "beeReleasePostAction",
			at = @At(
					value = "INVOKE",
					target = "Lcy/jdkdigital/productivebees/util/BeeHelper;getBeeProduce(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/animal/Bee;ZD)Ljava/util/List;"
			)
	)
	private List<ItemStack> productivebeesgenesis$discardPollenPuffs(
			Level level, Bee bee, boolean hasBlockUpgrade, double productivityModifier) {
		List<ItemStack> produce = BeeHelper.getBeeProduce(level, bee, hasBlockUpgrade, productivityModifier);
		if (!UselessByproductUpgradeHelper.hasUpgrade((AdvancedBeehiveBlockEntity) (Object) this)) {
			return produce;
		}
		return produce.stream()
				.filter(stack -> !UselessByproductUpgradeHelper.isPollenPuff(stack))
				.toList();
	}

	@Inject(method = "beeReleasePostAction", at = @At("TAIL"))
	private void productivebeesgenesis$restoreHoneyLevel(Level level, Bee bee, BlockState state,
			BeehiveBlockEntity.BeeReleaseStatus releaseStatus, CallbackInfo ci) {
		AdvancedBeehiveBlockEntity blockEntity = (AdvancedBeehiveBlockEntity) (Object) this;
		if (releaseStatus != BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED
				|| !UselessByproductUpgradeHelper.hasUpgrade(blockEntity)
				|| !state.hasProperty(BeehiveBlock.HONEY_LEVEL)) {
			return;
		}
		BlockPos pos = blockEntity.getBlockPos();
		BlockState current = level.getBlockState(pos);
		if (current.hasProperty(BeehiveBlock.HONEY_LEVEL)) {
			int originalLevel = state.getValue(BeehiveBlock.HONEY_LEVEL);
			if (current.getValue(BeehiveBlock.HONEY_LEVEL) != originalLevel) {
				level.setBlockAndUpdate(pos, current.setValue(BeehiveBlock.HONEY_LEVEL, originalLevel));
			}
		}
	}
}
