package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 资源蜜蜂：创世模组的JEI插件
 * <br/>
 * 注册PB离心配方的JEI分类，使MEK离心机的GuiProgress能跳转到PB离心配方界面。
 * <p>
 * 双配方跳转机制：
 * - SMELTING配方：通过Mekanism原生的recipeViewerType()返回RecipeViewerRecipeType.SMELTING
 * - PB离心配方：通过recipeViewerCategories()额外注册PB_CENTRIFUGE_VIEWER_TYPE
 * - JEI会同时显示两种配方的"Show Recipes"提示，玩家可选择跳转目标
 * <p>
 * 催化剂注册：
 * - 所有等级的MEK离心机方块（基础+4原版工厂+EM扩展工厂）都注册为PB离心配方的催化剂
 * - 在JEI中点击PB离心配方时，左侧会显示所有可执行该配方的机器
 */
@JeiPlugin
public class ProductiveBeesGenesisJEI implements IModPlugin {

    /** JEI插件ID */
    private static final ResourceLocation PLUGIN_ID =
            ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "jei_plugin");

    /** JEI配方类型（用于JEI内部分类标识） */
    public static final mezz.jei.api.recipe.RecipeType<CentrifugeRecipe> PB_CENTRIFUGE_TYPE =
            mezz.jei.api.recipe.RecipeType.create(ProductiveBeesGenesis.MOD_ID, "pb_centrifuge", CentrifugeRecipe.class);

    /**
     * Mekanism配方查看器类型（用于GuiProgress的recipeViewerCategories）
     * <br/>
     * 延迟初始化：首次访问时创建，避免在类加载时引用ModBlocks导致初始化顺序问题。
     * 使用Holder模式保证线程安全的懒加载。
     */
    private static volatile IRecipeViewerRecipeType<CentrifugeRecipe> pbCentrifugeViewerType;

    /**
     * 获取PB离心配方的Mekanism配方查看器类型
     * <br/>
     * 用于在GuiProgress.recipeViewerCategories()中注册，实现双配方JEI跳转。
     * 线程安全：使用volatile + synchronized保证双重检查锁定的正确性。
     *
     * @return PB离心配方的IRecipeViewerRecipeType实例
     */
    public static IRecipeViewerRecipeType<CentrifugeRecipe> getPbCentrifugeViewerType() {
        if (pbCentrifugeViewerType == null) {
            synchronized (ProductiveBeesGenesisJEI.class) {
                if (pbCentrifugeViewerType == null) {
                    // 动态构建工作站列表：基础+原版工厂+条件加载的EM/ME/EME工厂
                    List<ItemLike> workstations = new ArrayList<>();
                    workstations.add(ModBlocks.MEK_CENTRIFUGE.get());
                    workstations.add(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get());
                    workstations.add(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY.get());
                    workstations.add(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY.get());
                    workstations.add(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get());
                    // EM工厂（仅EM加载时）
                    for (var entry : ModBlocks.EM_FACTORIES.entrySet()) {
                        workstations.add(entry.getValue().get());
                    }
                    // ME工厂（仅ME加载时）
                    for (var entry : ModBlocks.ME_FACTORIES.entrySet()) {
                        workstations.add(entry.getValue().get());
                    }
                    // EME工厂（仅EME加载时）
                    for (var entry : ModBlocks.EME_FACTORIES.entrySet()) {
                        workstations.add(entry.getValue().get());
                    }
                    pbCentrifugeViewerType = new PbCentrifugeRecipeViewerType(
                            ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "pb_centrifuge"),
                            ModBlocks.MEK_CENTRIFUGE.get(),
                            Component.translatable("jei.productivebeesgenesis.pb_centrifuge"),
                            workstations.toArray(new ItemLike[0])
                    );
                }
            }
        }
        return pbCentrifugeViewerType;
    }

    @NotNull
    @Override
    public ResourceLocation getPluginUid() {
        return PLUGIN_ID;
    }

    /**
     * 注册JEI分类
     * <br/>
     * 创建PB离心配方分类，使用MEK离心机方块作为图标。
     */
    @Override
    public void registerCategories(IRecipeCategoryRegistration registry) {
        IGuiHelper guiHelper = registry.getJeiHelpers().getGuiHelper();
        ItemStack iconStack = new ItemStack(ModBlocks.MEK_CENTRIFUGE.get());
        registry.addRecipeCategories(new PbCentrifugeRecipeCategory(guiHelper, iconStack));
    }

    /**
     * 注册PB离心配方（蜜脾 + 动态生成的蜜脾块）
     * <br/>
     * 从PB的RecipeManager获取所有CentrifugeRecipe注册到JEI，
     * 并为每个有bee_type的蜜脾配方动态生成对应的蜜脾块配方（4倍产出）。
     */
    @Override
    public void registerRecipes(IRecipeRegistration registry) {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        // 获取PB原版的所有离心配方
        List<RecipeHolder<CentrifugeRecipe>> centrifugeRecipes =
                Minecraft.getInstance().level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get());

        // 收集所有配方（蜜脾 + 动态生成的蜜脾块）
        List<CentrifugeRecipe> allRecipes = new ArrayList<>(
                centrifugeRecipes.stream().map(RecipeHolder::value).toList());
        allRecipes.addAll(generateCombBlockRecipes(centrifugeRecipes));

        registry.addRecipes(PB_CENTRIFUGE_TYPE, allRecipes);
    }

    /**
     * 动态生成蜜脾块离心配方
     * <br/>
     * 原理：蜜脾块 = 4个蜜脾合成，所以蜜脾块的离心产出为蜜脾的4倍。
     * 遍历所有蜜脾离心配方，为每个有bee_type的蜜脾生成对应的蜜脾块配方。
     * 跳过没有bee_type的配方（如原版蜜脾）和已是蜜脾块配方的条目。
     */
    private List<CentrifugeRecipe> generateCombBlockRecipes(List<RecipeHolder<CentrifugeRecipe>> honeycombRecipes) {
        List<CentrifugeRecipe> blockRecipes = new ArrayList<>();

        for (RecipeHolder<CentrifugeRecipe> holder : honeycombRecipes) {
            CentrifugeRecipe recipe = holder.value();
            ItemStack[] inputItems = recipe.ingredient.getItems();
            if (inputItems.length == 0) continue;

            // 跳过已是蜜脾块配方的条目
            if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) continue;

            // 提取bee_type，跳过没有bee_type的配方
            ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
            if (beeType == null) continue;

            // 创建蜜脾块输入
            ItemStack combBlock = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
            combBlock.set(ModDataComponents.BEE_TYPE.get(), beeType);

            // 生成按配置倍率缩放的ChancedOutput列表
            int multiplier = ModConfig.COMMON.mekCentrifugeCombBlockMultiplier.get();
            List<ChancedOutput> blockOutputs = new ArrayList<>();
            for (ChancedOutput chanced : recipe.itemOutput) {
                blockOutputs.add(new ChancedOutput(chanced.ingredient(), chanced.min() * multiplier, chanced.max() * multiplier, chanced.chance()));
            }

            // 生成按配置倍率缩放的流体输出
            SizedFluidIngredient blockFluid = new SizedFluidIngredient(
                    recipe.fluidOutput.ingredient(), recipe.fluidOutput.amount() * multiplier);

            blockRecipes.add(new CentrifugeRecipe(
                    Ingredient.of(combBlock), blockOutputs, blockFluid, recipe.getProcessingTime()));
        }

        return blockRecipes;
    }

    /**
     * 注册配方催化剂
     * <br/>
     * 将所有等级的MEK离心机方块注册为PB离心配方和SMELTING配方的催化剂。
     * 催化剂作用：在JEI中查看配方时，左侧显示所有可执行该配方的机器。
     * <p>
     * 包含：
     * - 基础MEK离心机
     * - 4个原版等级工厂（BASIC/ADVANCED/ELITE/ULTIMATE）
     * - EM扩展等级工厂（OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE，仅EM加载时）
     * - ME扩展等级工厂（ABSOLUTE/SUPREME/COSMIC/INFINITE，仅ME加载时）
     * - EME扩展等级工厂（仅EME加载时）
     * <p>
     * 每个方块同时注册为PB离心配方催化剂和SMELTING配方催化剂，
     * 实现JEI双配方跳转（点击方块可查看两种配方类型）。
     */
    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registry) {
        // 基础MEK离心机 — 同时注册PB离心配方和SMELTING配方催化剂
        registry.addRecipeCatalyst(new ItemStack(ModBlocks.MEK_CENTRIFUGE.get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);

        // 原版4等级工厂
        registry.addRecipeCatalyst(new ItemStack(ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY.get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        registry.addRecipeCatalyst(new ItemStack(ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY.get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        registry.addRecipeCatalyst(new ItemStack(ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY.get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        registry.addRecipeCatalyst(new ItemStack(ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);

        // EM扩展等级工厂（仅EM加载时，Map为空则跳过）
        for (var entry : ModBlocks.EM_FACTORIES.entrySet()) {
            registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        }

        // ME扩展等级工厂（仅ME加载时，Map为空则跳过）
        for (var entry : ModBlocks.ME_FACTORIES.entrySet()) {
            registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        }

        // EME扩展等级工厂（仅EME加载时，Map为空则跳过）
        for (var entry : ModBlocks.EME_FACTORIES.entrySet()) {
            registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), PB_CENTRIFUGE_TYPE, RecipeTypes.SMELTING);
        }
    }
}
