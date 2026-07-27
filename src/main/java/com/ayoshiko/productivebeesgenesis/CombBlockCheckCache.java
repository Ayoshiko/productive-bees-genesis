package com.ayoshiko.productivebeesgenesis;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 离心机空转拦截缓存工具类
 * <p>
 * 从 {@link AbstractCombEventHandler} 抽取的空转拦截逻辑，遵循单一职责原则（SRP）：
 * <ul>
 *   <li>按 handler 实例缓存"输出已满"判断结果，避免加速环境下重复扫描输出槽</li>
 *   <li>50ms 冷却窗口复用缓存，覆盖多次 tick 调用</li>
 *   <li>WeakHashMap 弱引用 key，BlockEntity 被 GC 时缓存条目自动回收</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：cacheMap 由调用方提供 {@link java.util.Collections#synchronizedMap} 包装的
 * {@link java.util.WeakHashMap}，复合操作（get + put）在 synchronized 块内执行，
 * 确保线程安全。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class CombBlockCheckCache {

	/** 空转拦截冷却时间：50ms ≈ 1游戏刻，加速环境下可覆盖多次tick调用 */
	public static final long BLOCK_CHECK_COOLDOWN_NS = 50_000_000L;

	/** shouldBlockOperation 异常日志节流器（ms 模式，5 秒冷却，避免加速环境刷屏） */
	private static final LogThrottle blockCheckErrorThrottle = new LogThrottle();

	/** hasOutputSpace 异常日志节流器（ms 模式，5 秒冷却，避免加速环境刷屏） */
	private static final LogThrottle outputSpaceErrorThrottle = new LogThrottle();

	/** 测试用ItemStack — Holder 类模式保证线程安全的延迟初始化 */
	private static final class TestOutputStackHolder {
		static final ItemStack INSTANCE = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
	}

	/** 单个handler的空转拦截缓存条目 */
	public static final class BlockCheckCache {
		volatile Item inputItem;
		volatile long checkTime;
		/** 仅缓存"已满"结果（安全保守策略：宁可多停1tick，不可漏检） */
		volatile Boolean blockedFull;
	}

	private CombBlockCheckCache() {
		// 工具类禁止实例化
	}

	/**
	 * 空转拦截统一检查方法（按 handler 实例缓存）
	 * <p>
	 * 当输入匹配目标判断条件且输出槽完全无空间时返回true（应阻止运行）。
	 * <ul>
	 *   <li>快速路径：通过目标判断条件过滤</li>
	 *   <li>冷却缓存：50ms内复用"已满"结果，消除加速环境下99%的重复调用</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：cacheMap 由调用方提供 {@link java.util.Collections#synchronizedMap} 包装的
	 * {@link java.util.WeakHashMap}，复合操作（get + put）在 synchronized 块内执行，
	 * 确保线程安全。WeakHashMap 的 key 为弱引用，BlockEntity 被 GC 时
	 * 缓存条目自动被回收，避免内存泄漏。
	 *
	 * @param handler  物品处理器
	 * @param isTarget 判断输入物品是否为目标物品的谓词
	 * @param cacheMap 按 handler 实例存储的缓存（必须是 synchronizedMap 包装的 WeakHashMap）
	 * @return 是否应阻止机器运行
	 */
	public static boolean checkBlockOperation(
			IItemHandlerModifiable handler,
			Predicate<Item> isTarget,
			Map<IItemHandlerModifiable, BlockCheckCache> cacheMap) {
		try {
			ItemStack input = handler.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT);
			Item inputItem = input.getItem();

			if (!isTarget.test(inputItem)) return false;

			long now = System.nanoTime();
			// synchronizedMap 的复合操作（get + put）需要外部同步
			synchronized (cacheMap) {
				BlockCheckCache cache = cacheMap.get(handler);
				if (cache != null
						&& inputItem == cache.inputItem
						&& Boolean.TRUE.equals(cache.blockedFull)
						&& (now - cache.checkTime) < BLOCK_CHECK_COOLDOWN_NS) {
					return true;
				}

				boolean blocked = !hasOutputSpace(handler);

				if (cache == null) {
					cache = new BlockCheckCache();
					cacheMap.put(handler, cache);
				}
				cache.inputItem = inputItem;
				cache.blockedFull = blocked ? Boolean.TRUE : null;
				cache.checkTime = now;

				return blocked;
			}
		} catch (Exception e) {
			// 实例节流（tryLogMs）+ 全局节流（LogThrottle.warn 静态方法，5 秒冷却）
			// 使用 LogThrottle.warn 静态方法封装 logger 引用，避免直接暴露模组主类 logger
			blockCheckErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed ->
					LogThrottle.warn("comb_block_check_error", "shouldBlockOperation 检查异常"
							+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), e));
			return false;
		}
	}

	/**
	 * 清理空转拦截缓存 — 服务器停止时调用，防止static Map持有handler实例导致内存泄漏
	 * <p>
	 * BLOCK_CHECK_CACHES按handler实例存储缓存，handler引用了BlockEntity，
	 * 若不清理，服务器关闭后这些BlockEntity无法被GC回收，造成内存泄漏。
	 * <p>
	 * 注：cacheMap 使用 WeakHashMap，BlockEntity 被 GC 时缓存条目会自动回收，
	 * 此方法作为兜底清理，确保服务器停止时立即释放所有缓存。
	 *
	 * @param cacheMap 待清理的缓存Map
	 */
	public static void clearCaches(Map<IItemHandlerModifiable, BlockCheckCache> cacheMap) {
		synchronized (cacheMap) {
			cacheMap.clear();
		}
	}

	/**
	 * 检查离心机输出槽是否有剩余空间
	 * <p>
	 * 使用 Holder 类模式保证测试 ItemStack 线程安全的延迟初始化。
	 *
	 * @param invHandler 物品处理器
	 * @return 是否有输出空间
	 */
	public static boolean hasOutputSpace(IItemHandlerModifiable invHandler) {
		try {
			if (invHandler instanceof InventoryHandlerHelper.BlockEntityItemStackHandler outputHandler) {
				return outputHandler.canFitStacks(List.of(TestOutputStackHolder.INSTANCE));
			}
			return false;
		} catch (Exception e) {
			// 实例节流（tryLogMs）+ 全局节流（LogThrottle.warn 静态方法，5 秒冷却）
			// 使用 LogThrottle.warn 静态方法封装 logger 引用，避免直接暴露模组主类 logger
			outputSpaceErrorThrottle.tryLogMs(System.currentTimeMillis(), suppressed ->
					LogThrottle.warn("comb_output_space_error", "检查输出空间时异常，回退为 false"
							+ (suppressed > 0 ? " (抑制 " + suppressed + " 次)" : ""), e));
			return false;
		}
	}
}
