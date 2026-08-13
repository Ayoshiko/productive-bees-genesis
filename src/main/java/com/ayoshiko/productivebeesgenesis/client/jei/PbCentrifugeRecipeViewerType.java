package com.ayoshiko.productivebeesgenesis.client.jei;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
	 * PB离心配方的Mekanism配方查看器类型
	 * <br/>
	 * 实现IRecipeViewerRecipeType接口，使GuiProgress能通过recipeViewerCategories
	 * 同时注册SMELTING和PB离心配方两种类型，实现双配方JEI跳转。
	 * <p>
	 * 原理：Mekanism的GuiProgress.recipeViewerCategories接受IRecipeViewerRecipeType可变参数，
	 * 鼠标悬停进度条时JEI会显示所有注册类型的"Show Recipes"提示，点击可跳转到对应分类。
	 * <p>
	 * 与RVRecipeTypeWrapper的区别：
	 * - RVRecipeTypeWrapper要求RECIPE extends MekanismRecipe，PB的CentrifugeRecipe不满足
	 * - 本类直接实现IRecipeViewerRecipeType，不依赖MekanismRecipe
	 * - requiresHolder()返回false，因为PB配方不使用RecipeHolder包装
	 */
public record PbCentrifugeRecipeViewerType(
		ResourceLocation id,
		ItemLike iconItem,
		Component textComponent,
		Class<? extends CentrifugeRecipe> recipeClass,
		int xOffset,
		int yOffset,
		int width,
		int height,
		List<ItemLike> workstations
) implements IRecipeViewerRecipeType<CentrifugeRecipe> {

	/**
	 * 构造PB离心配方查看器类型
	 *
	 * @param id            配方类型ID（用于JEI内部标识）
	 * @param iconItem      图标物品（显示在JEI分类标签上）
	 * @param textComponent 分类标题文本
	 * @param altWorkstations 工作站物品（作为催化剂注册）
	 */
	public PbCentrifugeRecipeViewerType(ResourceLocation id, ItemLike iconItem, Component textComponent,
										ItemLike... altWorkstations) {
		this(id, iconItem, textComponent, CentrifugeRecipe.class,
				-28, -16, 144, 70,
				altWorkstations.length == 0 ? List.of(iconItem) : List.of(altWorkstations));
	}

	@Override
	public ResourceLocation id() {
		return id;
	}

	@Override
	public Class<? extends CentrifugeRecipe> recipeClass() {
		return recipeClass;
	}

	/**
	 * PB离心配方不使用RecipeHolder包装
	 */
	@Override
	public boolean requiresHolder() {
		return false;
	}

	@Override
	public ItemStack iconStack() {
		return new ItemStack(iconItem);
	}

	@Nullable
	@Override
	public ResourceLocation icon() {
		// 使用iconStack作为图标，不使用纹理路径
		return null;
	}

	@Override
	public int xOffset() {
		return xOffset;
	}

	@Override
	public int yOffset() {
		return yOffset;
	}

	@Override
	public int width() {
		return width;
	}

	@Override
	public int height() {
		return height;
	}

	@Override
	public List<ItemLike> workstations() {
		return workstations;
	}

	@Override
	public Component getTextComponent() {
		return textComponent;
	}

	/**
	 * 兼容IHasTextComponent接口（部分Mekanism版本通过此接口获取文本）
	 */
	public Component getTranslationComponent() {
		return textComponent;
	}
}
