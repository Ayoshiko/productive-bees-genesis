package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.CompiledBeeTypeFilter;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
	 * 万象创世蜜蜂类型缓存管理器
	 * <br/>
	 * 从 {@link MyriadCreationsEventHandler} 抽离，负责：
	 * <ul>
	 *   <li>蜜蜂类型缓存的定期刷新（从 PB 数据源读取，应用配置过滤）</li>
	 *   <li>预构建蜜脾/蜜脾块模板数组（避免高频路径重复构造 ItemStack）</li>
	 *   <li>缓存的失效与清理（服务器停止、配置重载时）</li>
	 * </ul>
	 * <p>
	 * <b>线程安全</b>：
	 * <ul>
	 *   <li>{@link #beeTypeCacheSnapshot} 为 volatile 引用，通过不可变 {@link BeeTypeCacheSnapshot} 原子替换</li>
	 *   <li>读写模式：服务端 tick 线程单写，GUI 线程/Mixin 线程多读</li>
	 * </ul>
	 */
public final class MyriadBeeTypeCache {

	/**
	 * 不可变快照：封装蜜蜂类型缓存和预构建模板数组
	 * <p>
	 * 通过单一 volatile 引用原子替换，避免多 volatile 字段在 rebuild 期间出现
	 * "类型已更新但模板仍为旧值"的不一致状态。
	 * <p>
	 * <b>不可变性约束</b>：
	 * <ul>
	 * <li>{@code beeTypes} 为 {@link List} 类型，EMPTY 快照使用 {@link List#of()} 真正不可变</li>
	 * <li>实际发布时使用 {@link List#copyOf(java.util.Collection)}，防止调用方修改共享类型表</li>
	 * <li>{@code honeycombTemplates} / {@code combBlockTemplates} 为 {@link ItemStack} 数组，
	 *       <b>调用方不得修改数组元素</b>，必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</li>
	 * <li>{@code honeycombTemplateByType} / {@code combBlockTemplateByType} 为 immutable Map，
	 *       提供 O(1) ResourceLocation → ItemStack 查找，避免 generateAggregatedStacks 中
	 *       O(N) 线性扫描 templates 数组（Spark 显示此处 5.13ms 热点）</li>
	 * </ul>
	 */
	public record BeeTypeCacheSnapshot(
			List<ResourceLocation> beeTypes,
			ItemStack[] honeycombTemplates,
			ItemStack[] combBlockTemplates,
			Map<ResourceLocation, ItemStack> honeycombTemplateByType,
			Map<ResourceLocation, ItemStack> combBlockTemplateByType,
			Map<ResourceLocation, List<ItemStack>> honeycombVariants,
			Map<ResourceLocation, List<ItemStack>> combBlockVariants,
			List<ResourceLocation> combBlockBeeTypes) {
		// EMPTY 使用 List.of()，避免共享可变列表实例。
		static final BeeTypeCacheSnapshot EMPTY = new BeeTypeCacheSnapshot(
				List.of(), new ItemStack[0], new ItemStack[0], Map.of(), Map.of(), Map.of(), Map.of(), List.of());

		/** 每次事务固定每蜂种的一个真实模板，预检、重试与提交必须共用返回值。 */
		public Map<ResourceLocation, ItemStack> selectTemplates(List<ResourceLocation> selected,
				boolean blocks, net.minecraft.util.RandomSource random) {
			Map<ResourceLocation, List<ItemStack>> variants = blocks ? combBlockVariants : honeycombVariants;
			Map<ResourceLocation, ItemStack> result = new HashMap<>(selected.size());
			for (ResourceLocation type : selected) {
				List<ItemStack> choices = variants.get(type);
				if (choices == null || choices.isEmpty()) continue;
				result.put(type, choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size())));
			}
			return result;
		}
	}

	/** 当前快照 — volatile 引用保证原子替换 */
	private static volatile BeeTypeCacheSnapshot beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;

	/**
	 * 预热完成标志
	 * <br/>
	 * {@code false} 时 {@link #onServerTick()} 每 tick 都触发缓存构建尝试；
	 * {@code true} 时不再周期扫描，直到配置或数据重载调用 {@link #invalidate()}。
	 * <p>
	 * BeeReloadListener 数据可用后，无论过滤结果是否为空，都发布快照并完成预热。
	 */
	private static volatile boolean warmupComplete = false;
	private static long nextWarmupTick = Long.MIN_VALUE;

	/** "缓存未就绪"日志冷却器（info 级别，5 秒冷却） */
	private static final LogThrottle cacheNotReadyThrottle = new LogThrottle(100L);

	/** "空白名单"日志冷却器（info 级别，5 秒冷却） */
	private static final LogThrottle emptyWhitelistThrottle = new LogThrottle(100L);

	/** "过滤结果为空"日志冷却器（warn 级别，10 秒冷却） */
	private static final LogThrottle configFilterThrottle = new LogThrottle(200L);

	private MyriadBeeTypeCache() {}

	/** 读取当前快照（volatile 读保证可见性） */
	public static BeeTypeCacheSnapshot snapshot() {
		return beeTypeCacheSnapshot;
	}

	/**
	 * 兼容性访问：返回当前的蜜蜂类型列表
	 * <br/>
	 * 返回的列表不可修改：EMPTY 快照返回 {@link List#of()}（不可变），
	 * 实际缓存也通过 {@link List#copyOf(java.util.Collection)} 发布为不可变列表。
	 */
	public static List<ResourceLocation> cachedBeeTypes() {
		return snapshot().beeTypes;
	}

	public static List<ResourceLocation> cachedBeeTypes(boolean blocks) {
		BeeTypeCacheSnapshot current = snapshot();
		return blocks ? current.combBlockBeeTypes : current.beeTypes;
	}

	/**
	 * 兼容性访问：返回当前的蜜脾模板数组
	 * <br/>
	 * <b>调用方必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</b>，
	 * 直接修改数组元素会污染缓存模板，导致后续所有 copy() 携带错误数据。
	 */
	public static ItemStack[] cachedHoneycombTemplates() {
		return snapshot().honeycombTemplates;
	}

	/**
	 * 兼容性访问：返回当前的蜜脾块模板数组
	 * <br/>
	 * <b>调用方必须通过 {@link ItemStack#copy()} 获取独立副本后再修改</b>，
	 * 直接修改数组元素会污染缓存模板，导致后续所有 copy() 携带错误数据。
	 */
	public static ItemStack[] cachedCombBlockTemplates() {
		return snapshot().combBlockTemplates;
	}

	/**
	 * 服务器 tick — 检查是否有事件驱动的重建请求
	 * <br/>
	 * 服务器启动后首次获得完整配方数据前，最多每 20 tick 尝试更新。
	 * 数据就绪并发布结果后返回 {@code false}，因此正常蜂箱工作期间不会读取配置、
	 * 扫描蜜蜂注册数据或查询配方。配置、标签或配方重载会调用 {@link #invalidate()}
	 * 重新进入待构建状态。
	 *
	 * @return true 如果本次 tick 触发了缓存更新检查（无论是否实际重建）
	 */
	public static boolean onServerTick() {
		return !warmupComplete;
	}

	/**
	 * 查询预热阶段是否完成
	 * <br/>
	 * 供 {@link MyriadCreationsEventHandler#isBeeTypeCacheWarmupComplete()} 转发，
	 * 用于在缓存为空时区分"蜜蜂数据未就绪"与"已发布的空过滤结果"。
	 *
	 * @return true 如果预热阶段已完成
	 */
	public static boolean isWarmupComplete() {
		return warmupComplete;
	}

	/**
	 * 更新蜜蜂类型缓存
	 * <p>
	 * 过滤逻辑：
	 * <ol>
	 *   <li>排除万象创世自身</li>
	 *   <li>保留蜂箱配方中的真实蜜脾，包括须在其它机器处理的蜜脾</li>
	 *   <li>应用配置文件的黑白名单过滤</li>
	 * </ol>
	 * <p>
	 * <b>预热与空缓存区分</b>：
	 * <ul>
	 *   <li>BeeReloadListener 未加载 — info 日志"缓存未就绪"，每 20 tick 重试，不更新 snapshot</li>
	 *   <li>BeeReloadListener 已加载 — 发布最新快照；合法的空过滤结果同样会覆盖旧快照</li>
	 * </ul>
	 *
	 * @param level 服务端世界
	 */
	public static void updateBeeTypeCache(ServerLevel level) {
		long currentTick = level.getGameTime();
		if (!warmupComplete && currentTick < nextWarmupTick) return;
		nextWarmupTick = currentTick + 20L;
		if (!AbstractCombEventHandler.isBeeReloadListenerReady()) {
			cacheNotReadyThrottle.tryLog(currentTick, suppressed ->
					DevLog.info("bee_cache", "蜜蜂数据尚未就绪，万象创世类型缓存将稍后重试"
							+ "（抑制 {} 次类似日志）", suppressed));
			return;
		}

		Set<ResourceLocation> excluded = Set.of(PBConstants.MYRIADCREATIONS_TYPE);

		// 每次重建只编译一次规则，统一处理 TOML 中的空格、重复项与空列表语义。
		ModConfig.FilterMode mode = ModConfig.SERVER.myriadCreationsFilterMode.get();
		List<? extends String> filteredList = ModConfig.SERVER.myriadCreationsFilteredBeeTypes.get();
		CompiledBeeTypeFilter filter = CompiledBeeTypeFilter.compile(mode.name(), filteredList);

		List<ResourceLocation> newCache = AbstractCombEventHandler.buildBeeTypeCache(
				level, excluded, beeType -> filter.allows(beeType.toString()));

		if (newCache.isEmpty()) {
			logEmptyResult(filter, currentTick);
		}
		publishSnapshot(newCache, level);
	}

	/** 原子发布类型、模板和索引；空列表也必须覆盖旧快照并通知下游缓存。 */
	private static void publishSnapshot(List<ResourceLocation> beeTypes, ServerLevel level) {
		List<ResourceLocation> resolvedTypes = new ArrayList<>();
		List<ResourceLocation> blockTypes = new ArrayList<>();
		List<ItemStack> honeycombs = new ArrayList<>();
		List<ItemStack> blocks = new ArrayList<>();
		Map<ResourceLocation, ItemStack> honeycombByType = new HashMap<>();
		Map<ResourceLocation, ItemStack> blockByType = new HashMap<>();
		Map<ResourceLocation, List<ItemStack>> honeycombVariants = new HashMap<>();
		Map<ResourceLocation, List<ItemStack>> blockVariants = new HashMap<>();
		for (ResourceLocation type : beeTypes) {
			List<ItemStack> variants = resolveHoneycombTemplates(level, type);
			if (variants.isEmpty()) continue;
			resolvedTypes.add(type);
			honeycombByType.put(type, variants.getFirst());
			honeycombVariants.put(type, variants);
			List<ItemStack> mappedBlocks = new ArrayList<>();
			for (ItemStack comb : variants) {
				addDistinct(honeycombs, comb);
				ItemStack block = RandomHoneycombSelector.buildCombBlockTemplate(type, comb);
				if (!block.isEmpty()) addDistinct(mappedBlocks, block);
			}
			if (!mappedBlocks.isEmpty()) {
				blockTypes.add(type);
				blockByType.put(type, mappedBlocks.getFirst());
				blockVariants.put(type, List.copyOf(mappedBlocks));
				for (ItemStack block : mappedBlocks) addDistinct(blocks, block);
			}
		}
		List<ResourceLocation> immutableTypes = List.copyOf(resolvedTypes);
		beeTypeCacheSnapshot = new BeeTypeCacheSnapshot(immutableTypes,
				honeycombs.toArray(ItemStack[]::new), blocks.toArray(ItemStack[]::new),
				Map.copyOf(honeycombByType), Map.copyOf(blockByType),
				Map.copyOf(honeycombVariants), Map.copyOf(blockVariants), List.copyOf(blockTypes));
		warmupComplete = BeeInfoHelper.isProduceIndexComplete();
		MyriadSelectionCache.onBeeTypesUpdated();
		WeightedTypeSelector.getInstance().onTypesUpdated(immutableTypes);
	}

	/** 枚举同蜂种的所有配方，按完整组件去重；缺失时等待重载，不伪造蜜脾。 */
	private static List<ItemStack> resolveHoneycombTemplates(ServerLevel level, ResourceLocation beeType) {
		List<ItemStack> result = new ArrayList<>();
		for (ItemStack output : BeeInfoHelper.getAllBeeProduce(level, beeType)) {
			if (isHoneycomb(output)) {
				addDistinct(result, RandomHoneycombSelector.normalizeHoneycombTemplate(beeType, output));
			}
		}
		return List.copyOf(result);
	}

	private static void addDistinct(List<ItemStack> templates, ItemStack candidate) {
		for (ItemStack template : templates) {
			if (ItemStack.isSameItemSameComponents(template, candidate)) return;
		}
		templates.add(candidate.copyWithCount(1));
	}

	/** 原生蜜脾及数据包声明的通用蜜脾标签均可参与万象生产。 */
	static boolean isHoneycomb(ItemStack stack) {
		return !stack.isEmpty() && (stack.getItem() instanceof net.minecraft.world.item.HoneycombItem
				|| stack.is(HONEYCOMBS));
	}

	private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> HONEYCOMBS =
			net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
					ResourceLocation.fromNamespaceAndPath("c", "honeycombs"));

	private static void logEmptyResult(CompiledBeeTypeFilter filter, long currentTick) {
		if (filter.isEmptyWhitelist()) {
			emptyWhitelistThrottle.tryLog(currentTick, suppressed ->
					DevLog.info("bee_cache", "万象创世白名单为空，已发布空类型缓存"
							+ "（抑制 {} 次类似日志）", suppressed));
			return;
		}
		configFilterThrottle.tryLog(currentTick, suppressed ->
				DevLog.warn("bee_cache", "万象创世过滤后没有可转化蜜蜂（mode={}, filterCount={}）"
							+ "，已发布空类型缓存（抑制 {} 次类似警告）",
						filter.modeName(), filter.entryCount(), suppressed));
	}

	/**
	 * 失效缓存（配置重载时调用）
	 * <br/>
	 * 使基于配置过滤的蜜蜂类型缓存立即失效，下次 tick 强制重建。
	 * <p>
	 * 同时清空已发布快照与下游选择器，使收紧后的规则不会继续使用旧类型；
	 * 下一 tick 起重试，直到 BeeReloadListener 数据就绪并发布新结果。
	 */
	public static void invalidate() {
		clearPublishedSnapshot();
		warmupComplete = false;
	}

	/**
	 * 清理所有缓存（服务器停止时调用）
	 * <br/>
	 * 重置所有静态字段到初始状态，防止跨存档数据泄漏。
	 */
	public static void clearAll() {
		clearPublishedSnapshot();
		warmupComplete = false;
	}

	/** Clears every published view before a rebuild so no consumer can use stale types. */
	private static void clearPublishedSnapshot() {
		nextWarmupTick = Long.MIN_VALUE;
		beeTypeCacheSnapshot = BeeTypeCacheSnapshot.EMPTY;
		MyriadSelectionCache.invalidate();
		WeightedTypeSelector.getInstance().onTypesUpdated(List.of());
	}
}
