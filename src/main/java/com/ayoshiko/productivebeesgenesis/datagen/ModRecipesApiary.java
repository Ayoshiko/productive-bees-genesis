package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import com.jerry.mekextras.common.tags.ExtraTags;
import io.github.masyumero.emextras.common.tags.EMExtraTags;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import fr.iglee42.evolvedmekanism.registries.EMTags;
import mekanism.common.tags.MekanismTags;
import mekanism.common.tier.FactoryTier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.common.conditions.ModLoadedCondition;
import net.neoforged.neoforge.registries.DeferredBlock;

import java.util.List;

/**
 * 蜂箱配方辅助类
 * <br/>
 * 从 ModRecipes 拆分，负责所有 MEK 通用机械蜂箱及其工厂升级配方的生成：
 * <ul>
 *   <li>Mek 基础蜂箱 + 4 级工厂（Basic/Advanced/Elite/Ultimate）</li>
 *   <li>EM 5 级蜂箱工厂（Overclocked/Quantum/Dense/Multiversal/Creative）</li>
 *   <li>ME 4 级蜂箱工厂（Absolute/Supreme/Cosmic/Infinite）</li>
 *   <li>EME 4 级蜂箱工厂（AbsoluteOverclocked/SupremeQuantum/CosmicDense/InfiniteMultiversal）</li>
 * </ul>
 * 共享的 MekDataBuilder、addTierRecipe、addEMETierRecipe、rl 由 {@link ModRecipes} 提供。
 */
final class ModRecipesApiary {

	private ModRecipesApiary() {
	}

	/** 添加全部蜂箱相关配方 */
	static void addRecipes(RecipeOutput output) {
		ModLoadedCondition mekCondition = new ModLoadedCondition("mekanism");
		// 基础蜂箱
		addMekApiaryRecipe(output, mekCondition);
		// Mek 4级蜂箱工厂
		addApiaryFactoryRecipes(output, mekCondition);
		// EM/ME/EME 蜂箱工厂
		addEMApiaryFactoryRecipes(output);
		addMEApiaryFactoryRecipes(output);
		addEMEApiaryFactoryRecipes(output);
	}

	/**
	 * 添加基础MEK通用机械蜂箱配方（RBR/ICI/RBR）
	 * <br/>
	 * 使用PB的 advanced_beehives 标签作为核心材料，接受任意高级蜂箱。
	 * R=红石粉, B=基础控制电路, I=钢锭, C=PB任意高级蜂箱（标签匹配）
	 */
	private static void addMekApiaryRecipe(RecipeOutput output, ModLoadedCondition condition) {
		// 使用标签代替固定方块，允许任意 PB 高级蜂箱作为配方材料
		Ingredient advancedBeehive = Ingredient.of(
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("productivebees", "advanced_beehives")));
		new ModRecipes.MekDataBuilder(ModBlocks.MEK_APIARY, 1)
				.pattern("RBR", "ICI", "RBR")
				.key('R', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "dusts/redstone")))
				.key('B', MekanismTags.Items.CIRCUITS_BASIC)
				.key('I', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/steel")))
				.key('C', advancedBeehive)
				.addCondition(condition)
				.build(output, ModRecipes.rl("mek_apiary"));
	}

	/**
	 * 添加 Mek 4级蜂箱工厂升级配方
	 * <br/>
	 * 升级链：初始版mek_apiary → Basic → Advanced → Elite → Ultimate
	 * 使用Mekanism原版TIER_PATTERN（ACA/IPI/ACA），与离心机工厂升级材料一致。
	 */
	private static void addApiaryFactoryRecipes(RecipeOutput output, ModLoadedCondition mekCondition) {
		// BASIC蜂箱工厂：初始mek_apiary + 铁锭 + 基础合金 + 基础电路
		ModRecipes.addTierRecipe(output, "factory/basic/mek_apiary",
				ModBlocks.MEK_APIARY,
				ModBlocks.BASIC_MEK_APIARY_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/iron")),
				MekanismTags.Items.ALLOYS_BASIC,
				MekanismTags.Items.CIRCUITS_BASIC,
				mekCondition);

		// ADVANCED蜂箱工厂：BASIC蜂箱工厂 + 锇锭 + 注入合金 + 高级电路
		ModRecipes.addTierRecipe(output, "factory/advanced/mek_apiary",
				ModBlocks.BASIC_MEK_APIARY_FACTORY,
				ModBlocks.ADVANCED_MEK_APIARY_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/osmium")),
				MekanismTags.Items.ALLOYS_INFUSED,
				MekanismTags.Items.CIRCUITS_ADVANCED,
				mekCondition);

		// ELITE蜂箱工厂：ADVANCED蜂箱工厂 + 金锭 + 强化合金 + 精英电路
		ModRecipes.addTierRecipe(output, "factory/elite/mek_apiary",
				ModBlocks.ADVANCED_MEK_APIARY_FACTORY,
				ModBlocks.ELITE_MEK_APIARY_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/gold")),
				MekanismTags.Items.ALLOYS_REINFORCED,
				MekanismTags.Items.CIRCUITS_ELITE,
				mekCondition);

		// ULTIMATE蜂箱工厂：ELITE蜂箱工厂 + 钻石 + 原子合金 + 终极电路
		ModRecipes.addTierRecipe(output, "factory/ultimate/mek_apiary",
				ModBlocks.ELITE_MEK_APIARY_FACTORY,
				ModBlocks.ULTIMATE_MEK_APIARY_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems/diamond")),
				MekanismTags.Items.ALLOYS_ATOMIC,
				MekanismTags.Items.CIRCUITS_ULTIMATE,
				mekCondition);
	}

	/**
	 * 添加ME等级蜂箱工厂升级配方
	 * <br/>
	 * 升级链：ULTIMATE 蜂箱 → ABSOLUTE → SUPREME → COSMIC → INFINITE
	 * INFINITE使用特殊模式（ACA/IPJ/ACA），使用plutonium+polonium。
	 * 条件：mekanism_extras模组加载
	 */
	private static void addMEApiaryFactoryRecipes(RecipeOutput output) {
		ModLoadedCondition meCondition = new ModLoadedCondition("mekanism_extras");

		// ABSOLUTE蜂箱工厂：ULTIMATE蜂箱工厂 + emerald + radiance + absolute电路
		ModRecipes.addTierRecipe(output, "factory/absolute/extra_mek_apiary",
				ModBlocks.ULTIMATE_MEK_APIARY_FACTORY,
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems/emerald")),
				ExtraTags.Items.ALLOYS_RADIANCE,
				ExtraTags.Items.CIRCUITS_ABSOLUTE,
				meCondition);

		// SUPREME蜂箱工厂：ABSOLUTE蜂箱工厂 + netherite + thermonuclear + supreme电路
		ModRecipes.addTierRecipe(output, "factory/supreme/extra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.SUPREME),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
				ExtraTags.Items.ALLOYS_THERMONUCLEAR,
				ExtraTags.Items.CIRCUITS_SUPREME,
				meCondition);

		// COSMIC蜂箱工厂：SUPREME蜂箱工厂 + refined_obsidian + shining + cosmic电路
		ModRecipes.addTierRecipe(output, "factory/cosmic/extra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.SUPREME),
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.COSMIC),
				MekanismTags.Items.INGOTS_REFINED_OBSIDIAN,
				ExtraTags.Items.ALLOYS_SHINING,
				ExtraTags.Items.CIRCUITS_COSMIC,
				meCondition);

		// INFINITE蜂箱工厂：COSMIC蜂箱工厂 + plutonium+polonium + spectrum + infinite电路（特殊模式）
		addMEApiaryInfiniteRecipe(output, meCondition);
	}

	/**
	 * 添加ME INFINITE等级的蜂箱工厂配方
	 * <br/>
	 * 特殊模式：ACA/IPJ/ACA，使用plutonium(I)和polonium(J)替代单一锭
	 */
	private static void addMEApiaryInfiniteRecipe(RecipeOutput output, ModLoadedCondition condition) {
		var previousFactory = ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.COSMIC);
		var resultFactory = ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.INFINITE);
		if (previousFactory == null || resultFactory == null) {
			return;
		}
		new ModRecipes.MekDataBuilder(resultFactory, 1)
				.pattern("ACA", "IPJ", "ACA")
				.key('A', ExtraTags.Items.ALLOYS_SPECTRUM)
				.key('C', ExtraTags.Items.CIRCUITS_INFINITE)
				.key('I', MekanismTags.Items.PELLETS_PLUTONIUM)
				.key('J', MekanismTags.Items.PELLETS_POLONIUM)
				.key('P', previousFactory)
				.addCondition(condition)
				.build(output, ModRecipes.rl("factory/infinite/extra_mek_apiary"));
	}

	/**
	 * 添加 EM 等级蜂箱工厂升级配方
	 * <br/>
	 * 升级链：ULTIMATE 蜂箱 → OVERCLOCKED → QUANTUM → DENSE → MULTIVERSAL → CREATIVE
	 * 条件：evolvedmekanism 模组加载
	 */
	private static void addEMApiaryFactoryRecipes(RecipeOutput output) {
		ModLoadedCondition emCondition = new ModLoadedCondition("evolvedmekanism");

		// OVERCLOCKED 蜂箱工厂：ULTIMATE 蜂箱工厂 + uranium + hypercharged + overclocked 电路
		ModRecipes.addTierRecipe(output, "factory/overclocked/mek_apiary",
				ModBlocks.ULTIMATE_MEK_APIARY_FACTORY,
				getEMApiaryFactoryBlockByIndex(0),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/uranium")),
				EMTags.Items.ALLOYS_HYPERCHARGED,
				EMTags.Items.CIRCUITS_OVERCLOCKED,
				emCondition);

		// QUANTUM 蜂箱工厂：OVERCLOCKED 蜂箱工厂 + tin + subatomic + quantum 电路
		ModRecipes.addTierRecipe(output, "factory/quantum/mek_apiary",
				getEMApiaryFactoryBlockByIndex(0),
				getEMApiaryFactoryBlockByIndex(1),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/tin")),
				EMTags.Items.ALLOYS_SUBATOMIC,
				EMTags.Items.CIRCUITS_QUANTUM,
				emCondition);

		// DENSE 蜂箱工厂：QUANTUM 蜂箱工厂 + bronze + singular + dense 电路
		ModRecipes.addTierRecipe(output, "factory/dense/mek_apiary",
				getEMApiaryFactoryBlockByIndex(1),
				getEMApiaryFactoryBlockByIndex(2),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/bronze")),
				EMTags.Items.ALLOYS_SINGULAR,
				EMTags.Items.CIRCUITS_DENSE,
				emCondition);

		// MULTIVERSAL 蜂箱工厂：DENSE 蜂箱工厂 + netherite + exoversal + multiversal 电路
		ModRecipes.addTierRecipe(output, "factory/multiversal/mek_apiary",
				getEMApiaryFactoryBlockByIndex(2),
				getEMApiaryFactoryBlockByIndex(3),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
				EMTags.Items.ALLOYS_EXOVERSAL,
				EMTags.Items.CIRCUITS_MULTIVERSAL,
				emCondition);

		// CREATIVE 蜂箱工厂：MULTIVERSAL 蜂箱工厂 + nether_star + creative + creative 电路
		ModRecipes.addTierRecipe(output, "factory/creative/mek_apiary",
				getEMApiaryFactoryBlockByIndex(3),
				getEMApiaryFactoryBlockByIndex(4),
				net.minecraft.world.item.Items.NETHER_STAR,
				EMTags.Items.ALLOYS_CREATIVE,
				EMTags.Items.CIRCUITS_CREATIVE_FORGE,
				emCondition);
	}

	/**
	 * 添加EME等级蜂箱工厂升级配方
	 * <br/>
	 * 升级链：ME ABSOLUTE 蜂箱 → ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
	 * 使用EME的组合模式（ACT/PXQ/TCA），同时需要ME和EM的材料。
	 * 条件：emextras + mekanism_extras + evolvedmekanism 模组同时加载
	 */
	private static void addEMEApiaryFactoryRecipes(RecipeOutput output) {
		ICondition allModsCondition = new net.neoforged.neoforge.common.conditions.AndCondition(
				List.of(
						new ModLoadedCondition("emextras"),
						new ModLoadedCondition("mekanism_extras"),
						new ModLoadedCondition("evolvedmekanism")));

		// ABSOLUTE_OVERCLOCKED蜂箱工厂：ME ABSOLUTE蜂箱工厂 + EM OVERCLOCKED离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/absolute_overclocked/emextra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				getEMFactoryBlockByIndex(0),
				ModBlocks.EME_APIARY_FACTORIES.get(EMExtraFactoryTier.ABSOLUTE_OVERCLOCKED),
				ExtraTags.Items.ALLOYS_RADIANCE,
				EMTags.Items.ALLOYS_HYPERCHARGED,
				EMExtraTags.Items.CIRCUITS_ABSOLUTE_OVERCLOCKED,
				allModsCondition);

		// SUPREME_QUANTUM蜂箱工厂：ME SUPREME蜂箱工厂 + EM QUANTUM离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/supreme_quantum/emextra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.SUPREME),
				getEMFactoryBlockByIndex(1),
				ModBlocks.EME_APIARY_FACTORIES.get(EMExtraFactoryTier.SUPREME_QUANTUM),
				ExtraTags.Items.ALLOYS_THERMONUCLEAR,
				EMTags.Items.ALLOYS_SUBATOMIC,
				EMExtraTags.Items.CIRCUITS_SUPREME_QUANTUM,
				allModsCondition);

		// COSMIC_DENSE蜂箱工厂：ME COSMIC蜂箱工厂 + EM DENSE离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/cosmic_dense/emextra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.COSMIC),
				getEMFactoryBlockByIndex(2),
				ModBlocks.EME_APIARY_FACTORIES.get(EMExtraFactoryTier.COSMIC_DENSE),
				ExtraTags.Items.ALLOYS_SHINING,
				EMTags.Items.ALLOYS_SINGULAR,
				EMExtraTags.Items.CIRCUITS_COSMIC_DENSE,
				allModsCondition);

		// INFINITE_MULTIVERSAL蜂箱工厂：ME INFINITE蜂箱工厂 + EM MULTIVERSAL离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/infinite_multiversal/emextra_mek_apiary",
				ModBlocks.ME_APIARY_FACTORIES.get(ExtraFactoryTier.INFINITE),
				getEMFactoryBlockByIndex(3),
				ModBlocks.EME_APIARY_FACTORIES.get(EMExtraFactoryTier.INFINITE_MULTIVERSAL),
				ExtraTags.Items.ALLOYS_SPECTRUM,
				EMTags.Items.ALLOYS_EXOVERSAL,
				EMExtraTags.Items.CIRCUITS_INFINITE_MULTIVERSAL,
				allModsCondition);
	}

	/**
	 * 通过索引获取 EM 蜂箱工厂方块
	 * <br/>
	 * EM 的 FactoryTier 在编译时不存在，通过 MekCompatHooks 反射获取。
	 * 索引映射：0=OVERCLOCKED, 1=QUANTUM, 2=DENSE, 3=MULTIVERSAL, 4=CREATIVE
	 */
	private static DeferredBlock<?> getEMApiaryFactoryBlockByIndex(int index) {
		List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
		if (index < emTiers.size()) {
			return ModBlocks.getEMApiaryFactoryBlock(emTiers.get(index));
		}
		return null;
	}

	/**
	 * 通过索引获取EM工厂方块（EME蜂箱配方需要引用EM离心机工厂作为材料）
	 * <br/>
	 * 委托至离心机辅助类的同名方法，避免重复实现反射逻辑。
	 */
	private static DeferredBlock<?> getEMFactoryBlockByIndex(int index) {
		List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
		if (index < emTiers.size()) {
			return ModBlocks.getEMFactoryBlock(emTiers.get(index));
		}
		return null;
	}
}
