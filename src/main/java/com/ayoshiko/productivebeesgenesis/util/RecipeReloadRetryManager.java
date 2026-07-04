package com.ayoshiko.productivebeesgenesis.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.crafting.RecipeManager;

/**
 * 配方重载延迟重试管理器
 * <br/>
 * 从 {@link BeeRecipeReloader} 抽离，负责管理配方重载的延迟重试上下文。
 * <p>
 * 首次世界加载时配置可能未完全加载，或者 PB 的 BeeIngredientFactory 尚未加载 myriadcreations 类型，
 * 此时会安排延迟重试任务，在服务器 tick 中检查就绪后再次尝试应用配方修改。
 * <p>
 * <b>线程安全</b>：
 * <ul>
 *   <li>{@link #pendingRetryContext} 为 {@link AtomicReference}，保证原子替换与 CAS 清除</li>
 *   <li>{@link PendingRetryContext} 为不可变 record，封装 recipeManager 和 registryAccess</li>
 *   <li>通过单一 AtomicReference 原子替换，避免多 volatile 字段在 clear/set 期间的不一致状态</li>
 *   <li>使用 {@code compareAndSet(ctx, EMPTY)} 清除：保证只清除自己读取的 context 实例，
 *       不会覆盖 reloader 调用 {@link #scheduleRetry} / {@link #rescheduleRetry} 设置的新 ctx</li>
 * </ul>
 * <p>
 * <b>重试计数策略</b>：
 * <ul>
 *   <li>{@link #scheduleRetry} — 重置 retryCount，用于外部首次 reload 事件（配置未加载）</li>
 *   <li>{@link #rescheduleRetry} — 不重置 retryCount，用于已在重试流程中的子重试（BeeIngredientFactory 未就绪），
 *       让重试次数累积，达到 {@link #MAX_RETRY_COUNT} 后放弃，避免无限重试</li>
 * </ul>
 */
public final class RecipeReloadRetryManager {

	/**
	 * 不可变快照：封装延迟重试所需的所有上下文
	 * <p>
	 * 通过单一 AtomicReference 原子替换，避免多 volatile 字段在 clear/set 期间
	 * 出现 "recipeManager 已清空但 registryAccess 仍为旧值" 的不一致状态。
	 */
	public record PendingRetryContext(
			RecipeManager recipeManager,
			HolderLookup.Provider registryAccess) {
		// 空上下文表示无待重试任务
		static final PendingRetryContext EMPTY = new PendingRetryContext(null, null);
	}

	/**
	 * 当前待重试上下文 — AtomicReference 保证原子替换与 CAS 清除
	 * <p>
	 * 使用 CAS（compareAndSet）清除：onServerTickSlowPath 读取 ctx 后调用 reloader，
	 * reloader 可能调用 scheduleRetry/rescheduleRetry 设置新 ctx。
	 * 如果用直接赋值 clear，会覆盖新设置的 ctx，导致重试任务丢失。
	 * CAS 只在自己读取的 ctx 未被修改时清除，保证新 ctx 不被覆盖。
	 */
	private static final AtomicReference<PendingRetryContext> pendingRetryContext =
			new AtomicReference<>(PendingRetryContext.EMPTY);

	private static final AtomicInteger retryCount = new AtomicInteger(0);
	private static final int MAX_RETRY_COUNT = 60; // 最多重试60次（约3秒）

	private RecipeReloadRetryManager() {}

	/** 是否有待重试任务（原子读保证可见性） */
	public static boolean hasPendingRetry() {
		return pendingRetryContext.get() != PendingRetryContext.EMPTY;
	}

	/**
	 * 安排延迟重试任务（重置重试计数）
	 * <br/>
	 * 原子替换：使用不可变快照封装 recipeManager 和 registryAccess，
	 * 保证 onServerTickSlowPath 读取时两个字段一致。
	 * <p>
	 * 重置 retryCount：用于外部首次 reload 事件（如配置未加载），新的 reload 事件应重新开始计数。
	 */
	public static void scheduleRetry(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		pendingRetryContext.set(new PendingRetryContext(recipeManager, registryAccess));
		retryCount.set(0);
	}

	/**
	 * 重新安排延迟重试任务（不重置重试计数）
	 * <br/>
	 * 用于已在重试流程中的子重试场景：例如 {@link BeeRecipeReloader#overrideRecipesInternal}
	 * 中 BeeIngredientFactory 未就绪时调用此方法安排下次 tick 重试。
	 * <p>
	 * 不重置 retryCount：让重试次数累积，达到 {@link #MAX_RETRY_COUNT} 后放弃，避免无限重试。
	 * 与 {@link #scheduleRetry} 的区别仅在于不重置计数器。
	 */
	public static void rescheduleRetry(RecipeManager recipeManager, HolderLookup.Provider registryAccess) {
		pendingRetryContext.set(new PendingRetryContext(recipeManager, registryAccess));
	}

	/**
	 * 清空待重试上下文（原子替换为空）
	 * <p>
	 * 公开访问：供 {@link ProductiveBeesGenesis#onServerStopped} 在服务器停止时调用，
	 * 防止 pendingRetryContext 持有的 RecipeManager / HolderLookup.Provider 引用阻碍 GC。
	 */
	public static void clearPendingRetryContext() {
		pendingRetryContext.set(PendingRetryContext.EMPTY);
		retryCount.set(0);
	}

	/**
	 * 服务器 tick 回调 — 处理延迟重试逻辑
	 * <br/>
	 * 当首次进入世界时配置可能未加载，或者 PB 的 BeeIngredientFactory 尚未就绪，
	 * 此时会在后续 tick 中重试应用配方修改。
	 * 由 {@link ProductiveBeesGenesis} 注册到事件总线。
	 * <p>
	 * 性能优化：使用 AtomicReference 快速检查，避免不必要的配置加载检查。
	 * 仅在 pendingRetry 为 true 时执行，正常游戏过程中此标志为 false，几乎零开销。
	 *
	 * @param reloader 配置就绪时执行的回调，参数为延迟重试上下文中存储的 recipeManager 和 registryAccess
	 */
	public static void onServerTick(BiConsumer<RecipeManager, HolderLookup.Provider> reloader) {
		// 快速路径：原子读取，无锁开销
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
	 * <p>
	 * CAS 清除策略：调用 reloader 后使用 {@code compareAndSet(ctx, EMPTY)} 清除自己读取的 ctx。
	 * 如果 reloader 调用了 scheduleRetry/rescheduleRetry 设置了新 ctx，CAS 会失败，
	 * 保留新 ctx 让下次 tick 继续重试；否则 CAS 成功，清除完成。
	 */
	private static void onServerTickSlowPath(BiConsumer<RecipeManager, HolderLookup.Provider> reloader) {
		// 单次快照读取保证 recipeManager 和 registryAccess 一致性
		PendingRetryContext ctx = pendingRetryContext.get();
		if (ctx == PendingRetryContext.EMPTY || ctx.recipeManager() == null) {
			// 使用 CAS 清除，避免覆盖其他线程刚设置的新 ctx
			pendingRetryContext.compareAndSet(ctx, PendingRetryContext.EMPTY);
			retryCount.set(0);
			return;
		}

		// 检查配置是否已加载
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			int currentRetry = retryCount.incrementAndGet();
			if (currentRetry >= MAX_RETRY_COUNT) {
				// P3-2: 重试次数超上限为严重错误（配方不会应用），使用 error 级别便于排查
				ProductiveBeesGenesis.LOGGER.error("配方重载重试次数超过上限({})，放弃应用万象创世配方修改", MAX_RETRY_COUNT);
				pendingRetryContext.compareAndSet(ctx, PendingRetryContext.EMPTY);
				retryCount.set(0);
			}
			return;
		}

		// 配置已加载，递增重试计数（用于限制 BeeIngredientFactory 未就绪等场景下的重试次数）
		// 如果 reloader 成功完成且未调用 rescheduleRetry，CAS 成功后 retryCount 会被重置为 0
		// 如果 reloader 调用了 rescheduleRetry（如 BeeIngredientFactory 未就绪），CAS 失败，retryCount 累积
		int currentRetry = retryCount.incrementAndGet();
		if (currentRetry >= MAX_RETRY_COUNT) {
			// P3-2: 重试次数超上限为严重错误（配方不会应用），使用 error 级别便于排查
			ProductiveBeesGenesis.LOGGER.error("配方重载重试次数超过上限({})，放弃应用万象创世配方修改", MAX_RETRY_COUNT);
			pendingRetryContext.compareAndSet(ctx, PendingRetryContext.EMPTY);
			retryCount.set(0);
			return;
		}

		// 执行配方修改
		try {
			ProductiveBeesGenesis.LOGGER.info("配置已就绪，执行延迟配方重载...");
			reloader.accept(ctx.recipeManager(), ctx.registryAccess());
			ProductiveBeesGenesis.LOGGER.info("延迟配方重载完成");
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("延迟配方重载失败", e);
		} finally {
			// CAS 清除自己读取的 ctx：
			// - 如果 reloader 调用了 scheduleRetry/rescheduleRetry 设置了新 ctx，CAS 失败，保留新 ctx
			// - 否则 CAS 成功，清除完成并重置 retryCount
			if (pendingRetryContext.compareAndSet(ctx, PendingRetryContext.EMPTY)) {
				retryCount.set(0);
			}
		}
	}
}
