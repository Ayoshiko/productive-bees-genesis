package com.ayoshiko.productivebeesgenesis.util;

import java.util.concurrent.atomic.AtomicInteger;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * 配方重载延迟重试管理器
 * <br/>
 * 从 {@link BeeRecipeReloader} 抽离，负责管理配方重载的延迟重试上下文。
 * <p>
 * 首次世界加载时配置可能未完全加载，此时会安排延迟重试任务，
 * 在服务器 tick 中检查配置就绪后再次尝试应用配方修改。
 * <p>
 * <b>线程安全</b>：
 * <ul>
 *   <li>{@link #pendingRetryContext} 为 volatile 引用，保证原子替换</li>
 *   <li>{@link PendingRetryContext} 为不可变 record，封装 recipeManager 和 registryAccess</li>
 *   <li>通过单一 volatile 引用原子替换，避免多 volatile 字段在 clear/set 期间的不一致状态</li>
 * </ul>
 */
public final class RecipeReloadRetryManager {

	/**
	 * 不可变快照：封装延迟重试所需的所有上下文
	 * <p>
	 * 通过单一 volatile 引用原子替换，避免多 volatile 字段在 clear/set 期间
	 * 出现 "recipeManager 已清空但 registryAccess 仍为旧值" 的不一致状态。
	 */
	public record PendingRetryContext(
			RecipeManager recipeManager,
			HolderLookup.Provider registryAccess) {
		// 空上下文表示无待重试任务
		static final PendingRetryContext EMPTY = new PendingRetryContext(null, null);
	}

	/** 当前待重试上下文 — volatile 引用保证原子替换；非 EMPTY 即表示有待重试任务 */
	private static volatile PendingRetryContext pendingRetryContext = PendingRetryContext.EMPTY;

	private static final AtomicInteger retryCount = new AtomicInteger(0);
	private static final int MAX_RETRY_COUNT = 60; // 最多重试60次（约3秒）

	private RecipeReloadRetryManager() {}

	/** 是否有待重试任务（volatile 读保证可见性） */
	public static boolean hasPendingRetry() {
		return pendingRetryContext != PendingRetryContext.EMPTY;
	}

	/**
	 * 安排延迟重试任务
	 * <br/>
	 * 原子替换：使用不可变快照封装 recipeManager 和 registryAccess，
	 * 保证 onServerTickSlowPath 读取时两个字段一致。
	 */
	public static void scheduleRetry(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		pendingRetryContext = new PendingRetryContext(recipeManager, registryAccess);
		retryCount.set(0);
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
	 * 服务器 tick 回调 — 处理延迟重试逻辑
	 * <br/>
	 * 当首次进入世界时配置可能未加载，此时会在后续 tick 中重试应用配方修改。
	 * 由 {@link ProductiveBeesGenesis} 注册到事件总线。
	 * <p>
	 * 性能优化：使用 volatile 标志快速检查，避免不必要的配置加载检查。
	 * 仅在 pendingRetry 为 true 时执行，正常游戏过程中此标志为 false，几乎零开销。
	 *
	 * @param reloader 待执行的配方重载器（配置就绪时调用其 overrideRecipesInternal）
	 */
	public static void onServerTick(Runnable reloader) {
		// 快速路径：使用 volatile 读取，无锁开销
		// 99.9% 的情况下无待重试任务，直接返回
		if (!hasPendingRetry()) {
			return;
		}

		// 慢速路径：需要重试
		onServerTickSlowPath(reloader);
	}

	/**
	 * 慢速路径处理 — 仅在需要重试时执行
	 * <br/>
	 * 从 onServerTick 分离，避免影响正常 tick 性能。
	 */
	private static void onServerTickSlowPath(Runnable reloader) {
		// 单次快照读取保证 recipeManager 和 registryAccess 一致性
		PendingRetryContext ctx = pendingRetryContext;
		if (ctx == PendingRetryContext.EMPTY || ctx.recipeManager() == null) {
			clearPendingRetryContext();
			return;
		}

		// 检查配置是否已加载
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			int currentRetry = retryCount.incrementAndGet();
			if (currentRetry >= MAX_RETRY_COUNT) {
				ProductiveBeesGenesis.LOGGER.warn("配方重载重试次数超过上限({})，放弃应用万象创世配方修改", MAX_RETRY_COUNT);
				clearPendingRetryContext();
			}
			return;
		}

		// 配置已加载，执行配方修改
		try {
			ProductiveBeesGenesis.LOGGER.info("配置已就绪，执行延迟配方重载...");
			reloader.run();
			ProductiveBeesGenesis.LOGGER.info("延迟配方重载完成");
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("延迟配方重载失败", e);
		} finally {
			clearPendingRetryContext();
		}
	}
}
