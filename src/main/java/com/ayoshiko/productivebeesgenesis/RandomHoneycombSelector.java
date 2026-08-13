package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.ParametersAreNonnullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
	 * 随机蜜脾/蜜脾块选择与分配算法工具类
	 * <p>
	 * 从 {@link AbstractCombEventHandler} 抽取的纯算法逻辑，遵循单一职责原则（SRP）：
	 * <ul>
	 *   <li>单个/批量随机蜜脾与蜜脾块生成</li>
	 *   <li>预构建模板数组（高频场景下用 copy() 替代 new + set 组件）</li>
	 *   <li>聚合生成（高倍加速下将总数聚合为少量堆叠）</li>
	 *   <li>不同类型随机选择（Fisher-Yates 洗牌 / do-while 重试自适应）</li>
	 *   <li>均匀分配与随机分配（Stars-and-Bars 算法）</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：所有方法均为无状态静态方法，使用 {@link ThreadLocalRandom} 保证并发安全。
	 * 传入的 {@code cachedBeeTypes} 列表由调用方保证线程安全（如 {@link CopyOnWriteArrayList} 或 {@link List#of()}）。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
public final class RandomHoneycombSelector {

	/** 兜底蜜蜂类型（缓存为空或异常时使用） */
	public static final ResourceLocation FALLBACK_BEE_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "iron");

	private RandomHoneycombSelector() {
		// 工具类禁止实例化
	}

	// ========== 单个生成 ==========

	/**
	 * 从指定缓存中随机选取一个蜜蜂类型，生成蜜脾
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 随机蜜脾ItemStack
	 */
	public static ItemStack generateRandomHoneycomb(List<ResourceLocation> cachedBeeTypes) {
		try {
			if (cachedBeeTypes.isEmpty()) return createFallbackHoneycomb();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(cachedBeeTypes.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(randomIndex));
			return stack;
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 tick 路径异常刷屏
			LogThrottle.error("random_honeycomb_gen_list",
					"生成随机蜜脾时发生错误 (5秒内仅首条输出): {}", e.toString());
			return createFallbackHoneycomb();
		}
	}

	/**
	 * 从预构建的蜜脾模板数组中随机选取一个并复制，生成蜜脾
	 * <p>
	 * 相比 {@link #generateRandomHoneycomb(List)}，此方法避免在每次调用时
	 * 都创建新的 {@link ItemStack} 并设置数据组件，显著降低256倍加速等高频场景下的GC压力。
	 *
	 * @param templates 蜜脾模板数组（每个元素已预设 bee_type 组件）
	 * @return 随机蜜脾ItemStack
	 */
	public static ItemStack generateRandomHoneycomb(ItemStack[] templates) {
		try {
			if (templates == null || templates.length == 0) return createFallbackHoneycomb();
			return templates[ThreadLocalRandom.current().nextInt(templates.length)].copy();
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 tick 路径异常刷屏
			LogThrottle.error("random_honeycomb_gen_tpl",
					"生成随机蜜脾时发生错误 (5秒内仅首条输出): {}", e.toString());
			return createFallbackHoneycomb();
		}
	}

	/**
	 * 从指定缓存中随机选取一个蜜蜂类型，生成蜜脾块
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 随机蜜脾块ItemStack
	 */
	public static ItemStack generateRandomCombBlock(List<ResourceLocation> cachedBeeTypes) {
		try {
			if (cachedBeeTypes.isEmpty()) return createFallbackCombBlock();

			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
			int randomIndex = ThreadLocalRandom.current().nextInt(cachedBeeTypes.size());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(randomIndex));
			return stack;
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 tick 路径异常刷屏
			LogThrottle.error("random_comb_block_gen_list",
					"生成随机蜜脾块时发生错误 (5秒内仅首条输出): {}", e.toString());
			return createFallbackCombBlock();
		}
	}

	/**
	 * 从预构建的蜜脾块模板数组中随机选取一个并复制，生成蜜脾块
	 *
	 * @param templates 蜜脾块模板数组（每个元素已预设 bee_type 组件）
	 * @return 随机蜜脾块ItemStack
	 */
	public static ItemStack generateRandomCombBlock(ItemStack[] templates) {
		try {
			if (templates == null || templates.length == 0) return createFallbackCombBlock();
			return templates[ThreadLocalRandom.current().nextInt(templates.length)].copy();
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 tick 路径异常刷屏
			LogThrottle.error("random_comb_block_gen_tpl",
					"生成随机蜜脾块时发生错误 (5秒内仅首条输出): {}", e.toString());
			return createFallbackCombBlock();
		}
	}

	// ========== 批量生成 ==========

	/**
	 * 批量生成蜜脾：一次性生成 count 个随机蜜脾并追加到输出列表
	 * <p>
	 * 预分配索引数组，减少 {@link ThreadLocalRandom} 调用与扩容开销，适用于 256x
	 * 加速等高频调用场景。
	 *
	 * @param out       输出列表
	 * @param count     生成数量
	 * @param templates 蜜脾模板数组
	 */
	public static void appendRandomHoneycombs(List<ItemStack> out, int count, ItemStack[] templates) {
		if (count <= 0 || templates == null || templates.length == 0) return;
		int[] indices = new int[count];
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < count; i++) {
			indices[i] = random.nextInt(templates.length);
		}
		for (int idx : indices) {
			out.add(templates[idx].copy());
		}
	}

	/**
	 * 批量生成蜜脾并返回新列表
	 *
	 * @param count     生成数量
	 * @param templates 蜜脾模板数组
	 * @return 包含 count 个随机蜜脾的可变列表
	 */
	public static List<ItemStack> generateRandomHoneycombs(int count, ItemStack[] templates) {
		List<ItemStack> out = new ArrayList<>(Math.max(0, count));
		appendRandomHoneycombs(out, count, templates);
		return out;
	}

	/**
	 * 批量生成蜜脾块：一次性生成 count 个随机蜜脾块并追加到输出列表
	 *
	 * @param out       输出列表
	 * @param count     生成数量
	 * @param templates 蜜脾块模板数组
	 */
	public static void appendRandomCombBlocks(List<ItemStack> out, int count, ItemStack[] templates) {
		if (count <= 0 || templates == null || templates.length == 0) return;
		int[] indices = new int[count];
		ThreadLocalRandom random = ThreadLocalRandom.current();
		for (int i = 0; i < count; i++) {
			indices[i] = random.nextInt(templates.length);
		}
		for (int idx : indices) {
			out.add(templates[idx].copy());
		}
	}

	/**
	 * 批量生成蜜脾块并返回新列表
	 *
	 * @param count     生成数量
	 * @param templates 蜜脾块模板数组
	 * @return 包含 count 个随机蜜脾块的可变列表
	 */
	public static List<ItemStack> generateRandomCombBlocks(int count, ItemStack[] templates) {
		List<ItemStack> out = new ArrayList<>(Math.max(0, count));
		appendRandomCombBlocks(out, count, templates);
		return out;
	}

	// ========== 聚合生成 ==========

	/**
	 * 将总数聚合为少量堆叠（每种类型 1~2 个 stack，单个不超过 64）
	 * <p>
	 * 用于蜂箱高倍加速场景：先随机选取最多 9 种不同 bee_type，再把 totalCount
	 * 按 {@link #allocateEvenly} 均匀分配，避免生成大量 count=1 的 ItemStack。
	 * <p>
	 * 性能优化：优先使用 {@code templateByType} Map 做 O(1) 模板查找，避免对大模板数组
	 * 的 O(N) 线性扫描。Spark 显示 findTemplate 是 5.13ms 热点。
	 *
	 * @param totalCount        总数量
	 * @param baseItem          物品类型（蜜脾/蜜脾块）
	 * @param cachedBeeTypes    蜜蜂类型缓存
	 * @param templates         预构建模板数组（fallback 用，Map 缺失时回退）
	 * @param templateByType    模板 Map（ResourceLocation → ItemStack），不可变快照
	 * @param random            随机源
	 * @return 聚合后的 ItemStack 列表
	 */
	public static List<ItemStack> generateAggregatedStacks(
			int totalCount,
			Item baseItem,
			List<ResourceLocation> cachedBeeTypes,
			ItemStack[] templates,
			Map<ResourceLocation, ItemStack> templateByType,
			RandomSource random) {
		if (totalCount <= 0) {
			return List.of();
		}

		List<ResourceLocation> selectedTypes;
		if (cachedBeeTypes == null || cachedBeeTypes.isEmpty()) {
			selectedTypes = List.of(FALLBACK_BEE_TYPE);
		} else {
			int maxTypes = Math.min(9, totalCount);
			selectedTypes = selectDistinctBeeTypes(maxTypes, random, cachedBeeTypes);
		}
		if (selectedTypes.isEmpty()) {
			return List.of();
		}

		Map<ResourceLocation, Integer> allocation = allocateEvenly(totalCount, selectedTypes);
		List<ItemStack> result = new ArrayList<>(selectedTypes.size() * 2);
		for (ResourceLocation type : selectedTypes) {
			Integer count = allocation.get(type);
			if (count == null || count <= 0) {
				continue;
			}
			int remaining = count;
			// O(1) Map 查找优先；Map 为空（向后兼容）时回退到 O(N) 数组扫描
			ItemStack template = (templateByType != null && !templateByType.isEmpty())
					? templateByType.get(type)
					: findTemplate(templates, type);
			while (remaining > 0) {
				int stackSize = Math.min(64, remaining);
				if (template != null) {
					result.add(template.copyWithCount(stackSize));
				} else {
					ItemStack stack = new ItemStack(baseItem, stackSize);
					stack.set(ModDataComponents.BEE_TYPE.get(), type);
					result.add(stack);
				}
				remaining -= stackSize;
			}
		}
		return result;
	}

	/**
	 * 在模板数组中查找指定 bee_type 的模板
	 *
	 * @param templates 模板数组
	 * @param type      蜜蜂类型
	 * @return 对应模板，找不到返回 null
	 */
	private static ItemStack findTemplate(ItemStack[] templates, ResourceLocation type) {
		if (templates == null || type == null) {
			return null;
		}
		for (ItemStack template : templates) {
			if (template != null && type.equals(template.get(ModDataComponents.BEE_TYPE.get()))) {
				return template;
			}
		}
		return null;
	}

	// ========== 模板构建 ==========

	/**
	 * 为指定蜜蜂类型缓存构建蜜脾模板数组
	 * <p>
	 * 模板在缓存更新时一次性构建，避免高频生成时重复创建 ItemStack 和设置数据组件。
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 蜜脾模板数组
	 */
	public static ItemStack[] buildHoneycombTemplates(List<ResourceLocation> cachedBeeTypes) {
		if (cachedBeeTypes == null || cachedBeeTypes.isEmpty()) return new ItemStack[0];
		ItemStack[] templates = new ItemStack[cachedBeeTypes.size()];
		for (int i = 0; i < cachedBeeTypes.size(); i++) {
			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(i));
			templates[i] = stack;
		}
		return templates;
	}

	/**
	 * 为指定蜜蜂类型缓存构建蜜脾块模板数组
	 *
	 * @param cachedBeeTypes 蜜蜂类型缓存
	 * @return 蜜脾块模板数组
	 */
	public static ItemStack[] buildCombBlockTemplates(List<ResourceLocation> cachedBeeTypes) {
		if (cachedBeeTypes == null || cachedBeeTypes.isEmpty()) return new ItemStack[0];
		ItemStack[] templates = new ItemStack[cachedBeeTypes.size()];
		for (int i = 0; i < cachedBeeTypes.size(); i++) {
			ItemStack stack = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
			stack.set(ModDataComponents.BEE_TYPE.get(), cachedBeeTypes.get(i));
			templates[i] = stack;
		}
		return templates;
	}

	// ========== 随机类型选择与分配 ==========

	/**
	 * 从蜜蜂缓存中随机选取指定数量的不同类型（零拷贝优化）
	 * <p>
	 * 使用索引集合追踪已选元素，避免每次调用时完整拷贝列表。
	 * 缓存列表由调用方保证线程安全（如 {@link CopyOnWriteArrayList} 或 {@link List#of()}），无需加锁。
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
			int count, RandomSource random, List<ResourceLocation> cache) {
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
		Map<ResourceLocation, Integer> result = new HashMap<>(types.size() * 2);
		int buckets = types.size();
		if (buckets <= 0 || total <= 0) return result;

		int base = total / buckets;
		int remainder = total % buckets;

		for (int i = 0; i < buckets; i++) {
			result.put(types.get(i), base + (i < remainder ? 1 : 0));
		}
		return result;
	}

	/**
	 * 将 total 随机分配到各蜜蜂类型上（允许某些类型为0）
	 * <p>
	 * 使用 Stars-and-Bars 算法：生成 {@code types.size()-1} 个 [0, total) 范围内的随机切点，
	 * 排序后计算相邻切点之间的段长度，即为每种蜜蜂类型的分配数量。
	 * 保证所有值非负且总和严格等于 total。
	 *
	 * @param total  总数量
	 * @param types  蜜蜂类型列表
	 * @param random 随机源
	 * @return 类型→数量的映射
	 */
	public static Map<ResourceLocation, Integer> allocateRandomly(
		int total,
		List<ResourceLocation> types,
		RandomSource random
	) {
		Map<ResourceLocation, Integer> result = new HashMap<>(types.size() * 2);
		int buckets = types.size();
		if (buckets <= 0 || total <= 0) return result;

		// Stars-and-Bars：buckets 个桶需要 buckets-1 个切点，加上首尾 0 和 total
		int[] cuts = new int[buckets + 1];
		cuts[0] = 0;
		cuts[buckets] = total;
		for (int i = 1; i < buckets; i++) {
			cuts[i] = random.nextInt(total);
		}
		Arrays.sort(cuts);

		for (int i = 0; i < buckets; i++) {
			result.put(types.get(i), cuts[i + 1] - cuts[i]);
		}
		return result;
	}

	// ========== 兜底辅助 ==========

	/** 创建兜底蜜脾（缓存为空或异常时使用） */
	public static ItemStack createFallbackHoneycomb() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}

	/** 创建兜底蜜脾块（缓存为空或异常时使用） */
	public static ItemStack createFallbackCombBlock() {
		ItemStack fallback = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
		fallback.set(ModDataComponents.BEE_TYPE.get(), FALLBACK_BEE_TYPE);
		return fallback;
	}
}
