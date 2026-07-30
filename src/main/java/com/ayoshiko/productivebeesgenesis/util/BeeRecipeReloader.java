package com.ayoshiko.productivebeesgenesis.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;

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
 * <p>
 * <b>职责分离</b>（v1.7.0 拆分）：
 * <ul>
 *   <li>{@link RecipeReloadRetryManager} — 延迟重试上下文管理</li>
 *   <li>{@link RecipeIngredientFactory} — Ingredient 与 Biome 解析</li>
 *   <li>{@link MyriadRecipeProcessor} — 单配方处理逻辑</li>
 *   <li>{@link PBReflectionCacheCleaner} — PB 反射缓存清理</li>
 * </ul>
 * 本类仅作为 {@link PreparableReloadListener} 入口，协调上述 4 个组件。
 */
public final class BeeRecipeReloader implements PreparableReloadListener {

	private final RecipeManager recipeManager;
	private final HolderLookup.Provider registryAccess;

	private final RecipeIngredientFactory ingredientFactory;
	private final MyriadRecipeProcessor recipeProcessor;

	/**
	 * @param recipeManager 配方管理器（来自 ReloadableServerResources）
	 * @param registryAccess 注册表访问（来自 AddReloadListenerEvent.getRegistryAccess()）
	 */
	public BeeRecipeReloader(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		this.recipeManager = recipeManager;
		this.registryAccess = registryAccess;
		this.ingredientFactory = new RecipeIngredientFactory(registryAccess);
		this.recipeProcessor = new MyriadRecipeProcessor(ingredientFactory);
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
	 * 服务器 tick 回调 — 委托给 {@link RecipeReloadRetryManager}
	 * <br/>
	 * 由 {@link ProductiveBeesGenesis} 注册到事件总线。
	 * 保留 public static 方法签名以兼容现有注册代码。
	 */
	public static void onServerTick(net.neoforged.neoforge.event.tick.ServerTickEvent.Post event) {
		// 延迟重试时通过 BiConsumer 回调重建 BeeRecipeReloader 实例并执行配方覆盖
		RecipeReloadRetryManager.onServerTick((recipeManager, registryAccess) -> {
			BeeRecipeReloader reloader = new BeeRecipeReloader(recipeManager, registryAccess);
			reloader.overrideRecipesInternal();
		});
	}

	/**
	 * 收集全部配方，处理万象创世相关条目，若有修改则全量替换
	 */
	private void overrideRecipes() {
		try {
			// 配置未加载时安排延迟重试（首次进入世界时常见）
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			RecipeReloadRetryManager.scheduleRetry(this.recipeManager, this.registryAccess);
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
			DevLog.warn("recipe_reload", "配置未加载，跳过配方覆盖");
			return;
		}

		// 就绪检查：BeeIngredientFactory 必须已加载 myriadcreations，否则 toNetwork 序列化时会 NPE
		if (!BeeIngredientFactory.getOrCreateList().containsKey(PBConstants.MYRIADCREATIONS_TYPE_STRING)) {
			// PB 的 BeeIngredientFactory 尚未加载 myriadcreations 类型，配方替换会因 toNetwork 序列化失败而 NPE。
			// 重新安排延迟重试（不重置 retryCount）：在后续 tick 中检查 BeeIngredientFactory 是否就绪，
			// 避免配方永远不被替换。使用 rescheduleRetry 而非 scheduleRetry，
			// 让 retryCount 累积，达到 MAX_RETRY_COUNT 后放弃，避免无限重试。
			// 此场景常见于首次进入世界时 PB 注册顺序晚于本模组的 reload listener。
			RecipeReloadRetryManager.rescheduleRetry(this.recipeManager, this.registryAccess);
			return;
		}
		List<RecipeHolder<?>> sourceRecipes = new ArrayList<>(recipeManager.getRecipes());
		// 构建新列表，避免在遍历中通过索引 remove(i)/i-- 造成的易错写法
		List<RecipeHolder<?>> processedRecipes = new ArrayList<>(sourceRecipes.size());
		boolean modified = false;

		for (RecipeHolder<?> holder : sourceRecipes) {
			RecipeHolder<?> processed = recipeProcessor.processRecipe(holder);
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
			PBReflectionCacheCleaner.clearBeeFishingCaches();
		}

		// 重建离心配方索引 — 确保服务端配方重载完成后索引必定更新
		// 修复：onTagsReload 触发时 ServerLifecycleHooks.getCurrentServer() 可能为 null（服务器启动早期），
		// 导致服务端跳过索引重建，所有配方查找走 FALLBACK 全量遍历路径（性能 O(N) 而非 O(1)）
		CentrifugeRecipeIndex.rebuild(recipeManager);
	}
}
