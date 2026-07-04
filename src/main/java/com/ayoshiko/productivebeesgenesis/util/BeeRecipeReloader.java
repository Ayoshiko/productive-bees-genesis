package com.ayoshiko.productivebeesgenesis.util;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.biome.Biome;

/**
 * 蜜蜂配方重载器
 * <br/>
 * 在数据包重载（/reload、服务器启动、数据包变更）后根据 {@link ModConfig} 动态修改
 * ProductiveBees 的 bee_fishing / bee_breeding / bee_spawning / bee_conversion 配方，
 * 实现万象创世蜜蜂获得方式的运行时配置。
 * <p>
 * 原理：通过 {@link PreparableReloadListener} 在 RecipeManager 完成加载后介入，
 * 收集全部配方并替换其中万象创世相关的条目，最后调用 {@link RecipeManager#replaceRecipes}
 * 全量替换。线程安全：reload 的 apply 阶段在 gameExecutor（主线程）执行，无并发问题。
 * <p>
 * 首次世界加载问题修复：配置可能在配方重载时未完全加载，此时会安排延迟重试任务，
 * 在服务器 tick 中检查配置就绪后再次尝试应用配方修改。
 */
public final class BeeRecipeReloader implements PreparableReloadListener {

	private final RecipeManager recipeManager;
	private final HolderLookup.Provider registryAccess;

	/**
	 * 不可变快照：封装延迟重试所需的所有上下文
	 * <p>
	 * 通过单一 volatile 引用原子替换，避免多 volatile 字段在 clear/set 期间
	 * 出现 "recipeManager 已清空但 registryAccess 仍为旧值" 的不一致状态。
	 */
	private record PendingRetryContext(
			RecipeManager recipeManager,
			HolderLookup.Provider registryAccess) {
		// 空上下文表示无待重试任务
		static final PendingRetryContext EMPTY = new PendingRetryContext(null, null);
	}

	/** 当前待重试上下文 — volatile 引用保证原子替换；非 EMPTY 即表示有待重试任务 */
	private static volatile PendingRetryContext pendingRetryContext = PendingRetryContext.EMPTY;

	private static final AtomicInteger retryCount = new AtomicInteger(0);
	private static final int MAX_RETRY_COUNT = 60; // 最多重试60次（约3秒）

	/** 是否有待重试任务（ volatile 读保证可见性） */
	private static boolean hasPendingRetry() {
		return pendingRetryContext != PendingRetryContext.EMPTY;
	}

	/**
	 * 清空待重试上下文（原子替换为空）
	 * <p>
	 * 公开访问：供 {@link ProductiveBeesGenesis#onServerStopped} 在服务器停止时调用，
	 * 防止 pendingRetryContext 持有的 RecipeManager / HolderLookup.Provider 引用阻碍 GC。
	 */
	public static void clearPendingRetryContext() {
		pendingRetryContext = PendingRetryContext.EMPTY;
		retryCount.set(0);
	}

	/**
	 * @param recipeManager 配方管理器（来自 ReloadableServerResources）
	 * @param registryAccess 注册表访问（来自 AddReloadListenerEvent.getRegistryAccess()）
	 */
	public BeeRecipeReloader(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		this.recipeManager = recipeManager;
		this.registryAccess = registryAccess;
	}

	@Override
	public CompletableFuture<Void> reload(
			PreparationBarrier barrier,
			ResourceManager resourceManager,
			ProfilerFiller preparationsProfiler,
			ProfilerFiller reloadProfiler,
			Executor backgroundExecutor,
			Executor gameExecutor) {
		// 准备阶段无操作，仅在 apply 阶段（主线程）执行配方覆盖
		return barrier.wait(CompletableFuture.completedFuture(null))
				.thenRunAsync(this::overrideRecipes, gameExecutor);
	}

	/**
	 * 服务器 tick 回调 — 处理延迟重试逻辑
	 * <br/>
	 * 当首次进入世界时配置可能未加载，此时会在后续 tick 中重试应用配方修改。
	 * 由 {@link com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis} 注册到事件总线。
	 * <p>
	 * 性能优化：使用 volatile 标志快速检查，避免不必要的配置加载检查。
	 * 仅在 pendingRetry 为 true 时执行，正常游戏过程中此标志为 false，几乎零开销。
	 */
	public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
		// 快速路径：使用 volatile 读取，无锁开销
		// 99.9% 的情况下无待重试任务，直接返回
		if (!hasPendingRetry()) {
			return;
		}

		// 慢速路径：需要重试
		onServerTickSlowPath();
	}

	/**
	 * 慢速路径处理 — 仅在需要重试时执行
	 * <br/>
	 * 从 onServerTick 分离，避免影响正常 tick 性能。
	 */
	private static void onServerTickSlowPath() {
		// 单次快照读取保证 recipeManager 和 registryAccess 一致性
		PendingRetryContext ctx = pendingRetryContext;
		if (ctx == PendingRetryContext.EMPTY || ctx.recipeManager() == null) {
			clearPendingRetryContext();
			retryCount.set(0);
			return;
		}

		// 检查配置是否已加载
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			int currentRetry = retryCount.incrementAndGet();
			if (currentRetry >= MAX_RETRY_COUNT) {
				ProductiveBeesGenesis.LOGGER.warn("配方重载重试次数超过上限({})，放弃应用万象创世配方修改", MAX_RETRY_COUNT);
				clearPendingRetryContext();
				retryCount.set(0);
			}
			return;
		}

		// 配置已加载，执行配方修改
		try {
			ProductiveBeesGenesis.LOGGER.info("配置已就绪，执行延迟配方重载...");
			BeeRecipeReloader reloader = new BeeRecipeReloader(ctx.recipeManager(), ctx.registryAccess());
			reloader.overrideRecipesInternal();
			ProductiveBeesGenesis.LOGGER.info("延迟配方重载完成");
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("延迟配方重载失败", e);
		} finally {
			clearPendingRetryContext();
			retryCount.set(0);
		}
	}

	/**
	 * 收集全部配方，处理万象创世相关条目，若有修改则全量替换
	 */
	private void overrideRecipes() {
		try {
			// 配置未加载时安排延迟重试（首次进入世界时常见）
			if (!ModConfig.SERVER_SPEC.isLoaded()) {
				ProductiveBeesGenesis.LOGGER.info("SERVER 配置未加载，安排延迟配方重载");
				// 原子替换：使用不可变快照封装 recipeManager 和 registryAccess，
				// 保证 onServerTickSlowPath 读取时两个字段一致
				pendingRetryContext = new PendingRetryContext(this.recipeManager, this.registryAccess);
				retryCount.set(0);
				return;
			}
			overrideRecipesInternal();
		} catch (Exception e) {
			// 任何异常都不应导致整体崩溃
			ProductiveBeesGenesis.LOGGER.error("重载万象创世蜜蜂配方时发生错误", e);
		}
	}

	/**
	 * 内部配方覆盖逻辑 — 假设配置已加载
	 */
	private void overrideRecipesInternal() {
		// 防御性检查：确保配置已加载
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			ProductiveBeesGenesis.LOGGER.warn("配置未加载，跳过配方覆盖");
			return;
		}

		// 就绪检查：BeeIngredientFactory 必须已加载 myriadcreations，否则 toNetwork 序列化时会 NPE
		if (!BeeIngredientFactory.getOrCreateList().containsKey(PBConstants.MYRIADCREATIONS_TYPE_STRING)) {
			ProductiveBeesGenesis.LOGGER.warn("BeeIngredientFactory 未就绪（缺少 myriadcreations），跳过配方替换");
			return;
		}
		List<RecipeHolder<?>> sourceRecipes = new ArrayList<>(recipeManager.getRecipes());
		// 构建新列表，避免在遍历中通过索引 remove(i)/i-- 造成的易错写法
		List<RecipeHolder<?>> processedRecipes = new ArrayList<>(sourceRecipes.size());
		boolean modified = false;

		for (RecipeHolder<?> holder : sourceRecipes) {
			RecipeHolder<?> processed = processRecipe(holder);
			if (processed == null) {
				// processRecipe 返回 null 表示移除该配方
				modified = true;
			} else {
				if (processed != holder) {
					modified = true;
				}
				processedRecipes.add(processed);
			}
		}

		if (modified) {
			recipeManager.replaceRecipes(processedRecipes);
			clearBeeFishingCaches();
			ProductiveBeesGenesis.LOGGER.info("万象创世蜜蜂配方已根据配置重载");
		}
	}

	/**
	 * 处理单个配方，返回 null 表示移除，返回原 holder 表示保留，返回新 holder 表示替换
	 */
	@SuppressWarnings("unchecked")
	private RecipeHolder<?> processRecipe(RecipeHolder<?> holder) {
		Recipe<?> recipe = holder.value();

		// 钓鱼配方：修改概率与群系，或禁用
		if (recipe instanceof BeeFishingRecipe fishing) {
			if (!isMyriadcreations(fishing.output)) {
				return holder;
			}
			// 总开关禁用时移除所有万象创世配方
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
				return null;
			}
			if (!ModConfig.SERVER.fishingEnabled.get()) {
				return null;
			}
			HolderSet<Biome> biomes = createBiomeHolderSet(ModConfig.SERVER.fishingBiomes.get());
			float chance = ModConfig.SERVER.fishingChance.get().floatValue();
			BeeFishingRecipe newRecipe = new BeeFishingRecipe(fishing.output, biomes, chance);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 繁殖配方：修改亲代，或禁用
		if (recipe instanceof BeeBreedingRecipe breeding) {
			// 检查 offspring、parent1 或 parent2 是否涉及万象创世蜜蜂
			boolean involvesMyriadCreations = isMyriadcreations(breeding.offspring)
					|| isMyriadcreations(breeding.parent1)
					|| isMyriadcreations(breeding.parent2);
			if (!involvesMyriadCreations) {
				return holder;
			}
			// 总开关禁用时移除所有涉及万象创世的繁殖配方
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
				return null;
			}
			// 只有 offspring 是万象创世时才修改亲代配置
			if (!isMyriadcreations(breeding.offspring)) {
				return holder;
			}
			if (!ModConfig.SERVER.breedingEnabled.get()) {
				return null;
			}
			Supplier<BeeIngredient> parent1 = BeeIngredientFactory.getIngredient(ModConfig.SERVER.breedingParent1.get());
			Supplier<BeeIngredient> parent2 = BeeIngredientFactory.getIngredient(ModConfig.SERVER.breedingParent2.get());
			BeeBreedingRecipe newRecipe = new BeeBreedingRecipe(parent1, parent2, breeding.offspring, breeding.parentDeathChance);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 蜂巢生成配方：修改蜂巢物品与群系，或禁用
		if (recipe instanceof BeeSpawningRecipe spawning) {
			if (!containsMyriadcreations(spawning.output)) {
				return holder;
			}
			// 总开关禁用时移除所有万象创世配方
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
				return null;
			}
			if (!ModConfig.SERVER.spawningEnabled.get()) {
				return null;
			}
			Ingredient ingredient = createIngredient(ModConfig.SERVER.spawningNest.get());
			HolderSet<Biome> biomes = createBiomeHolderSetFromString(ModConfig.SERVER.spawningBiomes.get());
			BeeSpawningRecipe newRecipe = new BeeSpawningRecipe(ingredient, spawning.spawnItem, spawning.output, biomes);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 蜜蜂转化配方：用其他物品转化获得万象创世，或禁用
		if (recipe instanceof BeeConversionRecipe conversion) {
			if (!isMyriadcreations(conversion.result)) {
				return holder;
			}
			// 总开关禁用时移除所有万象创世配方
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
				return null;
			}
			if (!ModConfig.SERVER.conversionEnabled.get()) {
				return null;
			}
			Supplier<BeeIngredient> source = getBeeIngredient(ModConfig.SERVER.conversionSource.get());
			Supplier<BeeIngredient> result = getBeeIngredient(ModConfig.SERVER.conversionResult.get());
			Ingredient item = createIngredient(ModConfig.SERVER.conversionItem.get());
			float chance = ModConfig.SERVER.conversionChance.get().floatValue();
			BeeConversionRecipe newRecipe = new BeeConversionRecipe(source, result, item, chance);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		// 蜜蜂产出配方：万象创世蜜脾产出参数，或禁用
		if (recipe instanceof AdvancedBeehiveRecipe produce) {
			if (!isMyriadcreations(produce.ingredient)) {
				return holder;
			}
			// 总开关禁用时移除所有万象创世配方
			if (!MyriadCreationsEventHandler.isMyriadCreationsEnabled()) {
				return null;
			}
			if (!ModConfig.SERVER.produceEnabled.get()) {
				return null;
			}
			List<TagOutputRecipe.ChancedOutput> outputs = createProduceOutputs();
			AdvancedBeehiveRecipe newRecipe = new AdvancedBeehiveRecipe(produce.ingredient, outputs);
			return new RecipeHolder<>(holder.id(), newRecipe);
		}

		return holder;
	}

	/**
	 * 判断 BeeIngredient 供应商是否对应万象创世蜜蜂
	 */
	private static boolean isMyriadcreations(Supplier<BeeIngredient> supplier) {
		try {
			if (supplier == null) {
				return false;
			}
			// 缓存 supplier.get() 结果，避免重复求值（supplier 可能涉及懒加载）
			BeeIngredient ing = supplier.get();
			return ing != null && PBConstants.MYRIADCREATIONS_TYPE.equals(ing.getBeeType());
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("isMyriadcreations 检查异常", e);
			return false;
		}
	}

	/**
	 * 判断 BeeIngredient 供应商列表中是否包含万象创世蜜蜂
	 */
	private static boolean containsMyriadcreations(List<Supplier<BeeIngredient>> outputs) {
		if (outputs == null || outputs.isEmpty()) {
			return false;
		}
		for (Supplier<BeeIngredient> supplier : outputs) {
			if (isMyriadcreations(supplier)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 根据群系 ID 列表构建 HolderSet（每个元素为单个群系 ID）
	 */
	private HolderSet<Biome> createBiomeHolderSet(List<? extends String> biomeIds) {
		if (biomeIds == null || biomeIds.isEmpty()) {
			return HolderSet.empty();
		}
		List<Holder<Biome>> holders = new ArrayList<>();
		for (String id : biomeIds) {
			Holder<Biome> holder = resolveBiome(id);
			if (holder != null) {
				holders.add(holder);
			} else {
				ProductiveBeesGenesis.LOGGER.warn("钓鱼群系 '{}' 未找到，已跳过", id);
			}
		}
		return holders.isEmpty() ? HolderSet.empty() : HolderSet.direct(holders);
	}

	/**
	 * 根据单个群系规格构建 HolderSet（支持标签 "#xxx" 或群系 ID "xxx"）
	 */
	private HolderSet<Biome> createBiomeHolderSetFromString(String biomeSpec) {
		if (biomeSpec == null || biomeSpec.isBlank()) {
			return HolderSet.empty();
		}
		if (biomeSpec.startsWith("#")) {
			// 标签规格：解析为 TagKey 并从注册表获取对应的 Named HolderSet
			try {
				ResourceLocation tagLoc = ResourceLocation.parse(biomeSpec.substring(1));
				TagKey<Biome> tagKey = TagKey.create(Registries.BIOME, tagLoc);
				Optional<HolderSet.Named<Biome>> tag = registryAccess.lookup(Registries.BIOME)
						.flatMap(reg -> reg.get(tagKey));
				// 显式宽化 Named<Biome> → HolderSet<Biome>，使 orElse 类型匹配
				return tag.<HolderSet<Biome>>map(n -> n).orElse(HolderSet.empty());
			} catch (Exception e) {
				ProductiveBeesGenesis.LOGGER.warn("解析群系标签 '{}' 失败", biomeSpec, e);
				return HolderSet.empty();
			}
		}
		// 单个群系 ID
		Holder<Biome> holder = resolveBiome(biomeSpec);
		if (holder == null) {
			ProductiveBeesGenesis.LOGGER.warn("生成群系 '{}' 未找到", biomeSpec);
			return HolderSet.empty();
		}
		return HolderSet.direct(List.of(holder));
	}

	/**
	 * 通过群系 ID 解析为 Holder<Biome>
	 */
	private Holder<Biome> resolveBiome(String biomeId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(biomeId);
			ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, rl);
			return registryAccess.lookup(Registries.BIOME)
					.flatMap(reg -> reg.get(key))
					.map(h -> (Holder<Biome>) h)
					.orElse(null);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析群系 '{}' 失败", biomeId, e);
			return null;
		}
	}

	/**
	 * 根据物品 ID 创建 Ingredient，找不到则返回 EMPTY
	 */
	private static Ingredient createIngredient(String itemId) {
		try {
			ResourceLocation rl = ResourceLocation.parse(itemId);
			Optional<Item> item = BuiltInRegistries.ITEM.getOptional(rl);
			return item.<Ingredient>map(i -> Ingredient.of(i)).orElse(Ingredient.EMPTY);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("解析蜂巢物品 '{}' 失败，使用空 Ingredient", itemId, e);
			return Ingredient.EMPTY;
		}
	}

	/**
	 * 根据蜜蜂类型名获取 BeeIngredient 供应商
	 * <br/>
	 * 使用 {@link BeeIngredientFactory#getIngredient(String)} 获取 lazy supplier。
	 * 调用前会先校验 BeeIngredientFactory 已包含该类型，避免序列化时返回 null。
	 * 若类型不存在，回退到 minecraft:bee 防止 NPE。
	 */
	private static Supplier<BeeIngredient> getBeeIngredient(String name) {
		if (name == null || name.isBlank()) {
			return BeeIngredientFactory.getIngredient("minecraft:bee");
		}
		if (!BeeIngredientFactory.getOrCreateList().containsKey(name)) {
			ProductiveBeesGenesis.LOGGER.warn("蜜蜂类型 '{}' 未在 BeeIngredientFactory 中找到，回退到 minecraft:bee", name);
			return BeeIngredientFactory.getIngredient("minecraft:bee");
		}
		return BeeIngredientFactory.getIngredient(name);
	}

	/**
	 * 根据配置构建万象创世蜜脾的产出列表
	 * <br/>
	 * 返回单个 {@link TagOutputRecipe.ChancedOutput}，物品、数量、概率均来自配置。
	 */
	private static List<TagOutputRecipe.ChancedOutput> createProduceOutputs() {
		Ingredient ingredient = createIngredient(ModConfig.SERVER.produceOutputItem.get());
		int min = ModConfig.SERVER.produceOutputMin.get();
		int max = ModConfig.SERVER.produceOutputMax.get();
		// 防御性处理：当配置出现 min > max 时自动纠正，避免 ChancedOutput 行为异常
		int finalMin = Math.min(min, max);
		int finalMax = Math.max(min, max);
		if (finalMin != min || finalMax != max) {
			ProductiveBeesGenesis.LOGGER.warn("produceOutputMin({}) > produceOutputMax({})，已自动交换", min, max);
		}
		float chance = ModConfig.SERVER.produceOutputChance.get().floatValue();
		List<TagOutputRecipe.ChancedOutput> outputs = new ArrayList<>(1);
		outputs.add(new TagOutputRecipe.ChancedOutput(ingredient, finalMin, finalMax, chance));
		return outputs;
	}

	/**
	 * 通过反射清理 BeeFishingRecipe 的静态缓存
	 * <br/>
	 * PB 在 BeeFishingRecipe 中维护了两个静态缓存（cachedBiomes、cachedRecipes），
	 * 替换配方后旧缓存会引用过期的 Recipe 实例，必须清理避免数据不一致。
	 */
	@SuppressWarnings("unchecked")
	private static void clearBeeFishingCaches() {
		try {
			Field cachedBiomesField = BeeFishingRecipe.class.getDeclaredField("cachedBiomes");
			cachedBiomesField.setAccessible(true);
			Map<?, ?> cachedBiomes = (Map<?, ?>) cachedBiomesField.get(null);
			if (cachedBiomes != null) {
				cachedBiomes.clear();
			}

			Field cachedRecipesField = BeeFishingRecipe.class.getDeclaredField("cachedRecipes");
			cachedRecipesField.setAccessible(true);
			Map<?, ?> cachedRecipes = (Map<?, ?>) cachedRecipesField.get(null);
			if (cachedRecipes != null) {
				cachedRecipes.clear();
			}
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("清理 BeeFishingRecipe 静态缓存失败", e);
		}
	}
}
