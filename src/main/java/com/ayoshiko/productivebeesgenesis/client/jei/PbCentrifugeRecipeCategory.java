package com.ayoshiko.productivebeesgenesis.client.jei;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.gui.widgets.IRecipeExtrasBuilder;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

	/** 背景宽度（与纹理区域一致） */
	private static final int BACKGROUND_WIDTH = 126;
	/** 背景高度（与纹理区域一致） */
	private static final int BACKGROUND_HEIGHT = 70;

	/** 数量变化只用于轮换展示，限制每个输出建立材料索引时的复制数量。 */
	static final int MAX_DISPLAY_COUNTS = 8;

	private final IDrawable icon;

	public PbCentrifugeRecipeCategory(IGuiHelper guiHelper, ItemStack iconStack) {
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

	@Override
	public int getWidth() {
		return BACKGROUND_WIDTH;
	}

	@Override
	public int getHeight() {
		return BACKGROUND_HEIGHT;
	}

	/**
	 * 绘制配方背景纹理
	 * <br/>
	 * JEI 19.36+ 用 getWidth/getHeight + draw 替代旧的 getBackground()。
	 * JEI 会在 (0,0) 起始的区域绘制，blit 直接映射纹理的 (0,0)~(126,70) 区域。
	 */
	@Override
	public void draw(
		CentrifugeRecipe recipe,
		IRecipeSlotsView recipeSlotsView,
		GuiGraphics guiGraphics,
		double mouseX,
		double mouseY
	) {
		guiGraphics.blit(BACKGROUND_TEXTURE, 0, 0, 0, 0, BACKGROUND_WIDTH, BACKGROUND_HEIGHT);
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
	 * - 输出槽：每个ChancedOutput生成一个槽位，展示有界数量候选
	 * - 流体槽：如果有流体输出，占用下一个可用输出槽位
	 * - 概率tooltip：显示产出概率和数量范围
	 */
	@Override
	public void setRecipe(IRecipeLayoutBuilder builder, CentrifugeRecipe recipe, IFocusGroup focuses) {
		setRecipeLayout(builder, recipe, false);
	}

	@Override
	public void createRecipeExtras(IRecipeExtrasBuilder builder, CentrifugeRecipe recipe, IFocusGroup focuses) {
		if (MyriadJeiOutputs.isMyriad(recipe)) {
			MyriadJeiOutputs.addCaptions(builder);
		}
	}

	/** 普通、热能和 Genesis 分类共用布局；热能分类仍按 PB 规则过滤蜂蜡。 */
	public static void setRecipeLayout(IRecipeLayoutBuilder builder, CentrifugeRecipe recipe, boolean stripWax) {
		// 输入槽
		builder.addSlot(RecipeIngredientRole.INPUT, 5, 27)
				.addItemStacks(Arrays.stream(recipe.ingredient.getItems()).toList())
				.setSlotName("ingredient");

		int randomSlots = MyriadJeiOutputs.isMyriad(recipe) ? 3 : 0;
		if (randomSlots > 0) {
			MyriadJeiOutputs.addCentrifugeSlots(builder, recipe);
		}

		// 输出槽布局：从(68,26)开始，每3个换行
		int startX = 68;
		int startY = 26;

		// 物品输出：使用索引for循环，循环变量i在每次迭代中为effectively final，无需final快照
		List<Map.Entry<ItemStack, TagOutputRecipe.ChancedOutput>> outputEntries =
				recipe.getRecipeOutputs().entrySet().stream()
						.filter(entry -> !stripWax || !entry.getKey().is(cy.jdkdigital.productivebees.init.ModTags.Common.WAXES))
						.toList();
		for (int i = 0; i < outputEntries.size(); i++) {
			Map.Entry<ItemStack, TagOutputRecipe.ChancedOutput> entry = outputEntries.get(i);
			ItemStack stack = entry.getKey();
			TagOutputRecipe.ChancedOutput value = entry.getValue();

			List<ItemStack> innerList = displayOutputStacks(stack, value.min(), value.max());

			// 使用模运算计算行列位置：col = i % 3, row = i / 3
			int slotIndex = i + randomSlots;
			int col = slotIndex % 3;
			int row = slotIndex / 3;
			builder.addSlot(RecipeIngredientRole.OUTPUT, startX + (col * 18) + 1, startY + (row * 18) + 1)
					.addItemStacks(innerList)
					.setStandardSlotBackground()
					.addRichTooltipCallback((recipeSlotView, tooltip) -> {
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
					.setSlotName("output" + i);
		}

		// 流体输出
		FluidStack fluid = recipe.getFluidOutputs();
		if (!fluid.isEmpty()) {
			int slotIndex = outputEntries.size() + randomSlots;
			int col = slotIndex % 3;
			int row = slotIndex / 3;
			builder.addSlot(RecipeIngredientRole.OUTPUT, startX + (col * 18) + 1, startY + (row * 18) + 1)
					.addIngredient(NeoForgeTypes.FLUID_STACK, fluid)
					.setFluidRenderer(Math.max(1000, fluid.getAmount()), false, 16, 16)
					.setStandardSlotBackground()
					.addRichTooltipCallback((recipeSlotView, tooltip) ->
							tooltip.add(Component.translatable("productivebees.centrifuge.tooltip.amount",
									fluid.getAmount() + "mB")))
					.setSlotName("output_fluid");
		}
	}

	/** 区间两端始终保留；非正数没有可显示的物品，原始区间仍由 tooltip 说明。 */
	static List<ItemStack> displayOutputStacks(ItemStack stack, int min, int max) {
		int first = Math.max(1, min);
		if (stack.isEmpty() || max < first) {
			return List.of();
		}
		long span = (long) max - first;
		int variants = (int) Math.min(MAX_DISPLAY_COUNTS, span + 1L);
		List<ItemStack> stacks = new ArrayList<>(variants);
		for (int i = 0; i < variants; i++) {
			long offset = variants == 1 ? 0L : span * i / (variants - 1);
			ItemStack candidate = stack.copy();
			candidate.setCount((int) (first + offset));
			stacks.add(candidate);
		}
		return stacks;
	}
}
