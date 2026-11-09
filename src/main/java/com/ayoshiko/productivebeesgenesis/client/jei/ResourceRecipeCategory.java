package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.*;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 固定最多十六项产物；悬停给出完整原始单位、概率与四份独立抽样语义。 */
public final class ResourceRecipeCategory implements IRecipeCategory<ResourceJeiRecipe> {
	private final boolean centrifuging;
	private final IDrawable icon;
	public ResourceRecipeCategory(IGuiHelper helper, boolean centrifuging, ItemStack icon) {
		this.centrifuging = centrifuging;
		this.icon = helper.createDrawableIngredient(VanillaTypes.ITEM_STACK, icon);
	}
	@Override public RecipeType<ResourceJeiRecipe> getRecipeType() { return centrifuging ? ResourceJeiPlugin.CENTRIFUGING : ResourceJeiPlugin.PRODUCTION; }
	@Override public Component getTitle() { return Component.translatable("jei.productivebeesgenesis.resource_" + (centrifuging ? "centrifuging" : "production")); }
	@Override public int getWidth() { return 176; }
	@Override public int getHeight() { return 144; }
	@Override public IDrawable getIcon() { return icon; }
	@Override public ResourceLocation getRegistryName(ResourceJeiRecipe value) { return value.recipe().id().withPrefix(value.block() ? "resource_block/" : "resource_single/"); }

	@Override public void setRecipe(IRecipeLayoutBuilder builder, ResourceJeiRecipe value, IFocusGroup focuses) {
		var recipe = value.recipe();
		if (centrifuging) {
			var input = new ResourceCentrifugeRecipe(recipe.bee(), recipe.outputs(), recipe.processingTime()).input(value.block());
			var slot = builder.addSlot(RecipeIngredientRole.INPUT, 8, 38).addItemStack(input).setStandardSlotBackground();
			if (recipe.includePbOutputs()) slot.addRichTooltipCallback((view, tooltip) ->
					tooltip.add(Component.translatable("jei.productivebeesgenesis.resource_includes_pb")));
		} else {
			var bee = BeeIngredientFactory.getOrCreateList().get(recipe.bee().toString());
			var type = PbCompatRefs.getBeeIngredientType();
			if (bee != null && type instanceof IIngredientType<?> ingredientType) addBee(builder, ingredientType, bee);
		}
		for (int i = 0; i < recipe.outputs().size(); i++) {
			var out = recipe.outputs().get(i);
			builder.addSlot(RecipeIngredientRole.OUTPUT, 52 + i % 4 * 30, 28 + i / 4 * 24)
					.addIngredient(ResourceIngredient.TYPE, new ResourceIngredient(out.key(), out.exactAmount(), value.portions()))
					.setStandardSlotBackground().setSlotName("resource_" + i)
					.addRichTooltipCallback((slot, tooltip) -> tooltip.add(Component.translatable(
							"jei.productivebeesgenesis.resource_chance", java.math.BigDecimal.valueOf(out.chance())
									.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString())));
		}
	}
	@SuppressWarnings("unchecked")
	private static void addBee(IRecipeLayoutBuilder builder, IIngredientType<?> type, BeeIngredient bee) {
		builder.addSlot(RecipeIngredientRole.INPUT, 4, 30).addIngredient((IIngredientType<BeeIngredient>) type, bee);
	}

	@Override public void draw(ResourceJeiRecipe value, IRecipeSlotsView slots, GuiGraphics graphics, double mouseX, double mouseY) {
		var font = Minecraft.getInstance().font;
		var bee = BeeIngredientFactory.getOrCreateList().get(value.recipe().bee().toString());
		Component name = bee != null && Minecraft.getInstance().level != null
				? bee.getCachedEntity(Minecraft.getInstance().level).getName() : Component.translatable("jei.productivebeesgenesis.resource_unknown_bee");
		if (name.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translation
				&& !net.minecraft.locale.Language.getInstance().has(translation.getKey())) {
			name = Component.literal(value.recipe().bee().toString());
		}
		graphics.drawString(font, font.substrByWidth(name, 174).getString(), 1, 2, 0xFF404040, false);
		if (centrifuging) graphics.drawString(font, Component.translatable("jei.productivebeesgenesis.resource_time",
				value.recipe().processingTime()), 1, 14, 0xFF606060, false);
		graphics.drawString(font, "->", 30, 42, 0xFF606060, false);
		for (int i = 0; i < value.recipe().outputs().size(); i++) {
			var out = value.recipe().outputs().get(i);
			String amount = ResourceVisuals.amount(out.key(), out.exactAmount(), value.portions());
			if (font.width(amount) > 28) amount = "...";
			graphics.drawString(font, amount, 52 + i % 4 * 30, 45 + i / 4 * 24, 0xFF404040, false);
		}
		var note = Component.translatable(value.block() ? "jei.productivebeesgenesis.resource_four_rolls"
				: "jei.productivebeesgenesis.resource_base_amount");
		graphics.drawString(font, font.substrByWidth(note, 174).getString(), 1, 128, 0xFF606060, false);
	}
}
