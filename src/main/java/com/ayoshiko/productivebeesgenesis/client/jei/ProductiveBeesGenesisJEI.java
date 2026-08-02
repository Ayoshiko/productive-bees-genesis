package com.ayoshiko.productivebeesgenesis.client.jei;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
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
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	 * 蜜脾块离心配方缓存
	 * <p>
	 * registerRecipes 在 JEI 初始化和配方重载时调用，蜜脾块配方由蜜脾配方派生，
	 * 缓存避免在重载时重复遍历所有蜜脾配方并创建大量 CentrifugeRecipe 对象。
	 * 缓存键为 {@link ProductiveBeesGenesis#RECIPE_VERSION}，每次标签/配方重载时递增，
	 * 自动触发缓存失效。使用 volatile 保证跨线程可见性。
	 */
	private static volatile List<CentrifugeRecipe> cachedCombBlockRecipes = null;
	private static volatile long cachedRecipeVersion = -1L;

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
	 * 模块 1：同时为特殊蜜脾块（ghostly/milky/powdery/vanilla）生成派生配方。
	 * <p>
	 * 蜜脾块配方生成结果会被缓存，缓存键为 {@link ProductiveBeesGenesis#RECIPE_VERSION}，
	 * 配方重载时版本号递增自动失效，避免重复遍历蜜脾配方并创建大量派生对象。
	 */
	@Override
	public void registerRecipes(IRecipeRegistration registry) {
		if (Minecraft.getInstance().level == null) {
			return;
		}
		// 获取PB原版的所有离心配方
		List<RecipeHolder<CentrifugeRecipe>> centrifugeRecipes =
				Minecraft.getInstance().level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.CENTRIFUGE_TYPE.get());

		// 检查缓存：配方版本号未变化时复用已生成的蜜脾块配方
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		List<CentrifugeRecipe> blockRecipes;
		if (cachedCombBlockRecipes != null && cachedRecipeVersion == currentVersion) {
			blockRecipes = cachedCombBlockRecipes;
		} else {
			blockRecipes = generateCombBlockRecipes(centrifugeRecipes);
			// 模块 1：合并特殊蜜脾块配方（ghostly/milky/powdery/vanilla）
			blockRecipes.addAll(generateSpecialCombBlockRecipes());
			cachedCombBlockRecipes = blockRecipes;
			cachedRecipeVersion = currentVersion;
		}

		// 收集所有配方（蜜脾 + 动态生成的蜜脾块）
		List<CentrifugeRecipe> allRecipes = new ArrayList<>(
				centrifugeRecipes.stream().map(RecipeHolder::value).toList());
		allRecipes.addAll(blockRecipes);

		registry.addRecipes(PB_CENTRIFUGE_TYPE, allRecipes);
	}

	/**
	 * 失效蜜脾块配方缓存
	 * <p>
	 * 通常由 {@link ProductiveBeesGenesis#onTagsReload} 在 TagsUpdatedEvent 中间接触发
	 * （RECIPE_VERSION 递增后，下次 registerRecipes 自动重建）。
	 * 此方法提供手动失效入口，供特殊场景使用。
	 */
	public static void invalidateCache() {
		cachedCombBlockRecipes = null;
		cachedRecipeVersion = -1L;
	}

	/**
	 * Wax 物品标签 — 复刻 PB 热力离心机 stripWax 行为
	 * <br/>
	 * PB 原版 ModTags.Common.WAXES = c:waxes，蜜脾块离心时过滤此标签的产出。
	 * 蜜脾离心保留 Wax（符合 PB 原版），仅蜜脾块配方派生时过滤。
	 */
	private static final TagKey<Item> WAXES_TAG = TagKey.create(
			Registries.ITEM, ResourceLocation.fromNamespaceAndPath("c", "waxes"));

	/**
	 * 动态生成蜜脾块离心配方
	 * <br/>
	 * 原理：蜜脾块 = 4个蜜脾合成，所以蜜脾块的离心产出为蜜脾的4倍。
	 * 遍历所有蜜脾离心配方，为每个有bee_type的蜜脾生成对应的蜜脾块配方。
	 * 跳过没有bee_type的配方（如原版蜜脾）和已是蜜脾块配方的条目。
	 * <p>
	 * 模块 3 修复：复刻 PB 热力离心机 stripWax 行为，蜜脾块配方过滤 c:waxes 标签的产出。
	 * PB 原版 HeatedCentrifugeBlockEntity 处理蜜脾块时 stripWax=true，蜜脾块离心不产出 Wax。
	 * 蜜脾配方保留 Wax 不变（蜜脾离心产出 Wax 是正常的）。
	 * <p>
	 * 模块 6 修复（v2.4）：PB 原版有独立的蜜脾块配方（如 comb_blazing.json 产出 blaze_rod），
	 * 与单蜜脾配方（honeycomb_blazing.json 产出 blaze_powder）产物不同。
	 * 原生蜜脾块配方已在 centrifugeRecipes 中注册到 JEI，此处不能再为同一 bee_type 派生配方，
	 * 否则 JEI 显示双配方且实际离心走派生路径产出错误（烈焰粉而非烈焰棒）。
	 * 修复：第一遍扫描收集有原生蜜脾块配方的 bee_type 集合，第二遍派生时跳过这些 bee_type。
	 */
	private List<CentrifugeRecipe> generateCombBlockRecipes(List<RecipeHolder<CentrifugeRecipe>> honeycombRecipes) {
		List<CentrifugeRecipe> blockRecipes = new ArrayList<>();

		// 模块 6 修复：第一遍扫描 — 收集有 PB 原生蜜脾块配方的 bee_type
		Set<ResourceLocation> nativeCombBlockBeeTypes = new HashSet<>();
		for (RecipeHolder<CentrifugeRecipe> holder : honeycombRecipes) {
			CentrifugeRecipe recipe = holder.value();
			ItemStack[] inputItems = recipe.ingredient.getItems();
			if (inputItems.length == 0) continue;
			if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) {
				ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
				if (beeType != null) {
					nativeCombBlockBeeTypes.add(beeType);
				}
			}
		}

		// 第二遍 — 为无原生蜜脾块配方的 bee_type 派生
		for (RecipeHolder<CentrifugeRecipe> holder : honeycombRecipes) {
			CentrifugeRecipe recipe = holder.value();
			ItemStack[] inputItems = recipe.ingredient.getItems();
			if (inputItems.length == 0) continue;

			// 跳过已是蜜脾块配方的条目
			if (inputItems[0].getItem() == ModItems.CONFIGURABLE_COMB_BLOCK.get()) continue;

			// 提取bee_type，跳过没有bee_type的配方
			ResourceLocation beeType = inputItems[0].get(ModDataComponents.BEE_TYPE.get());
			if (beeType == null) continue;

			// 模块 6 修复：跳过有原生蜜脾块配方的 bee_type（原生配方已在 centrifugeRecipes 中注册）
			if (nativeCombBlockBeeTypes.contains(beeType)) continue;

			// 创建蜜脾块输入
			ItemStack combBlock = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
			combBlock.set(ModDataComponents.BEE_TYPE.get(), beeType);

			// 生成按配置倍率缩放的ChancedOutput列表
			int multiplier = ModConfig.SERVER.mekCentrifugeCombBlockMultiplier.get();
			List<ChancedOutput> blockOutputs = new ArrayList<>();
			for (ChancedOutput chanced : recipe.itemOutput) {
				// 模块 3：过滤 Wax 产出，复刻 PB 热力离心机 stripWax=true 行为
				if (isWaxOutput(chanced)) continue;
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
	 * 判断 ChancedOutput 是否为 Wax 产出（复刻 PB stripWax 行为）
	 */
	private static boolean isWaxOutput(ChancedOutput chanced) {
		ItemStack[] items = chanced.ingredient().getItems();
		for (ItemStack stack : items) {
			if (stack.is(WAXES_TAG)) return true;
		}
		return false;
	}

	/**
	 * 动态生成特殊蜜脾块离心配方 — 模块 1
	 * <br/>
	 * 为 4 种特殊蜜脾块（comb_ghostly/comb_milky/comb_powdery/vanilla honeycomb_block）
	 * 从 {@link CentrifugeRecipeIndex} 静态索引获取派生配方（4倍产出 + 过滤 Wax），
	 * 避免重复计算。索引在 rebuild 时一次性构建，JEI 注册时直接复用。
	 * <p>
	 * 与 {@link #generateCombBlockRecipes} 互补：后者处理有 bee_type 的 configurable_comb，
	 * 本方法处理无 bee_type 的特殊蜜脾（ghostly/milky/powdery/vanilla）。
	 */
	private List<CentrifugeRecipe> generateSpecialCombBlockRecipes() {
		List<CentrifugeRecipe> blockRecipes = new ArrayList<>();
		// PB 特殊蜜脾块（ModBlocks 与本项目 ModBlocks 重名，使用全限定名避免冲突）
		addSpecialBlockRecipeIfPresent(blockRecipes, cy.jdkdigital.productivebees.init.ModBlocks.COMB_GHOSTLY.get());
		addSpecialBlockRecipeIfPresent(blockRecipes, cy.jdkdigital.productivebees.init.ModBlocks.COMB_MILKY.get());
		addSpecialBlockRecipeIfPresent(blockRecipes, cy.jdkdigital.productivebees.init.ModBlocks.COMB_POWDERY.get());
		// 原版蜜脾块
		addSpecialBlockRecipeIfPresent(blockRecipes, Blocks.HONEYCOMB_BLOCK);
		return blockRecipes;
	}

	/**
	 * 从静态索引获取单个特殊蜜脾块配方并加入列表 — 模块 1 辅助方法
	 *
	 * @param recipes 配方收集列表
	 * @param block   特殊蜜脾块对应的方块（ItemLike）
	 */
	private void addSpecialBlockRecipeIfPresent(List<CentrifugeRecipe> recipes, ItemLike block) {
		ItemStack stack = new ItemStack(block);
		RecipeHolder<CentrifugeRecipe> holder = CentrifugeRecipeIndex.getSpecialCombBlock(stack);
		if (holder != null) {
			recipes.add(holder.value());
		}
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
		// ===== 离心机催化剂 =====
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

		// ===== 蜂箱催化剂 =====
		// 将所有MEK通用机械蜂箱注册为PB原版蜂箱配方的催化剂
		// 玩家点击蜂箱的"Show Recipes"可跳转到PB蜂箱配方界面，查看可容纳的蜜蜂种类和产物
		// 通过 PbCompatRefs 反射获取 PB compat 包的字段，避免直接引用 PB compat 类（软依赖隔离）
		Object advBeehiveTypeObj = PbCompatRefs.getAdvancedBeehiveType();
		if (!(advBeehiveTypeObj instanceof mezz.jei.api.recipe.RecipeType<?> advBeehiveType)) {
			DevLog.warn("jei", "无法获取 PB ADVANCED_BEEHIVE_TYPE，跳过蜂箱催化剂注册");
			return;
		}

		// 基础MEK蜂箱
		registry.addRecipeCatalyst(new ItemStack(ModBlocks.MEK_APIARY.get()), advBeehiveType);

		// 原版4等级蜂箱工厂
		registry.addRecipeCatalyst(new ItemStack(ModBlocks.BASIC_MEK_APIARY_FACTORY.get()), advBeehiveType);
		registry.addRecipeCatalyst(new ItemStack(ModBlocks.ADVANCED_MEK_APIARY_FACTORY.get()), advBeehiveType);
		registry.addRecipeCatalyst(new ItemStack(ModBlocks.ELITE_MEK_APIARY_FACTORY.get()), advBeehiveType);
		registry.addRecipeCatalyst(new ItemStack(ModBlocks.ULTIMATE_MEK_APIARY_FACTORY.get()), advBeehiveType);

		// EM扩展等级蜂箱工厂（仅EM加载时，Map为空则跳过）
		for (var entry : ModBlocks.EM_APIARY_FACTORIES.entrySet()) {
			registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), advBeehiveType);
		}

		// ME扩展等级蜂箱工厂（仅ME加载时，Map为空则跳过）
		for (var entry : ModBlocks.ME_APIARY_FACTORIES.entrySet()) {
			registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), advBeehiveType);
		}

		// EME扩展等级蜂箱工厂（仅EME加载时，Map为空则跳过）
		for (var entry : ModBlocks.EME_APIARY_FACTORIES.entrySet()) {
			registry.addRecipeCatalyst(new ItemStack(entry.getValue().get()), advBeehiveType);
		}
	}

	/**
	 * JEI运行时可用回调 — 用于在运行时隐藏蜜蜂实体和相关配方
	 * <br/>
	 * 当万象创世蜜蜂被禁用时，从JEI中隐藏：
	 * 1. 万象创世蜜蜂实体（BeeIngredient）
	 * 2. 所有获取万象创世蜜蜂的配方（钓鱼、繁殖、转化、蜂巢生成等）
	 * <p>
	 * 此方法在JEI完全初始化后调用，可以访问所有已注册的ingredient和配方。
	 */
	@Override
	public void onRuntimeAvailable(IJeiRuntime runtime) {
		// 检查配置是否加载以及万象创世是否被禁用
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			return;
		}
		if (ModConfig.SERVER.myriadCreationsEnabled.get()) {
			return; // 万象创世已启用，无需隐藏
		}

		var ingredientManager = runtime.getIngredientManager();
		var recipeManager = runtime.getRecipeManager();

		// 1. 隐藏万象创世蜜蜂的BeeIngredient
		BeeIngredient myriadIngredient = BeeIngredientFactory.getOrCreateList().get(PBConstants.MYRIADCREATIONS_TYPE_STRING);
		if (myriadIngredient != null) {
			// 通过 PbCompatRefs 反射获取 PB compat 包的字段，避免直接引用 PB compat 类（软依赖隔离）
			Object beeIngredientTypeObj = PbCompatRefs.getBeeIngredientType();
			if (beeIngredientTypeObj != null) {
				removeBeeIngredientAtRuntime(ingredientManager, beeIngredientTypeObj, myriadIngredient);
			}
		}

		// 2. 隐藏所有获取万象创世蜜蜂的配方（钓鱼、繁殖、转化、蜂巢生成）
		String[][] fields = {
				{"BEE_FISHING_TYPE", "钓鱼"},
				{"BEE_BREEDING_TYPE", "繁殖"},
				{"BEE_CONVERSION_TYPE", "转化"},
				{"BEE_SPAWNING_TYPE", "蜂巢生成"}
		};
		// 通过 PbCompatRefs 反射获取 PB compat 包的类，避免直接引用 PB compat 类（软依赖隔离）
		Class<?> pbJeiPluginClass = PbCompatRefs.getPbJeiPluginClass();
		if (pbJeiPluginClass == null) {
			return;
		}
		for (String[] f : fields) {
			JeiRecipeHider.hideRecipesByReflection(
					recipeManager,
					pbJeiPluginClass,
					f[0], f[1],
					PBConstants.MYRIADCREATIONS_TYPE_STRING);
		}
	}

	/**
	 * 通过反射类型转换调用 ingredientManager.removeIngredientsAtRuntime
	 * <br/>
	 * PB 的 BEE_INGREDIENT 字段类型为 {@code IIngredientType<BeeIngredient>}，反射获取后为 Object。
	 * 通过 unchecked cast 将 Object 转换为 {@code IIngredientType<T>}，并将 BeeIngredient 作为 T 传入。
	 * 类型擦除保证运行时安全。
	 *
	 * @param manager    JEI ingredient 管理器
	 * @param typeObj    反射获取的 IIngredientType 实例
	 * @param ingredient 要移除的 ingredient（BeeIngredient）
	 * @param <T>        ingredient 类型参数（运行时由 typeObj 决定，编译期无法确定）
	 */
	@SuppressWarnings("unchecked")
	private static <T> void removeBeeIngredientAtRuntime(
			IIngredientManager manager,
			Object typeObj,
			Object ingredient) {
		IIngredientType<T> type = (IIngredientType<T>) typeObj;
		T castIngredient = (T) ingredient;
		manager.removeIngredientsAtRuntime(type, List.of(castIngredient));
	}
}

