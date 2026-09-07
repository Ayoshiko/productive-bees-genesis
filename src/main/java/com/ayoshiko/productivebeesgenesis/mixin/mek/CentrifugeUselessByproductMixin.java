package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
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
import org.spongepowered.asm.mixin.injection.Redirect;
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
		if (UselessByproductUpgradeHelper.hasUpgrade(blockEntity)) {
			outputs.removeIf(UselessByproductUpgradeHelper::isWax);
		}
		return outputs;
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

	@Redirect(
			method = "completeRecipeProcessing(Lnet/minecraft/world/item/crafting/RecipeHolder;Lnet/neoforged/neoforge/items/IItemHandlerModifiable;Lnet/minecraft/util/RandomSource;ZI)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I",
					remap = false
			)
	)
	private int productivebeesgenesis$discardHoney(FluidTank tank, FluidStack stack,
			IFluidHandler.FluidAction action) {
		CentrifugeBlockEntity blockEntity = (CentrifugeBlockEntity) (Object) this;
		if (UselessByproductUpgradeHelper.hasUpgrade(blockEntity)
				&& UselessByproductUpgradeHelper.isHoney(stack)) {
			return 0;
		}
		return tank.fill(stack, action);
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
