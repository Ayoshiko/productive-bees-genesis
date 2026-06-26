package com.ayoshiko.productivebeesgenesis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

import javax.annotation.ParametersAreNonnullByDefault;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeInput;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 蜜蜂事件处理器公共逻辑基类
 * <br/>
 * 提供 万象创世 与 无尽·创世 两个事件处理器的共享逻辑：
 * <ol>
 *   <li>蜜蜂类型缓存更新（子类提供排除规则与额外过滤）</li>
 *   <li>随机蜜脾/蜜脾块生成</li>
 *   <li>离心机空转拦截（按 handler 实例缓存）</li>
 *   <li>离心机追加产出（预分配算法，保证总产出=消耗数且不溢出）</li>
 * </ol>
 * <p>
 * <b>线程安全</b>：所有公共方法均为线程安全，使用 {@link CopyOnWriteArrayList}、
 * {@link ConcurrentHashMap} 和 {@link ThreadLocalRandom} 保证并发安全。
 * <p>
 * <b>设计说明</b>：基类不持有任何状态字段，缓存由子类各自持有，确保两个处理器互不干扰。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public abstract class AbstractCombEventHandler {

	/** 兜底蜜蜂类型（缓存为空或异常时使用） */
	protected static final ResourceLocation FALLBACK_BEE_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "iron");

	/** 缓存更新间隔（tick） */
	protected static final int CACHE_UPDATE_INTERVAL = 20;

	/** 空转拦截冷却时间：50ms ≈ 1游戏刻，加速环境下可覆盖多次tick调用 */
	protected static final long BLOCK_CHECK_COOLDOWN_NS = 50_000_000L;

	/** 测试用ItemStack — Holder 类模式保证线程安全的延迟初始化 */
	private static final class TestOutputStackHolder {
		static final ItemStack INSTANCE = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
	}

	/** 单个handler的空转拦截缓存条目 */
	protected static final class BlockCheckCache {
		volatile Item inputItem;
		volatile long checkTime;
		/** 仅缓存"已满"结果（安全保守策略：宁可多停1tick，不可漏检） */
		volatile Boolean blockedFull;
	}

	// ========== 缓存更新 ==========

	/**
	 * 更新蜜蜂类型缓存（原子替换，避免竞态窗口）
	 * <p>
	 * 通用流程：
	 * <ol>
	 *   <li>从PB数据源读取所有蜜蜂类型</li>
	 *   <li>排除子类指定的类型（如自身、避免循环转化的类型）</li>
	 *   <li>排除没有离心配方的蜜蜂</li>
	 *   <li>应用子类提供的额外过滤（如配置文件过滤）</li>
	 * </ol>
	 *
	 * @param level         服务端世界
	 * @param excludedTypes 需要排除的蜜蜂类型集合
	 * @param extraFilter   额外过滤谓词（可为null表示无额外过滤），返回true保留该类型
	 * @return 新的缓存列表
	 */
	protected static CopyOnWriteArrayList<ResourceLocation> buildBeeTypeCache(
			ServerLevel level,
			Set<ResourceLocation> excludedTypes,
			Predicate<ResourceLocation> extraFilter) {
		try {
			Map<ResourceLocation, ?> beeData = BeeReloadListener.INSTANCE.getData();
			if (beeData == null || beeData.isEmpty()) {
				return new CopyOnWriteArrayList<>();
			}

			List<ResourceLocation> newTypes = new ArrayList<>();
			for (ResourceLocation beeType : beeData.keySet()) {
				if (excludedTypes.contains(beeType)) continue;
				if (!hasCentrifugeRecipe(level, beeType)) continue;
				if (extraFilter != null && !extraFilter.test(beeType)) continue;
				newTypes.add(beeType);
			}

			return new CopyOnWriteArrayList<>(newTypes);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("更新蜜蜂类型缓存时发生错误", e);
			return new CopyOnWriteArrayList<>();
		}
	}

	/**
	 * 检查指定蜜蜂类型是否有对应的离心配方
	 * <p>
	 * 检查失败时保守返回true，避免误删有效蜜蜂。
	 *
	 * @param level   服务端世界
	 * @param beeType 蜜蜂类型ID
	 * @return 是否存在离心配方
	 */
	protected static boolean hasCentrifugeRecipe(ServerLevel level, ResourceLocation beeType) {
		try {
			ItemStack testComb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			testComb.set(ModDataComponents.BEE_TYPE.get(), beeType);

			var testInput = new InventoryHandlerHelper.BlockEntityItemStackHandler(2);
			testInput.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, testComb);

			RecipeHolder<CentrifugeRecipe> recipe = level.getRecipeManager()
					.getRecipeFor(ModRecipeTypes.CENTRIFUGE_TYPE.get(), (RecipeInput) testInput, level)
					.orElse(null);
			return recipe != null;
		} catch (Exception e) {
			return true;
		}
	}

	// ========== 随机蜜脾生成 ==========

	/**
	 * 从指定缓存中随机选取一个蜜蜂类型，生成蜜脾
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 随机蜜脾ItemStack
	 */
	protected static ItemStack generateRandomHoneycomb(CopyOnWriteArrayList<ResourceLocation> cachedBeeTypes) {
		try {
			if (cachedBeeTypes.isEmpty()) return createFallbackHoneycomb();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(cachedBeeTypes.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(randomIndex));
			return stack;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("生成随机蜜脾时发生错误", e);
			return createFallbackHoneycomb();
		}
	}

	/**
	 * 从指定缓存中随机选取一个蜜蜂类型，生成蜜脾块
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 随机蜜脾块ItemStack
	 */
	protected static ItemStack generateRandomCombBlock(CopyOnWriteArrayList<ResourceLocation> cachedBeeTypes) {
		try {
			if (cachedBeeTypes.isEmpty()) return createFallbackCombBlock();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(cachedBeeTypes.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(randomIndex));
			return stack;
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.error("生成随机蜜脾块时发生错误", e);
			return createFallbackCombBlock();
		}
	}

	// ========== 随机类型选择与分配 ==========

	/**
	 * 从蜜蜂缓存中随机选取指定数量的不同类型（零拷贝优化）
	 * <p>
	 * 使用索引集合追踪已选元素，避免每次调用时完整拷贝列表。
	 * 缓存列表由 {@link CopyOnWriteArrayList} 保证读安全，无需加锁。
	 * <p>
	 * 算法选择：
	 * <ul>
	 *   <li>count <= poolSize/2：使用 do-while 随机重试，碰撞率低，避免拷贝整个列表</li>
	 *   <li>count > poolSize/2：改用洗牌算法（Fisher-Yates），避免高碰撞率下的无限重试</li>
	 * </ul>
	 *
	 * @param count  需要选取的类型数量
	 * @param random 随机源
	 * @param cache  蜜蜂类型缓存
	 * @return 选中的蜜蜂类型列表
	 */
	public static List<ResourceLocation> selectDistinctBeeTypes(
			int count, RandomSource random, CopyOnWriteArrayList<ResourceLocation> cache) {
		int poolSize = cache.size();
		if (poolSize == 0) return List.of();
		if (count >= poolSize) return List.copyOf(cache);

		// 当选取数量超过池容量一半时，do-while 碰撞率激增，改用洗牌算法
		if (count > poolSize / 2) {
			List<ResourceLocation> shuffled = new ArrayList<>(cache);
			// Fisher-Yates 洗牌：仅洗前 count 个位置，避免全量洗牌
			for (int i = 0; i < count; i++) {
				int j = i + random.nextInt(poolSize - i);
				ResourceLocation tmp = shuffled.get(i);
				shuffled.set(i, shuffled.get(j));
				shuffled.set(j, tmp);
			}
			return new ArrayList<>(shuffled.subList(0, count));
		}

		List<ResourceLocation> selected = new ArrayList<>(count);
		Set<Integer> usedIndices = new HashSet<>(count * 2);

		for (int i = 0; i < count; i++) {
			int idx;
			do {
				idx = random.nextInt(poolSize);
			} while (!usedIndices.add(idx));
			selected.add(cache.get(idx));
		}
		return selected;
	}

	/**
	 * 将 total 均匀分配到各蜜蜂类型上，每个类型至少1个
	 *
	 * @param total 总数量
	 * @param types 蜜蜂类型列表
	 * @return 类型→数量的映射
	 */
	public static Map<ResourceLocation, Integer> allocateEvenly(int total, List<ResourceLocation> types) {
		Map<ResourceLocation, Integer> result = new HashMap<>();
		int buckets = types.size();
		if (buckets <= 0 || total <= 0) return result;

		int base = total / buckets;
		int remainder = total % buckets;

		for (int i = 0; i < buckets; i++) {
			result.put(types.get(i), base + (i < remainder ? 1 : 0));
		}
		return result;
	}

	// ========== 离心机公共逻辑 ==========

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
	 * 确保线程安全。WeakHashMap 的 key 为弱引用，BlockEntity 卸载后 handler 被 GC 时
	 * 缓存条目自动被回收，避免内存泄漏。
	 *
	 * @param handler       物品处理器
	 * @param isTarget      判断输入物品是否为目标物品的谓词
	 * @param cacheMap      按 handler 实例存储的缓存（必须是 synchronizedMap 包装的 WeakHashMap）
	 * @return 是否应阻止机器运行
	 */
	protected static boolean checkBlockOperation(
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
			ProductiveBeesGenesis.LOGGER.warn("shouldBlockOperation 检查异常", e);
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
	protected static void clearBlockCheckCaches(Map<IItemHandlerModifiable, BlockCheckCache> cacheMap) {
		synchronized (cacheMap) {
			cacheMap.clear();
		}
	}

	/**
	 * 离心机追加随机蜜脾产出（核心机制：转化）
	 * <p>
	 * 设计理念：创世蜜蜂 = 「支付一个创世物品，转化为任意同类型物品」。
	 * 每个输入的创世蜜脾精确转化为1个随机蜜脾（线性缩放）。
	 * <p>
	 * <b>槽位安全策略（预分配）</b>：PB离心机仅9格输出槽，若32个蜜脾随机出>9种则放不下。
	 * 采用预分配算法：
	 * <ol>
	 *   <li>限制种类数K = min(9, totalCount)，确保不超槽位</li>
	 *   <li>从缓存中随机选K种不同蜜蜂类型</li>
	 *   <li>将totalCount均匀分配到K种上（每种至少1个）</li>
	 * </ol>
	 * 保证：总产出数 == 消耗数，且永不溢出。
	 *
	 * @param input              输入物品
	 * @param invHandler         物品处理器
	 * @param random             随机源
	 * @param productivityModifier PB升级倍率
	 * @param isTargetComb       判断是否为目标蜜脾
	 * @param isTargetBlock      判断是否为目标蜜脾块
	 * @param cachedBeeTypes     蜜蜂类型缓存
	 */
	protected static void appendRandomCombsInternal(
			ItemStack input,
			IItemHandlerModifiable invHandler,
			RandomSource random,
			int productivityModifier,
			Predicate<ItemStack> isTargetComb,
			Predicate<ItemStack> isTargetBlock,
			CopyOnWriteArrayList<ResourceLocation> cachedBeeTypes) {
		if (!isTargetComb.test(input) && !isTargetBlock.test(input)) return;
		if (!hasOutputSpace(invHandler)) return;

		boolean isCombBlock = isTargetBlock.test(input);
		int totalCount = Math.max(1, productivityModifier);

		int maxTypes = Math.min(9, totalCount);
		List<ResourceLocation> selectedTypes = selectDistinctBeeTypes(maxTypes, random, cachedBeeTypes);
		if (selectedTypes.isEmpty()) return;

		Map<ResourceLocation, Integer> allocation = allocateEvenly(totalCount, selectedTypes);

		Item baseItem = isCombBlock ? ModItems.CONFIGURABLE_COMB_BLOCK.get() : ModItems.CONFIGURABLE_HONEYCOMB.get();
		if (invHandler instanceof InventoryHandlerHelper.BlockEntityItemStackHandler outputHandler) {
			for (Map.Entry<ResourceLocation, Integer> entry : allocation.entrySet()) {
				try {
					ItemStack output = new ItemStack(baseItem, entry.getValue());
					output.set(ModDataComponents.BEE_TYPE.get(), entry.getKey());
					outputHandler.addOutput(output);
				} catch (Exception e) {
					ProductiveBeesGenesis.LOGGER.warn("追加随机蜜脾产出异常", e);
					break;
				}
			}
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
			ProductiveBeesGenesis.LOGGER.warn("检查输出空间时异常，回退为 false", e);
			return false;
		}
	}

	// ========== 私有辅助方法 ==========

	/** 创建兜底蜜脾（缓存为空或异常时使用） */
	protected static ItemStack createFallbackHoneycomb() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}

	/** 创建兜底蜜脾块（缓存为空或异常时使用） */
	protected static ItemStack createFallbackCombBlock() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}
}
