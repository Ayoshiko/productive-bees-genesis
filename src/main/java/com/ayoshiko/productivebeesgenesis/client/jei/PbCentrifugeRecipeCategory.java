package com.ayoshiko.productivebeesgenesis.client.jei;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * PB离心配方的JEI分类
 * <br/>
 * 在JEI中展示PB CentrifugeRecipe的输入物品、概率输出物品和流体输出。
 * 与PB原版CentrifugeRecipeCategory的区别：
 * - 使用独立的RecipeType（productivebeesgenesis:pb_centrifuge）
 * - 复用PB原版的centrifuge_recipe.png纹理作为背景
 * - 作为MEK离心机的JEI跳转目标
 * <p>
 * 布局参考PB原版（126x70）：
 * - 输入槽(5,27)
 * - 输出槽起始(68,26)，每3个换行
 * - 流体输出槽（与物品输出共享槽位区域）
 */
public class PbCentrifugeRecipeCategory implements IRecipeCategory<CentrifugeRecipe> {

	/** JEI分类背景纹理路径（复用PB原版纹理） */
	private static final ResourceLocation BACKGROUND_TEXTURE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "textures/gui/jei/centrifuge_recipe.png");

	private final IDrawable background;
	private final IDrawable icon;

	public PbCentrifugeRecipeCategory(IGuiHelper guiHelper, ItemStack iconStack) {
		this.background = guiHelper.createDrawable(BACKGROUND_TEXTURE, 0, 0, 126, 70);
		this.icon = guiHelper.createDrawableIngredient(VanillaTypes.ITEM_STACK, iconStack);
	}

	@Override
	public RecipeType<CentrifugeRecipe> getRecipeType() {
		return ProductiveBeesGenesisJEI.PB_CENTRIFUGE_TYPE;
	}

	@Nonnull
	@Override
	public Component getTitle() {
		return Component.translatable("jei.productivebeesgenesis.pb_centrifuge");
	}

	@Nonnull
	@Override
	public IDrawable getBackground() {
		return this.background;
	}

	@Nonnull
	@Override
	public IDrawable getIcon() {
		return icon;
	}

	/**
	 * 设置配方布局
	 * <br/>
	 * 原理：
	 * - 输入槽：展示配方ingredient的所有可能物品
	 * - 输出槽：每个ChancedOutput生成一个槽位，展示min~max所有可能数量
	 * - 流体槽：如果有流体输出，占用下一个可用输出槽位
	 * - 概率tooltip：显示产出概率和数量范围
	 */
	@Override
	public void setRecipe(IRecipeLayoutBuilder builder, CentrifugeRecipe recipe, IFocusGroup focuses) {
		// 输入槽
		builder.addSlot(RecipeIngredientRole.INPUT, 5, 27)
				.addItemStacks(Arrays.stream(recipe.ingredient.getItems()).toList())
				.setSlotName("ingredient");

		// 输出槽布局：从(68,26)开始，每3个换行
		int startX = 68;
		int startY = 26;
		final int[] slotIndex = {0};

		// 物品输出
		if (recipe.getRecipeOutputs().size() > 0) {
			recipe.getRecipeOutputs().forEach((stack, value) -> {
				// 为每个可能的输出数量生成一个物品堆栈
				List<ItemStack> innerList = new ArrayList<>();
				IntStream.range(value.min(), value.max() + 1).forEach(u -> {
					ItemStack newStack = stack.copy();
					newStack.setCount(u);
					innerList.add(newStack);
				});

				// 使用模运算计算行列位置：col = slotIndex % 3, row = slotIndex / 3
				int col = slotIndex[0] % 3;
				int row = slotIndex[0] / 3;
				builder.addSlot(RecipeIngredientRole.OUTPUT, startX + (col * 18) + 1, startY + (row * 18) + 1)
						.addItemStacks(innerList)
						.addTooltipCallback((recipeSlotView, tooltip) -> {
							float chance = value.chance() * 100f;
							if (chance < 100) {
								tooltip.add(Component.translatable("productivebees.centrifuge.tooltip.chance",
										chance < 1 ? "<1%" : chance + "%"));
							}
							if (value.min() != value.max()) {
								tooltip.add(Component.translatable("productivebees.centrifuge.tooltip.amount",
										value.min() + " - " + value.max()));
							}
						})
						.setSlotName("output" + slotIndex[0]);

				slotIndex[0]++;
			});
		}

		// 流体输出
		FluidStack fluid = recipe.getFluidOutputs();
		if (!fluid.isEmpty()) {
			int col = slotIndex[0] % 3;
			int row = slotIndex[0] / 3;
			builder.addSlot(RecipeIngredientRole.OUTPUT, startX + (col * 18) + 1, startY + (row * 18) + 1)
					.addIngredient(NeoForgeTypes.FLUID_STACK, fluid)
					.addTooltipCallback((recipeSlotView, tooltip) ->
							tooltip.add(Component.translatable("productivebees.centrifuge.tooltip.amount",
									fluid.getAmount() + "mB")))
					.setSlotName("output_fluid");
		}
	}
}
