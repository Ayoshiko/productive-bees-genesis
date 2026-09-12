package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Applies output filtering and essence conversion to Productive Bees advanced hives. */
@Mixin(AdvancedBeehiveBlockEntity.class)
public abstract class AdvancedBeehiveUselessByproductMixin {

	/**
	 * 过滤无用副产物（花粉团）。
	 * <p>
	 * <b>从 {@code @Redirect} 改为 {@code @WrapOperation}</b>：{@code @Redirect} 对同一调用点是
	 * <b>独占</b>的，任何其它模组只要也改写这次 {@code BeeHelper.getBeeProduce} 调用，
	 * 就会与本模组直接冲突（加载期报冲突或静默互相覆盖）。{@code @WrapOperation} 允许链式共存，
	 * 且处理体与原实现逐字等价（同样用原参数调用原方法，再按升级状态过滤）。
	 * <p>
	 * <b>{@code require = 0}</b>：目标属第三方模组（Productive Bees）。若上游重构该方法体，
	 * 最坏结果是回到「不过滤花粉团」的原版行为，而不是让游戏启动崩溃 ——
	 * 与本项目 {@code TileComponentEjectorFastPathMixin} 的既定约定一致。
	 */
	@WrapOperation(
			method = "beeReleasePostAction",
			at = @At(
					value = "INVOKE",
					target = "Lcy/jdkdigital/productivebees/util/BeeHelper;getBeeProduce(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/animal/Bee;ZD)Ljava/util/List;"
			),
			require = 0
	)
	private List<ItemStack> productivebeesgenesis$discardPollenPuffs(
			Level level, Bee bee, boolean hasBlockUpgrade, double productivityModifier,
			Operation<List<ItemStack>> original) {
		List<ItemStack> produce = original.call(level, bee, hasBlockUpgrade, productivityModifier);
		if (!UselessByproductUpgradeHelper.hasUpgrade((AdvancedBeehiveBlockEntity) (Object) this)) {
			return produce;
		}
		// 不就地修改：返回新的不可变列表，避免改到上游共享/不可变实现
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

	/** 在蜂箱完成一次蜜蜂释放后，将已写入输出槽的产物按精华升级转换。 */
	@Inject(method = "beeReleasePostAction", at = @At("TAIL"))
	private void productivebeesgenesis$convertEssence(Level level, Bee bee, BlockState state,
			BeehiveBlockEntity.BeeReleaseStatus releaseStatus, CallbackInfo ci) {
		AdvancedBeehiveBlockEntity blockEntity = (AdvancedBeehiveBlockEntity) (Object) this;
		if (releaseStatus == BeehiveBlockEntity.BeeReleaseStatus.HONEY_DELIVERED
				&& EssenceConversionUpgradeHelper.hasUpgrade(blockEntity)) {
			EssenceConversionUpgradeHelper.convertStored(level, blockEntity.inventoryHandler);
		}
	}
}
