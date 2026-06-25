package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import com.jerry.mekextras.common.tags.ExtraTags;
import io.github.masyumero.emextras.common.tags.EMExtraTags;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import fr.iglee42.evolvedmekanism.registries.EMTags;
import mekanism.common.tags.MekanismTags;
import mekanism.common.recipe.upgrade.MekanismShapedRecipe;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tier.FactoryTier;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementRequirements;
import net.minecraft.advancements.AdvancementRewards;
import net.minecraft.advancements.Criterion;
import net.minecraft.advancements.critereon.RecipeUnlockedTrigger;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.ItemLike;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 配方数据生成器
 * <br/>
 * 为MEK离心机工厂方块生成合成配方，使用Mekanism的mekanism:mek_data类型配方
 * （合成时保留机器数据，如能量、物品等）。
 * <p>
 * 配方模式遵循各模组原版：
 * - Mekanism基础：基础离心机(RBR/ICI/RBR)，4级工厂TIER_PATTERN（ACA/IPI/ACA）
 * - EM 5等级：TIER_PATTERN，使用EM的合金/电路/锭标签
 * - ME 4等级：TIER_PATTERN，使用ME的合金/电路/锭标签（INFINITE特殊模式）
 * - EME 4等级：EMEXTRA_PATTERN（ACT/PXQ/TCA），组合ME+EM材料
 * <p>
 * 所有配方使用ModLoadedCondition条件，仅在对应模组加载时生成。
 */
public class ModRecipes extends RecipeProvider {

    public ModRecipes(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput output) {
        addMekBaseRecipes(output);
        addEMFactoryRecipes(output);
        addMEFactoryRecipes(output);
        addEMEFactoryRecipes(output);
    }

    // ======================== MekData配方构建器 ========================

    /**
     * 构建mekanism:mek_data类型的有序配方
     * <br/>
     * MekanismShapedRecipe是ShapedRecipe的包装器，在合成时保留机器数据（能量、物品等）。
     * 由于Mekanism的MekDataShapedRecipeBuilder在datagen模块中（不在主jar中），
     * 这里手动构建ShapedRecipe并包装为MekanismShapedRecipe。
     */
    private static class MekDataBuilder {
        private final ItemStack result;
        private final List<String> pattern = new ArrayList<>();
        private final Map<Character, Ingredient> key = new LinkedHashMap<>();
        private final Map<String, Criterion<?>> criteria = new LinkedHashMap<>();
        private final List<ICondition> conditions = new ArrayList<>();

        MekDataBuilder(ItemLike result, int count) {
            this.result = new ItemStack(result, count);
        }

        MekDataBuilder pattern(String... rows) {
            this.pattern.clear();
            for (String row : rows) {
                this.pattern.add(row);
            }
            return this;
        }

        MekDataBuilder key(char symbol, TagKey<Item> tag) {
            key.put(symbol, Ingredient.of(tag));
            return this;
        }

        MekDataBuilder key(char symbol, ItemLike item) {
            key.put(symbol, Ingredient.of(item));
            return this;
        }

        MekDataBuilder key(char symbol, Ingredient ingredient) {
            key.put(symbol, ingredient);
            return this;
        }

        MekDataBuilder addCondition(ICondition condition) {
            conditions.add(condition);
            return this;
        }

        void build(RecipeOutput output, ResourceLocation id) {
            ShapedRecipe shapedRecipe = new ShapedRecipe(
                    "", CraftingBookCategory.EQUIPMENT,
                    ShapedRecipePattern.of(key, pattern),
                    result, true);
            // 包装为MekanismShapedRecipe，使用mekanism:mek_data序列化器
            Recipe<?> mekRecipe = new MekanismShapedRecipe(shapedRecipe);

            net.minecraft.advancements.AdvancementHolder advancementHolder = null;
            if (!criteria.isEmpty()) {
                Advancement.Builder builder = output.advancement()
                        .addCriterion("has_the_recipe", RecipeUnlockedTrigger.unlocked(id))
                        .rewards(AdvancementRewards.Builder.recipe(id))
                        .requirements(AdvancementRequirements.Strategy.OR);
                criteria.forEach(builder::addCriterion);
                advancementHolder = builder.build(id.withPrefix("recipes/"));
            }
            output.accept(id, mekRecipe, advancementHolder, conditions.toArray(new ICondition[0]));
        }
    }

    // ======================== Mek基础配方 ========================

    /**
     * 添加Mekanism基础离心机和4级工厂配方
     * <br/>
     * 基础离心机：RBR/ICI/RBR，使用红石粉+基础电路+钢锭+PB动力离心机
     * 工厂升级链：BASIC→ADVANCED→ELITE→ULTIMATE，使用TIER_PATTERN（ACA/IPI/ACA）
     * 条件：mekanism模组加载
     */
    private void addMekBaseRecipes(RecipeOutput output) {
        ModLoadedCondition mekCondition = new ModLoadedCondition("mekanism");

        // 基础离心机：PB动力离心机 + 红石粉 + 基础电路 + 钢锭
        addMekCentrifugeRecipe(output, mekCondition);

        // BASIC工厂：基础离心机 + 铁锭 + 基础合金 + 基础电路
        addTierRecipe(output, "factory/basic/mek_centrifuge",
                ModBlocks.MEK_CENTRIFUGE,
                ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY,
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/iron")),
                MekanismTags.Items.ALLOYS_BASIC,
                MekanismTags.Items.CIRCUITS_BASIC,
                mekCondition);

        // ADVANCED工厂：BASIC工厂 + 锇锭 + 注入合金 + 高级电路
        addTierRecipe(output, "factory/advanced/mek_centrifuge",
                ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY,
                ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY,
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/osmium")),
                MekanismTags.Items.ALLOYS_INFUSED,
                MekanismTags.Items.CIRCUITS_ADVANCED,
                mekCondition);

        // ELITE工厂：ADVANCED工厂 + 金锭 + 强化合金 + 精英电路
        addTierRecipe(output, "factory/elite/mek_centrifuge",
                ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY,
                ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY,
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/gold")),
                MekanismTags.Items.ALLOYS_REINFORCED,
                MekanismTags.Items.CIRCUITS_ELITE,
                mekCondition);

        // ULTIMATE工厂：ELITE工厂 + 钻石 + 原子合金 + 终极电路
        addTierRecipe(output, "factory/ultimate/mek_centrifuge",
                ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY,
                ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems/diamond")),
                MekanismTags.Items.ALLOYS_ATOMIC,
                MekanismTags.Items.CIRCUITS_ULTIMATE,
                mekCondition);
    }

    /**
     * 添加基础MEK离心机配方（RBR/ICI/RBR）
     * <br/>
     * R=红石粉, B=基础控制电路, I=钢锭, C=PB动力离心机
     * 使用Ingredient引用PB的powered_centrifuge方块
     */
    private void addMekCentrifugeRecipe(RecipeOutput output, ModLoadedCondition condition) {
        // 通过ResourceLocation引用PB的powered_centrifuge方块
        Ingredient poweredCentrifuge = Ingredient.of(
                net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .get(ResourceLocation.fromNamespaceAndPath("productivebees", "powered_centrifuge")));
        new MekDataBuilder(ModBlocks.MEK_CENTRIFUGE, 1)
                .pattern("RBR", "ICI", "RBR")
                .key('R', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "dusts/redstone")))
                .key('B', MekanismTags.Items.CIRCUITS_BASIC)
                .key('I', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/steel")))
                .key('C', poweredCentrifuge)
                .addCondition(condition)
                .build(output, rl("mek_centrifuge"));
    }

    // ======================== EM 配方 ========================

    /**
     * 添加EM等级离心机工厂配方
     * <br/>
     * 升级链：ULTIMATE → OVERCLOCKED → QUANTUM → DENSE → MULTIVERSAL → CREATIVE
     * 使用Mekanism的TIER_PATTERN（ACA/IPI/ACA），材料映射参考EM原版配方。
     * 条件：evolvedmekanism模组加载
     */
    private void addEMFactoryRecipes(RecipeOutput output) {
        ModLoadedCondition emCondition = new ModLoadedCondition("evolvedmekanism");

        // OVERCLOCKED: ULTIMATE离心机工厂 + uranium + hypercharged + overclocked电路
        addTierRecipe(output, "factory/overclocked/mek_centrifuge",
                ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
                getEMFactoryBlockByIndex(0),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/uranium")),
                EMTags.Items.ALLOYS_HYPERCHARGED,
                EMTags.Items.CIRCUITS_OVERCLOCKED,
                emCondition);

        // QUANTUM: OVERCLOCKED离心机工厂 + tin + subatomic + quantum电路
        addTierRecipe(output, "factory/quantum/mek_centrifuge",
                getEMFactoryBlockByIndex(0),
                getEMFactoryBlockByIndex(1),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/tin")),
                EMTags.Items.ALLOYS_SUBATOMIC,
                EMTags.Items.CIRCUITS_QUANTUM,
                emCondition);

        // DENSE: QUANTUM离心机工厂 + bronze + singular + dense电路
        addTierRecipe(output, "factory/dense/mek_centrifuge",
                getEMFactoryBlockByIndex(1),
                getEMFactoryBlockByIndex(2),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/bronze")),
                EMTags.Items.ALLOYS_SINGULAR,
                EMTags.Items.CIRCUITS_DENSE,
                emCondition);

        // MULTIVERSAL: DENSE离心机工厂 + netherite + exoversal + multiversal电路
        addTierRecipe(output, "factory/multiversal/mek_centrifuge",
                getEMFactoryBlockByIndex(2),
                getEMFactoryBlockByIndex(3),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
                EMTags.Items.ALLOYS_EXOVERSAL,
                EMTags.Items.CIRCUITS_MULTIVERSAL,
                emCondition);

        // CREATIVE: MULTIVERSAL离心机工厂 + nether_star + creative + creative电路
        addTierRecipe(output, "factory/creative/mek_centrifuge",
                getEMFactoryBlockByIndex(3),
                getEMFactoryBlockByIndex(4),
                net.minecraft.world.item.Items.NETHER_STAR,
                EMTags.Items.ALLOYS_CREATIVE,
                EMTags.Items.CIRCUITS_CREATIVE_FORGE,
                emCondition);
    }

    // ======================== ME 配方 ========================

    /**
     * 添加ME等级离心机工厂配方
     * <br/>
     * 升级链：ULTIMATE → ABSOLUTE → SUPREME → COSMIC → INFINITE
     * 使用Mekanism的TIER_PATTERN（ACA/IPI/ACA），材料映射参考ME原版配方。
     * INFINITE使用特殊模式（ACA/IPJ/ACA），使用plutonium+polonium。
     * 条件：mekanism_extras模组加载
     */
    private void addMEFactoryRecipes(RecipeOutput output) {
        ModLoadedCondition meCondition = new ModLoadedCondition("mekanism_extras");

        // ABSOLUTE: ULTIMATE离心机工厂 + emerald + radiance + absolute电路
        addTierRecipe(output, "factory/absolute/extra_mek_centrifuge",
                ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems/emerald")),
                ExtraTags.Items.ALLOYS_RADIANCE,
                ExtraTags.Items.CIRCUITS_ABSOLUTE,
                meCondition);

        // SUPREME: ABSOLUTE离心机工厂 + netherite + thermonuclear + supreme电路
        addTierRecipe(output, "factory/supreme/extra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
                ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
                ExtraTags.Items.ALLOYS_THERMONUCLEAR,
                ExtraTags.Items.CIRCUITS_SUPREME,
                meCondition);

        // COSMIC: SUPREME离心机工厂 + refined_obsidian + shining + cosmic电路
        addTierRecipe(output, "factory/cosmic/extra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC),
                mekanism.common.tags.MekanismTags.Items.INGOTS_REFINED_OBSIDIAN,
                ExtraTags.Items.ALLOYS_SHINING,
                ExtraTags.Items.CIRCUITS_COSMIC,
                meCondition);

        // INFINITE: COSMIC离心机工厂 + plutonium+polonium + spectrum + infinite电路（特殊模式）
        addMEInfiniteRecipe(output, meCondition);
    }

    /**
     * 添加ME INFINITE等级的离心机工厂配方
     * <br/>
     * 特殊模式：ACA/IPJ/ACA，使用plutonium(I)和polonium(J)替代单一锭
     */
    private void addMEInfiniteRecipe(RecipeOutput output, ModLoadedCondition condition) {
        var previousFactory = ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC);
        var resultFactory = ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.INFINITE);
        if (previousFactory == null || resultFactory == null) {
            return;
        }
        new MekDataBuilder(resultFactory, 1)
                .pattern("ACA", "IPJ", "ACA")
                .key('A', ExtraTags.Items.ALLOYS_SPECTRUM)
                .key('C', ExtraTags.Items.CIRCUITS_INFINITE)
                .key('I', mekanism.common.tags.MekanismTags.Items.PELLETS_PLUTONIUM)
                .key('J', mekanism.common.tags.MekanismTags.Items.PELLETS_POLONIUM)
                .key('P', previousFactory)
                .addCondition(condition)
                .build(output, rl("factory/infinite/extra_mek_centrifuge"));
    }

    // ======================== EME 配方 ========================

    /**
     * 添加EME等级离心机工厂配方
     * <br/>
     * 升级链：ME ABSOLUTE → ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
     * 使用EME的组合模式（ACT/PXQ/TCA），同时需要ME和EM的材料。
     * A=ME合金, C=EME电路, T=EM合金, P=ME上一级工厂, Q=EM上一级工厂, X=Steel Casing
     * 条件：emextras + mekanism_extras + evolvedmekanism 模组同时加载
     */
    private void addEMEFactoryRecipes(RecipeOutput output) {
        // EME需要ME和EM同时加载
        ICondition allModsCondition = new net.neoforged.neoforge.common.conditions.AndCondition(
                List.of(
                        new ModLoadedCondition("emextras"),
                        new ModLoadedCondition("mekanism_extras"),
                        new ModLoadedCondition("evolvedmekanism")));

        // ABSOLUTE_OVERCLOCKED: ME ABSOLUTE离心机工厂 + EM OVERCLOCKED离心机工厂
        addEMETierRecipe(output, "factory/absolute_overclocked/emextra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
                getEMFactoryBlockByIndex(0),
                ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.ABSOLUTE_OVERCLOCKED),
                ExtraTags.Items.ALLOYS_RADIANCE,
                EMTags.Items.ALLOYS_HYPERCHARGED,
                EMExtraTags.Items.CIRCUITS_ABSOLUTE_OVERCLOCKED,
                allModsCondition);

        // SUPREME_QUANTUM: ME SUPREME离心机工厂 + EM QUANTUM离心机工厂
        addEMETierRecipe(output, "factory/supreme_quantum/emextra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
                getEMFactoryBlockByIndex(1),
                ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.SUPREME_QUANTUM),
                ExtraTags.Items.ALLOYS_THERMONUCLEAR,
                EMTags.Items.ALLOYS_SUBATOMIC,
                EMExtraTags.Items.CIRCUITS_SUPREME_QUANTUM,
                allModsCondition);

        // COSMIC_DENSE: ME COSMIC离心机工厂 + EM DENSE离心机工厂
        addEMETierRecipe(output, "factory/cosmic_dense/emextra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC),
                getEMFactoryBlockByIndex(2),
                ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.COSMIC_DENSE),
                ExtraTags.Items.ALLOYS_SHINING,
                EMTags.Items.ALLOYS_SINGULAR,
                EMExtraTags.Items.CIRCUITS_COSMIC_DENSE,
                allModsCondition);

        // INFINITE_MULTIVERSAL: ME INFINITE离心机工厂 + EM MULTIVERSAL离心机工厂
        addEMETierRecipe(output, "factory/infinite_multiversal/emextra_mek_centrifuge",
                ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.INFINITE),
                getEMFactoryBlockByIndex(3),
                ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.INFINITE_MULTIVERSAL),
                ExtraTags.Items.ALLOYS_SPECTRUM,
                EMTags.Items.ALLOYS_EXOVERSAL,
                EMExtraTags.Items.CIRCUITS_INFINITE_MULTIVERSAL,
                allModsCondition);
    }

    // ======================== 通用配方方法 ========================

    /**
     * 添加标准TIER_PATTERN配方（ACA/IPI/ACA）
     * <br/>
     * A=合金, C=电路, I=锭/材料, P=上一级工厂
     * 适用于EM和ME等级
     */
    private void addTierRecipe(RecipeOutput output, String path,
                               net.neoforged.neoforge.registries.DeferredBlock<?> previousFactory,
                               net.neoforged.neoforge.registries.DeferredBlock<?> resultFactory,
                               TagKey<Item> ingotTag,
                               TagKey<Item> alloyTag, TagKey<Item> circuitTag,
                               ICondition condition) {
        if (previousFactory == null || resultFactory == null) {
            return;
        }
        new MekDataBuilder(resultFactory, 1)
                .pattern("ACA", "IPI", "ACA")
                .key('A', alloyTag)
                .key('C', circuitTag)
                .key('I', ingotTag)
                .key('P', previousFactory)
                .addCondition(condition)
                .build(output, rl(path));
    }

    /**
     * 添加标准TIER_PATTERN配方（ItemLike版本，用于nether_star等非Tag材料）
     */
    private void addTierRecipe(RecipeOutput output, String path,
                               net.neoforged.neoforge.registries.DeferredBlock<?> previousFactory,
                               net.neoforged.neoforge.registries.DeferredBlock<?> resultFactory,
                               ItemLike ingotItem,
                               TagKey<Item> alloyTag, TagKey<Item> circuitTag,
                               ICondition condition) {
        if (previousFactory == null || resultFactory == null) {
            return;
        }
        new MekDataBuilder(resultFactory, 1)
                .pattern("ACA", "IPI", "ACA")
                .key('A', alloyTag)
                .key('C', circuitTag)
                .key('I', ingotItem)
                .key('P', previousFactory)
                .addCondition(condition)
                .build(output, rl(path));
    }

    /**
     * 添加EME组合配方（ACT/PXQ/TCA）
     * <br/>
     * A=ME合金, C=EME电路, T=EM合金, P=ME上一级工厂, Q=EM上一级工厂, X=Steel Casing
     */
    private void addEMETierRecipe(RecipeOutput output, String path,
                                   net.neoforged.neoforge.registries.DeferredBlock<?> mePreviousFactory,
                                   net.neoforged.neoforge.registries.DeferredBlock<?> emPreviousFactory,
                                   net.neoforged.neoforge.registries.DeferredBlock<?> resultFactory,
                                   TagKey<Item> meAlloyTag, TagKey<Item> emAlloyTag, TagKey<Item> emeCircuitTag,
                                   ICondition condition) {
        if (mePreviousFactory == null || emPreviousFactory == null || resultFactory == null) {
            return;
        }
        new MekDataBuilder(resultFactory, 1)
                .pattern("ACT", "PXQ", "TCA")
                .key('A', meAlloyTag)
                .key('C', emeCircuitTag)
                .key('T', emAlloyTag)
                .key('P', mePreviousFactory)
                .key('Q', emPreviousFactory)
                .key('X', MekanismBlocks.STEEL_CASING)
                .addCondition(condition)
                .build(output, rl(path));
    }

    /**
     * 通过索引获取EM工厂方块
     * <br/>
     * EM的FactoryTier在编译时不存在，通过MekCompatHooks反射获取。
     * 索引映射：0=OVERCLOCKED, 1=QUANTUM, 2=DENSE, 3=MULTIVERSAL, 4=CREATIVE
     */
    private net.neoforged.neoforge.registries.DeferredBlock<?> getEMFactoryBlockByIndex(int index) {
        List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
        if (index < emTiers.size()) {
            return ModBlocks.getEMFactoryBlock(emTiers.get(index));
        }
        return null;
    }

    /** 创建模组命名空间的ResourceLocation */
    private static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, path);
    }
}
