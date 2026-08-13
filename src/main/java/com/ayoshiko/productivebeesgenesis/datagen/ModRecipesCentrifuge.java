package com.ayoshiko.productivebeesgenesis.datagen;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.tags.ExtraTags;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import fr.iglee42.evolvedmekanism.registries.EMTags;
import io.github.masyumero.emextras.common.tags.EMExtraTags;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
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
	 * 离心机配方辅助类
	 * <br/>
	 * 从 ModRecipes 拆分，负责所有 MEK 离心机及其工厂升级配方的生成：
	 * <ul>
	 *   <li>Mek 基础离心机 + 4 级工厂（Basic/Advanced/Elite/Ultimate）</li>
	 *   <li>EM 5 级离心机工厂（Overclocked/Quantum/Dense/Multiversal/Creative）</li>
	 *   <li>ME 4 级离心机工厂（Absolute/Supreme/Cosmic/Infinite）</li>
	 *   <li>EME 4 级离心机工厂（AbsoluteOverclocked/SupremeQuantum/CosmicDense/InfiniteMultiversal）</li>
	 * </ul>
	 * 共享的 MekDataBuilder、addTierRecipe、addEMETierRecipe、rl 由 {@link ModRecipes} 提供。
	 */
final class ModRecipesCentrifuge {

	private ModRecipesCentrifuge() {
	}

	/** 添加全部离心机相关配方 */
	static void addRecipes(RecipeOutput output) {
		ModLoadedCondition mekCondition = new ModLoadedCondition("mekanism");
		// 基础离心机
		addMekCentrifugeRecipe(output, mekCondition);
		// Mek 4级离心机工厂
		addMekCentrifugeFactoryRecipes(output, mekCondition);
		// EM/ME/EME 离心机工厂 — 运行时守卫：未安装对应可选模组时跳过，
		// 避免 runData 环境缺失可选模组类导致 NoClassDefFoundError（与运行时注册逻辑一致）
		if (MekCompatHooks.isEvolvedMekanismLoaded()) {
			addEMFactoryRecipes(output);
		}
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			addMEFactoryRecipes(output);
		}
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			addEMEFactoryRecipes(output);
		}
	}

	/**
	 * 添加 Mek 4级离心机工厂升级配方
	 * <br/>
	 * 升级链：BASIC→ADVANCED→ELITE→ULTIMATE，使用 TIER_PATTERN（ACA/IPI/ACA）
	 */
	private static void addMekCentrifugeFactoryRecipes(RecipeOutput output, ModLoadedCondition mekCondition) {
		// BASIC工厂：基础离心机 + 铁锭 + 基础合金 + 基础电路
		ModRecipes.addTierRecipe(output, "factory/basic/mek_centrifuge",
				ModBlocks.MEK_CENTRIFUGE,
				ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/iron")),
				MekanismTags.Items.ALLOYS_BASIC,
				MekanismTags.Items.CIRCUITS_BASIC,
				mekCondition);

		// ADVANCED工厂：BASIC工厂 + 锇锭 + 注入合金 + 高级电路
		ModRecipes.addTierRecipe(output, "factory/advanced/mek_centrifuge",
				ModBlocks.BASIC_MEK_CENTRIFUGE_FACTORY,
				ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/osmium")),
				MekanismTags.Items.ALLOYS_INFUSED,
				MekanismTags.Items.CIRCUITS_ADVANCED,
				mekCondition);

		// ELITE工厂：ADVANCED工厂 + 金锭 + 强化合金 + 精英电路
		ModRecipes.addTierRecipe(output, "factory/elite/mek_centrifuge",
				ModBlocks.ADVANCED_MEK_CENTRIFUGE_FACTORY,
				ModBlocks.ELITE_MEK_CENTRIFUGE_FACTORY,
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/gold")),
				MekanismTags.Items.ALLOYS_REINFORCED,
				MekanismTags.Items.CIRCUITS_ELITE,
				mekCondition);

		// ULTIMATE工厂：ELITE工厂 + 钻石 + 原子合金 + 终极电路
		ModRecipes.addTierRecipe(output, "factory/ultimate/mek_centrifuge",
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
	 */
	private static void addMekCentrifugeRecipe(RecipeOutput output, ModLoadedCondition condition) {
		Ingredient poweredCentrifuge = Ingredient.of(
				BuiltInRegistries.BLOCK
						.get(ResourceLocation.fromNamespaceAndPath("productivebees", "powered_centrifuge")));
		new ModRecipes.MekDataBuilder(ModBlocks.MEK_CENTRIFUGE, 1)
				.pattern("RBR", "ICI", "RBR")
				.key('R', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "dusts/redstone")))
				.key('B', MekanismTags.Items.CIRCUITS_BASIC)
				.key('I', ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/steel")))
				.key('C', poweredCentrifuge)
				.addCondition(condition)
				.build(output, ModRecipes.rl("mek_centrifuge"));
	}

	/**
	 * 添加EM等级离心机工厂配方
	 * <br/>
	 * 升级链：ULTIMATE → OVERCLOCKED → QUANTUM → DENSE → MULTIVERSAL → CREATIVE
	 * 条件：evolvedmekanism模组加载
	 */
	private static void addEMFactoryRecipes(RecipeOutput output) {
		ModLoadedCondition emCondition = new ModLoadedCondition("evolvedmekanism");

		// OVERCLOCKED: ULTIMATE离心机工厂 + uranium + hypercharged + overclocked电路
		ModRecipes.addTierRecipe(output, "factory/overclocked/mek_centrifuge",
				ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
				getEMFactoryBlockByIndex(0),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/uranium")),
				EMTags.Items.ALLOYS_HYPERCHARGED,
				EMTags.Items.CIRCUITS_OVERCLOCKED,
				emCondition);

		// QUANTUM: OVERCLOCKED离心机工厂 + tin + subatomic + quantum电路
		ModRecipes.addTierRecipe(output, "factory/quantum/mek_centrifuge",
				getEMFactoryBlockByIndex(0),
				getEMFactoryBlockByIndex(1),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/tin")),
				EMTags.Items.ALLOYS_SUBATOMIC,
				EMTags.Items.CIRCUITS_QUANTUM,
				emCondition);

		// DENSE: QUANTUM离心机工厂 + bronze + singular + dense电路
		ModRecipes.addTierRecipe(output, "factory/dense/mek_centrifuge",
				getEMFactoryBlockByIndex(1),
				getEMFactoryBlockByIndex(2),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/bronze")),
				EMTags.Items.ALLOYS_SINGULAR,
				EMTags.Items.CIRCUITS_DENSE,
				emCondition);

		// MULTIVERSAL: DENSE离心机工厂 + netherite + exoversal + multiversal电路
		ModRecipes.addTierRecipe(output, "factory/multiversal/mek_centrifuge",
				getEMFactoryBlockByIndex(2),
				getEMFactoryBlockByIndex(3),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
				EMTags.Items.ALLOYS_EXOVERSAL,
				EMTags.Items.CIRCUITS_MULTIVERSAL,
				emCondition);

		// CREATIVE: MULTIVERSAL离心机工厂 + nether_star + creative + creative电路
		ModRecipes.addTierRecipe(output, "factory/creative/mek_centrifuge",
				getEMFactoryBlockByIndex(3),
				getEMFactoryBlockByIndex(4),
				net.minecraft.world.item.Items.NETHER_STAR,
				EMTags.Items.ALLOYS_CREATIVE,
				EMTags.Items.CIRCUITS_CREATIVE_FORGE,
				emCondition);
	}

	/**
	 * 添加ME等级离心机工厂配方
	 * <br/>
	 * 升级链：ULTIMATE → ABSOLUTE → SUPREME → COSMIC → INFINITE
	 * INFINITE使用特殊模式（ACA/IPJ/ACA），使用plutonium+polonium。
	 * 条件：mekanism_extras模组加载
	 */
	private static void addMEFactoryRecipes(RecipeOutput output) {
		ModLoadedCondition meCondition = new ModLoadedCondition("mekanism_extras");

		// ABSOLUTE: ULTIMATE离心机工厂 + emerald + radiance + absolute电路
		ModRecipes.addTierRecipe(output, "factory/absolute/extra_mek_centrifuge",
				ModBlocks.ULTIMATE_MEK_CENTRIFUGE_FACTORY,
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "gems/emerald")),
				ExtraTags.Items.ALLOYS_RADIANCE,
				ExtraTags.Items.CIRCUITS_ABSOLUTE,
				meCondition);

		// SUPREME: ABSOLUTE离心机工厂 + netherite + thermonuclear + supreme电路
		ModRecipes.addTierRecipe(output, "factory/supreme/extra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
				ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "ingots/netherite")),
				ExtraTags.Items.ALLOYS_THERMONUCLEAR,
				ExtraTags.Items.CIRCUITS_SUPREME,
				meCondition);

		// COSMIC: SUPREME离心机工厂 + refined_obsidian + shining + cosmic电路
		ModRecipes.addTierRecipe(output, "factory/cosmic/extra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC),
				MekanismTags.Items.INGOTS_REFINED_OBSIDIAN,
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
	private static void addMEInfiniteRecipe(RecipeOutput output, ModLoadedCondition condition) {
		var previousFactory = ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC);
		var resultFactory = ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.INFINITE);
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
				.build(output, ModRecipes.rl("factory/infinite/extra_mek_centrifuge"));
	}

	/**
	 * 添加EME等级离心机工厂配方
	 * <br/>
	 * 升级链：ME ABSOLUTE → ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
	 * 使用EME的组合模式（ACT/PXQ/TCA），同时需要ME和EM的材料。
	 * 条件：emextras + mekanism_extras + evolvedmekanism 模组同时加载
	 */
	private static void addEMEFactoryRecipes(RecipeOutput output) {
		ICondition allModsCondition = new net.neoforged.neoforge.common.conditions.AndCondition(
				List.of(
						new ModLoadedCondition("emextras"),
						new ModLoadedCondition("mekanism_extras"),
						new ModLoadedCondition("evolvedmekanism")));

		// ABSOLUTE_OVERCLOCKED: ME ABSOLUTE离心机工厂 + EM OVERCLOCKED离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/absolute_overclocked/emextra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.ABSOLUTE),
				getEMFactoryBlockByIndex(0),
				ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.ABSOLUTE_OVERCLOCKED),
				ExtraTags.Items.ALLOYS_RADIANCE,
				EMTags.Items.ALLOYS_HYPERCHARGED,
				EMExtraTags.Items.CIRCUITS_ABSOLUTE_OVERCLOCKED,
				allModsCondition);

		// SUPREME_QUANTUM: ME SUPREME离心机工厂 + EM QUANTUM离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/supreme_quantum/emextra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.SUPREME),
				getEMFactoryBlockByIndex(1),
				ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.SUPREME_QUANTUM),
				ExtraTags.Items.ALLOYS_THERMONUCLEAR,
				EMTags.Items.ALLOYS_SUBATOMIC,
				EMExtraTags.Items.CIRCUITS_SUPREME_QUANTUM,
				allModsCondition);

		// COSMIC_DENSE: ME COSMIC离心机工厂 + EM DENSE离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/cosmic_dense/emextra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.COSMIC),
				getEMFactoryBlockByIndex(2),
				ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.COSMIC_DENSE),
				ExtraTags.Items.ALLOYS_SHINING,
				EMTags.Items.ALLOYS_SINGULAR,
				EMExtraTags.Items.CIRCUITS_COSMIC_DENSE,
				allModsCondition);

		// INFINITE_MULTIVERSAL: ME INFINITE离心机工厂 + EM MULTIVERSAL离心机工厂
		ModRecipes.addEMETierRecipe(output, "factory/infinite_multiversal/emextra_mek_centrifuge",
				ModBlocks.ME_FACTORIES.get(ExtraFactoryTier.INFINITE),
				getEMFactoryBlockByIndex(3),
				ModBlocks.EME_FACTORIES.get(EMExtraFactoryTier.INFINITE_MULTIVERSAL),
				ExtraTags.Items.ALLOYS_SPECTRUM,
				EMTags.Items.ALLOYS_EXOVERSAL,
				EMExtraTags.Items.CIRCUITS_INFINITE_MULTIVERSAL,
				allModsCondition);
	}

	/**
	 * 通过索引获取EM工厂方块
	 * <br/>
	 * EM的FactoryTier在编译时不存在，通过MekCompatHooks反射获取。
	 * 索引映射：0=OVERCLOCKED, 1=QUANTUM, 2=DENSE, 3=MULTIVERSAL, 4=CREATIVE
	 */
	private static DeferredBlock<?> getEMFactoryBlockByIndex(int index) {
		List<FactoryTier> emTiers = MekCompatHooks.getEMFactoryTiers();
		if (index < emTiers.size()) {
			return ModBlocks.getEMFactoryBlock(emTiers.get(index));
		}
		return null;
	}
}
