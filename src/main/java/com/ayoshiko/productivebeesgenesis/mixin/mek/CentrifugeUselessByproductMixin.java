package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** Applies Productive Bees byproduct filtering and essence conversion to centrifuges. */
@Mixin(CentrifugeBlockEntity.class)
public abstract class CentrifugeUselessByproductMixin {

	@ModifyArg(
			method = "canProcessRecipe",
			at = @At(
					value = "INVOKE",
					target = "Lcy/jdkdigital/productivelib/common/block/entity/InventoryHandlerHelper$BlockEntityItemStackHandler;"
							+ "canFitStacks(Ljava/util/List;)Z",
					remap = false
			),
			index = 0
	)
	private List<ItemStack> productivebeesgenesis$ignoreWaxForCapacity(List<ItemStack> outputs) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (!UselessByproductUpgradeHelper.hasUpgrade(blockEntity)) return outputs;
		// 不得就地 removeIf：该入参由上游构造，可能是 List.of()/Stream.toList() 等不可变实现，
		// 就地修改会抛 UnsupportedOperationException，把一次正常的容量判定变成配方处理崩溃。
		// 先只做一次只读探测，无副产物时原样返回（零分配），有副产物时才构造过滤后的新列表。
		boolean hasWax = false;
		for (int i = 0; i < outputs.size(); i++) {
			if (UselessByproductUpgradeHelper.isWax(outputs.get(i))) {
				hasWax = true;
				break;
			}
		}
		if (!hasWax) return outputs;
		List<ItemStack> filtered = new java.util.ArrayList<>(outputs.size());
		for (int i = 0; i < outputs.size(); i++) {
			ItemStack stack = outputs.get(i);
			if (!UselessByproductUpgradeHelper.isWax(stack)) filtered.add(stack);
		}
		return filtered;
	}

	@ModifyVariable(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;"
					+ "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At("HEAD"),
			argsOnly = true,
			ordinal = 0
	)
	private boolean productivebeesgenesis$discardWax(boolean stripWax) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		return stripWax || UselessByproductUpgradeHelper.hasUpgrade(blockEntity);
	}

	/**
	 * 丢弃蜂蜜副产物（安装无用副产物升级时）。
	 * <p>
	 * <b>从 {@code @Redirect} 改为 {@code @WrapOperation}</b>：{@code @Redirect} 对同一调用点是
	 * 独占的，任何其它模组只要也改写这次 {@code FluidTank.fill} 调用就会与本模组直接冲突。
	 * {@code @WrapOperation} 允许链式共存，处理体与原实现逐字等价。
	 * <p>
	 * <b>{@code require = 0}</b>：目标属第三方模组（Productive Bees）。上游重构方法体时
	 * 最坏回到「不过滤蜂蜜」的原版行为，而不是启动崩溃。
	 */
	@WrapOperation(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I",
					remap = false
			),
			require = 0
	)
	private int productivebeesgenesis$discardHoney(FluidTank tank, FluidStack stack,
			IFluidHandler.FluidAction action, Operation<Integer> original) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (UselessByproductUpgradeHelper.hasUpgrade(blockEntity)
				&& UselessByproductUpgradeHelper.isHoney(stack)) {
			return 0;
		}
		return original.call(tank, stack, action);
	}

	/** 在资源蜜蜂离心机完成配方输出后，转换已聚合的物品产物。 */
	@Inject(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;"
					+ "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At("TAIL")
	)
	private void productivebeesgenesis$convertEssence(
			RecipeHolder<CentrifugeRecipe> recipe,
			net.neoforged.neoforge.items.IItemHandlerModifiable inventory,
			RandomSource random, boolean stripWax, int productivityModifier, CallbackInfo ci) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (EssenceConversionUpgradeHelper.hasUpgrade(blockEntity)) {
			EssenceConversionUpgradeHelper.convertStored(blockEntity.getLevel(), inventory);
		}
	}

	/** 在资源蜜蜂离心机完成配方输出后，将粗矿按 Mekanism 熔炼配方转换为锭。 */
	@Inject(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;"
					+ "Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At("TAIL")
	)
	private void productivebeesgenesis$convertRawOre(
			RecipeHolder<CentrifugeRecipe> recipe,
			net.neoforged.neoforge.items.IItemHandlerModifiable inventory,
			RandomSource random, boolean stripWax, int productivityModifier, CallbackInfo ci) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (RawOreSmeltingUpgradeHelper.hasUpgrade(blockEntity)) {
			RawOreSmeltingUpgradeHelper.convertStored(blockEntity.getLevel(), inventory);
		}
	}
}
