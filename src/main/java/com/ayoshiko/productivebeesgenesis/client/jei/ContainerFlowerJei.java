package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import cy.jdkdigital.productivebees.common.recipe.BeeFloweringRecipe;
import cy.jdkdigital.productivebees.compat.jei.ProductiveBeesJeiPlugin;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import java.util.List;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.recipe.RecipeIngredientRole;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;

/** 容器只是提示图标，实际可用容器由花源能力检测决定，不能列成普通花朵。 */
public final class ContainerFlowerJei {
    private ContainerFlowerJei() {}

    private static final IIngredientRenderer<ItemStack> CONTAINER_ICON = new IIngredientRenderer<>() {
        @Override
        public void render(GuiGraphics graphics, ItemStack stack) {
            graphics.renderItem(stack, 0, 0);
        }

        @Override
        public List<Component> getTooltip(ItemStack stack, TooltipFlag flag) {
            return List.of(Component.translatable("jei.productivebeesgenesis.container_flower.title"));
        }
    };

    public static ContainerFlower requirement(BeeFloweringRecipe recipe) {
        var data = BeeReloadListener.INSTANCE.getData(recipe.bee().getBeeType());
        return data == null ? null : ContainerFlower.fromData(data);
    }

    public static boolean setRecipe(IRecipeLayoutBuilder builder, BeeFloweringRecipe recipe) {
        var requirement = requirement(recipe);
        if (requirement == null) return false;
        builder.addSlot(RecipeIngredientRole.INPUT, 29, 12)
                .addIngredient(ProductiveBeesJeiPlugin.BEE_INGREDIENT, recipe.bee()).setSlotName("source");
        builder.addSlot(RecipeIngredientRole.RENDER_ONLY, 26, 51)
                .addItemStack(display(requirement)).setSlotName("container_requirement")
                .setCustomRenderer(VanillaTypes.ITEM_STACK, CONTAINER_ICON)
                .addRichTooltipCallback((slot, tooltip) -> {
                    tooltip.add(requirement.description());
                    tooltip.add(Component.translatable("jei.productivebeesgenesis.container_flower.single"));
                    tooltip.add(Component.translatable("jei.productivebeesgenesis.container_flower.feeder"));
                });
        return true;
    }

    private static ItemStack display(ContainerFlower requirement) {
        var minecraft = Minecraft.getInstance();
        var level = minecraft == null ? null : minecraft.level;
        if (level != null && net.neoforged.fml.ModList.get().isLoaded("ae2")) {
            return com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ContainerFlowerDisplay.cell(requirement, level.registryAccess());
        }
        return new ItemStack(Items.PAPER);
    }
}
