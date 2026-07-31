package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.WeightedAllocation;
import com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * 蜜蜂产出处理器
 * <br/>
 * 负责查询蜜蜂产出配方并将产物分发到输出槽与流体罐。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅处理蜜蜂产出查询与产物分发，不涉及 tick 编排或槽位管理</li>
 *   <li>依赖倒置：通过 {@link ApiaryUpgradeHandler} 接口获取升级倍率，不直接访问升级组件</li>
 * </ul>
 * <p>
 * 线程安全：双缓存策略 — 静态 {@link ConcurrentHashMap}（正缓存）+ LRU {@link LinkedHashMap}（负缓存，容量 256），
 * 所有方块实体共享同一份缓存（相同 EntityType 的产出配方数据全局一致）。
 * 方块实体在服务端单线程执行，ConcurrentHashMap / synchronizedMap 提供防御性保护。
 * <p>
 * Task 16 性能优化：
 * <ul>
 *   <li>16.1 提供批量产出 API（{@link #processBatchProduce}），支持同组蜜蜂共享配方查询结果</li>
 *   <li>16.3 缓存改为 static，由 {@link #invalidateCache()} 统一失效（配方重载时调用）</li>
 *   <li>16.5 批量分发时合并相同物品栈，减少 insertItem 调用次数</li>
 * </ul>
 */
public class BeeProduceProcessor {

	/**
	 * 万象创世随机蜜脾/蜜脾块的 totalCount 上限
	 * <br/>
	 * Task 7 起，此常量语义从"ItemStack 数量上限"改为"totalCount 上限"：
	 * 传入 {@link MyriadAggregatedStacksBuilder#buildAggregatedHoneycombs} /
	 * {@link MyriadAggregatedStacksBuilder#buildAggregatedCombBlocks} 的 totalCount 参数，
	 * 由 {@link WeightedAllocation#allocateByWeight} 分配到 3 种 bee_type，
	 * 实际产出 ItemStack 数量 ≤ 9（每种 bee_type 至多 ceil(576/3/64)=3 个聚合 stack）。
	 * <p>
	 * 上限 576 = 9 输出槽 × 64 堆叠上限，保护输出槽总容量不被高倍加速场景击穿。
	 */
	private static final int MYRIAD_RANDOM_CAP = 576;

	/**
	 * mergeStacks 调用阈值 — 仅在 stacks.size() > 此值时才调用 mergeStacks 合并相同物品栈
	 * <br/>
	 * 设计原理：小批量场景（PB 原版蜜蜂 2-3 stack、万象创世 9 stack）下，
	 * ItemStackMergeHelper.mergeStacks 的 hashCode 预分组开销大于合并收益，
	 * 直接分发更高效。仅在批量场景（>8 stack）才走合并路径。
	 */
	private static final int MERGE_THRESHOLD = 8;

	/**
	 * 蜜蜂产出配方缓存（静态共享）
	 * <br/>
	 * Key: 蜜蜂类型键 ResourceLocation（如 productivebees:iron，由 {@link BeeNbtHelper#resolveBeeTypeKey} 解析）
	 * Value: 该蜜蜂的配方输出表（ItemStack -> ChancedOutput，原始数据不执行概率检查）
	 * <p>
	 * 模块 2+3：缓存类型从 {@code List<ItemStack>} 改为 {@code Map<ItemStack, ChancedOutput>}，
	 * 缓存配方原始数据而非随机结果。概率判定统一由 {@link BeeProduceBatchSampler} 在采样阶段处理，
	 * 避免原 {@code chancedOutput.max()} 硬编码导致概率产物变必产物。
	 * <p>
	 * 静态化原因：相同蜜蜂类型的产出配方数据全局一致，所有方块实体共享
	 * 同一份缓存避免 N 个蜂箱各存一份的内存浪费。
	 * <p>
	 * 使用 ResourceLocation 而非 EntityType 作为键的原因：
	 * ConfigurableBee 的 EntityType 永远是 productivebees:configurable_bee，
	 * 但具体蜜蜂类型（如 productivebees:iron）存储在 beeData 的 "type" 字段中。
	 * 使用 EntityType 作为键会导致所有 ConfigurableBee 共享同一份（错误的）配方。
	 * <p>
	 * 缓存失效通过 {@link #invalidateCache()} 在配方重载时清空，
	 * 由 {@link ProductiveBeesGenesis#onTagsReload} 统一调用。
	 */
	private static final Map<ResourceLocation, Map<ItemStack, ChancedOutput>> produceCache =
		Collections.synchronizedMap(new LinkedHashMap<ResourceLocation, Map<ItemStack, ChancedOutput>>(512, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Map<ItemStack, ChancedOutput>> eldest) {
				return size() > 512;
			}
		});

	/** 负缓存最大条目数（与 PbRecipeFinder.MAX_RECIPE_CACHE_SIZE 对齐） */
	private static final int MAX_NEGATIVE_CACHE_SIZE = 256;

	/**
	 * 蜜蜂无产出配方负缓存（静态共享，LRU，容量 256）
	 * <br/>
	 * 缓存 BeeInfoHelper.getBeeProduce 返回空结果的蜜蜂类型键，避免重复全量遍历。
	 * LinkedHashMap + removeEldestEntry 实现 LRU；synchronizedMap 提供防御性线程安全。
	 * 缓存失效通过 {@link #invalidateCache()} 在配方重载时清空。
	 */
	private static final Map<ResourceLocation, Boolean> negativeProduceCache =
			Collections.synchronizedMap(new LinkedHashMap<ResourceLocation, Boolean>(64, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Boolean> eldest) {
					return size() > MAX_NEGATIVE_CACHE_SIZE;
				}
			});

	/** 升级处理器引用 — 用于应用生产力倍率 */
	private final ApiaryUpgradeHandler upgradeHandler;

	/** 基因采样器产出处理器 — 委托生成 TYPE 基因物品 */
	private final GeneSampler geneSampler = new GeneSampler();

	/** 蜜脾→蜜脾块转换器 — 委托执行蜜脾块升级转换 */
	private final CombBlockConverter combBlockConverter = new CombBlockConverter();

	/** 万象创世产出预聚合器 — 替代原 576 ItemStack 路径，将产出聚合为 ≤9 个聚合 stack */
	private final MyriadAggregatedStacksBuilder myriadAggregatedBuilder = new MyriadAggregatedStacksBuilder();

	/**
	 * distributeToOutput 数组复用 — 避免每 20 tick × 类型数次分配 3 数组（对齐 PbRecipeCompleter 模式）
	 * <br/>
	 * 槽位数不变时直接复用实例字段数组，仅清空 slotStacks 引用；
	 * 槽位数变化时（防御性，正常场景不触发）重新分配。
	 */
	private ItemStack[] reusableSlotStacks = new ItemStack[0];
	private int[] reusableSlotCounts = new int[0];
	private int[] reusableSlotLimits = new int[0];

	/**
	 * 构造蜜蜂产出处理器
	 *
	 * @param upgradeHandler 升级处理器（提供生产力倍率等）
	 */
	public BeeProduceProcessor(ApiaryUpgradeHandler upgradeHandler) {
		this.upgradeHandler = upgradeHandler;
	}

	/**
	 * 批量处理同组蜜蜂的产出（Task 16.1 核心优化）
	 * <br/>
	 * 同组蜜蜂共享一次配方查询结果，按各自累积产出次数批量分发：
	 * <ol>
	 *   <li>对每个 BeeSlot，根据其 pendingCount 计算该蜜蜂的总产出</li>
	 *   <li>合并所有蜜蜂的产出物品栈（相同物品叠加）</li>
	 *   <li>一次性批量插入输出槽（减少 insertItem 调用次数）</li>
	 *   <li>一次性注入累积的流体（类型由离心配方推断）</li>
	 * </ol>
	 * <p>
	 * 模块 2+3：
	 * <ul>
	 *   <li>概率产出统一：移除 buildAdjustedItems，改为 {@link BeeProduceBatchSampler#sample}
	 *       统一执行概率判定（原 chancedOutput.max() 硬编码忽略 chance 字段）</li>
	 *   <li>蜂蜜流体条件化：移除 HONEY_FLUID_AMOUNT_PER_PRODUCE 硬编码，改为
	 *       {@link BeeFluidOutputResolver#resolveFluidOutput} 从离心配方推断流体类型</li>
	 * </ul>
	 * <p>
	 * 设计原理：将 N 只蜜蜂 × M 次产出的 N×M 次 insert 调用，
	 * 合并为按物品种类数的少量 insert 调用，显著降低高频场景的容器操作开销。
	 *
	 * @param beeSlots    蜜蜂槽数组（仅处理非空且 pendingCount>0 的槽位）
	 * @param pendingCounts 每个槽位累积的待产出次数（与 beeSlots 同长度）
	 * @param groupSlotIndices 当前蜜蜂类型组包含的槽位索引列表（Bug 3：仅遍历当前组槽位，避免混养串组）
	 * @param beeTypeKey  组内共享的蜜蜂类型键（已由调用方分组，避免重复解析）
	 * @param produceList 共享的产出配方输出表（ItemStack -> ChancedOutput，已查询缓存）
	 * @param slotManager 槽位管理器
	 * @param level       世界实例（万象创世随机产物生成用，可为 null）
	 * @param outputBuffer F4 产物溢出缓冲区（null 时剩余产物丢弃，与原版行为一致）
	 */
	public void processBatchProduce(BeeSlot[] beeSlots, int[] pendingCounts,
									List<Integer> groupSlotIndices,
									ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> produceList,
									ApiarySlotManager slotManager, Level level,
									ApiaryOutputBuffer outputBuffer) {
		if (beeSlots == null || pendingCounts == null || groupSlotIndices == null
				|| produceList == null || produceList.isEmpty()) {
			return;
		}

		// 累积所有蜜蜂的产出物品，待批量合并插入
		List<ItemStack> allItems = new ArrayList<>(groupSlotIndices.size() * produceList.size());
		long totalFluidAmount = 0L;
		// Bug 10: 累积万象创世蜜蜂的产出次数，用于追加随机蜜脾/蜜脾块
		int myriadCount = 0;
		int aggregatedCount = 0;  // 累积同组产出次数，循环外统一调用 BeeProduceBatchSampler（聚合取整修复）
		// 累积总产出次数 — 基因采样器按每次产出独立判定概率（与 PB 原版语义一致）
		int totalProduceCount = 0;
		// F5: 累加 productivity 基因纯度（按产出次数加权），用于加权平均后应用 PB 原版第五层公式
		float weightedPuritySum = 0.0f;
		boolean isMyriad = PBConstants.MYRIADCREATIONS_TYPE.equals(beeTypeKey);

		// 循环外预算生产力倍率 — 升级安装数量不随蜜蜂槽变化，
		// 避免每次采样重复触发 4 次 getInstalledUpgrades EnumMap 查询
		float productivityMultiplier = upgradeHandler.getProductivityMultiplier();

		// 模块 2+3：循环外查询流体输出类型（同组蜜蜂共享 beeTypeKey，流体类型一致）
		// BeeFluidOutputResolver 从离心配方推断流体类型：蜂蜜返回 FluidStack(honey, 250)，
		// 非蜂蜜流体（如时间流体）返回 EMPTY，无配方返回蜂蜜（向后兼容）
		FluidStack fluidTemplate = (beeTypeKey != null && level != null)
				? BeeFluidOutputResolver.resolveFluidOutput(beeTypeKey, level)
				: FluidStack.EMPTY;

		// Bug 3: 仅遍历当前组的槽位索引，避免混养时其他组槽位被错误处理
		for (int idx : groupSlotIndices) {
			int count = pendingCounts[idx];
			if (count <= 0) continue;
			BeeSlot slot = beeSlots[idx];
			if (slot == null || slot.isEmpty()) continue;

			aggregatedCount += count;
			// 模块 2+3：仅当流体模板非空时累积流体量（非蜂蜜流体蜜蜂不注入蜂蜜）
			if (!fluidTemplate.isEmpty()) {
				totalFluidAmount += (long) fluidTemplate.getAmount() * count;
			}
			totalProduceCount += count;
			// F5: 累加当前蜜蜂的 productivity 纯度（按产出次数加权，后续除以 aggregatedCount 得加权平均）
			weightedPuritySum += slot.getProductivityPurity() * count;
			// 万象创世蜜蜂累积产出次数（按 count 线性缩放随机产物）
			if (isMyriad) {
				myriadCount += count;
			}
			// Bug 3: 处理完立即清零，防止其他组重复处理同一槽位导致产出翻倍
			pendingCounts[idx] = 0;
		}

		// 模块 2+3：概率产出统一 — 循环外调用 BeeProduceBatchSampler 替代 buildAdjustedItems
		// F5: 计算同组蜜蜂的加权平均 productivity 纯度，应用 PB 原版第五层公式
		// finalMultiplier = upgradeMultiplier × (1 + 0.2 × purity)，纯度 1.0 时额外 +20% 产出
		if (aggregatedCount > 0) {
			float avgPurity = weightedPuritySum / aggregatedCount;
			float beeBonus = 1.0f + 0.2f * avgPurity;
			float finalMultiplier = productivityMultiplier * beeBonus;
			// 机械蜂箱当前无 stability 升级，stabilityBonus = 0.0
			allItems.addAll(BeeProduceBatchSampler.sample(
					produceList, aggregatedCount, finalMultiplier, 0.0f));
		}
		// Task 1: 万象创世随机蜜脾应用 PB 生产力倍率
		myriadCount = (int)(myriadCount * productivityMultiplier);

		// Bug 10: 万象创世蜜蜂追加随机蜜脾/蜜脾块
		// 机械蜂箱绕过 BeeHelperMixin 注入（调用 BeeInfoHelper.getBeeProduce 而非 BeeHelper.getBeeProduce），
		// 需在此动态追加。随机产物不进入静态缓存 produceCache，避免所有蜂箱共享同一份随机结果。
		// Task 7: 改用 MyriadAggregatedStacksBuilder 预聚合，将原 576 ItemStack 降为 ≤9 个聚合 stack，
		// 后续 distributeToOutput 迭代次数从 576 降为 9。
		if (myriadCount > 0 && level != null) {
			try {
				// cappedMyriadCount 作为 totalCount 上限保护输出槽总容量（9 槽 × 64 = 576，实际 ItemStack ≤9）
				int cappedMyriadCount = Math.min(myriadCount, MYRIAD_RANDOM_CAP);
				List<ItemStack> randomItems;
				if (upgradeHandler.hasCombBlockUpgrade()) {
					// 有 Block/Omega 升级：buildAggregatedCombBlocks 内部已 4× 缩放（与 Mixin 单次 4 个比例一致）
					randomItems = myriadAggregatedBuilder.buildAggregatedCombBlocks(
							cappedMyriadCount, level, this /* factoryKey */);
				} else {
					// 无 Block/Omega 升级
					randomItems = myriadAggregatedBuilder.buildAggregatedHoneycombs(
							cappedMyriadCount, level, this /* factoryKey */);
				}
				if (randomItems != null && !randomItems.isEmpty()) {
					allItems.addAll(randomItems);
				}
			} catch (Exception e) {
				// 随机产物追加失败不影响主产出，记录警告便于调试
				// MyriadAggregatedStacksBuilder 内部已有降级路径，外层 try-catch 为最终兜底
				ProductiveBeesGenesis.LOGGER.warn("万象创世随机蜜脾追加失败", e);
			}
		}

		// 基因采样器产出 TYPE 基因 — 复刻 PB 原版 AdvancedBeehiveBlockEntity#beeReleasePostAction 逻辑
		// 机械蜂箱虽无实体蜜蜂，但可从蜜蜂 NBT 的 neoforge:attachments.productivebees:attributes_handler 读取属性
		// （参考 BeeTooltipRenderer.getAttributesCompound）。当前仅生成 TYPE 基因，
		// PRODUCTIVITY 基因加成已在 BeeProduceBatchSampler 中应用，ENDURANCE/TEMPER 不适用（无实体蜜蜂）。
		// 与 PB 原版 Gene.getStack(type, purity) 格式完全兼容。
		// 概率公式：SAMPLER_BASE_CHANCE × 采样器数量 × 累积产出次数（独立伯努利判定）
		if (totalProduceCount > 0 && beeTypeKey != null && level != null
				&& upgradeHandler.hasGeneSamplerUpgrade()) {
			List<ItemStack> geneStacks = geneSampler.generateGeneSamples(
					beeTypeKey, totalProduceCount, upgradeHandler.getGeneSamplerCount(), level);
			if (!geneStacks.isEmpty()) {
				allItems.addAll(geneStacks);
			}
		}

		if (allItems.isEmpty() && totalFluidAmount == 0) return;

		// Bug 5修复：安装omega升级后，将蜜脾转换为蜜脾块（1:1替换，保持数量）
		// 转换结果不写入静态缓存 produceCache（不同蜂箱升级状态不同），每次动态转换
		if (upgradeHandler.hasCombBlockUpgrade()) {
			allItems = combBlockConverter.convertCombsToBlocks(allItems);
		}

		// 批量插入合并后的物品到输出槽
		List<ItemStack> leftovers = distributeToOutput(slotManager.getOutputSlots(), allItems);
		// F4: 将未成功插入的剩余产物送入缓冲区，下 tick 重试注入
		if (!leftovers.isEmpty() && outputBuffer != null) {
			outputBuffer.offer(leftovers);
		}

		// 模块 2+3：批量注入累积流体（类型由 BeeFluidOutputResolver 推断）
		// fluidTemplate 为 EMPTY 时 totalFluidAmount 始终为 0，不会注入
		if (totalFluidAmount > 0 && !fluidTemplate.isEmpty()) {
			injectFluid(slotManager.getFluidTank(), fluidTemplate, totalFluidAmount);
		}
	}

	/**
	 * 获取指定蜜蜂类型键的缓存产出配方输出表（双缓存：正缓存 + 负缓存）
	 * <br/>
	 * 查询顺序：正缓存 → 负缓存 → 全量遍历。负缓存命中返回空 Map，跳过全量遍历。
	 * 双缓存未命中时查询 {@link BeeInfoHelper#getBeeProduce}，结果写入对应缓存。
	 * <p>
	 * 模块 2+3：返回类型从 {@code List<ItemStack>} 改为 {@code Map<ItemStack, ChancedOutput>}，
	 * 缓存配方原始数据（不执行概率检查）。概率判定统一由 {@link BeeProduceBatchSampler} 在采样阶段处理。
	 * 无配方蜜蜂返回空 Map（不再返回占位产出），由调用方 BeeSlotTickProcessor 跳过处理。
	 * <p>
	 * 使用 ResourceLocation 作为查询键（非 EntityType.getKey()，ConfigurableBee 仅返回 configurable_bee），
	 * 确保能查询到 BeeReloadListener 中的具体蜜蜂产出配方。
	 *
	 * @param beeTypeKey 蜜蜂类型键（由 {@link BeeNbtHelper#resolveBeeTypeKey} 解析）
	 * @param level      世界实例（配方查询用）
	 * @return 配方输出表（ItemStack -> ChancedOutput），无配方返回空 Map，永不为 null
	 */
	public Map<ItemStack, ChancedOutput> getCachedProduce(ResourceLocation beeTypeKey, Level level) {
		if (beeTypeKey == null || level == null) return Map.of();

		// 1. 查正缓存（有产出配方的蜜蜂）
		Map<ItemStack, ChancedOutput> cached = produceCache.get(beeTypeKey);
		if (cached != null) return cached;

		// 2. 查负缓存（无产出配方的蜜蜂）— 命中则跳过全量遍历，返回空 Map
		if (negativeProduceCache.containsKey(beeTypeKey)) {
			return Map.of();
		}

		// 3. 双缓存未命中 — 查询 BeeInfoHelper.getBeeProduce（已缓存 getRecipeOutputs 结果）
		Map<ItemStack, ChancedOutput> result = BeeInfoHelper.getBeeProduce(level, beeTypeKey);
		if (result == null || result.isEmpty()) {
			// 无配方：写入负缓存（LRU 淘汰），返回空 Map
			negativeProduceCache.put(beeTypeKey, Boolean.TRUE);
			return Map.of();
		}

		// 有配方：写入正缓存（BeeInfoHelper 已返回不可变视图，直接缓存）
		produceCache.put(beeTypeKey, result);
		return result;
	}

	/**
	 * 分发物品列表到输出槽（直写优化版）
	 * <br/>
	 * 仿照 {@link com.ayoshiko.productivebeesgenesis.mek.PbRecipeCompleter#planAndExecute} 的直写模式：
	 * 先合并相同物品+组件的栈，再预扫描输出槽状态，对空槽直接 {@code setStack}，
	 * 对同类型槽直接 {@code grow}，完全绕过 {@code insertItem} 内部的
	 * {@code isSameItemSameComponents} 组件比较（含 GeckoLib wrapOperation 拦截）。
	 * <p>
	 * Spark 分析显示旧版 {@code insertItem} 路径消耗 22.69 ms（占蜂箱 tick 的 42%），
	 * 其中 17.8 ms 花在 {@code isSameItemSameComponents} → {@code PatchedDataComponentMap.equals} 上。
	 * 直写模式将组件比较替换为 Item 引用比较（{@code ==}），预期减少 15-17 ms。
	 * <p>
	 * 剩余物品溢出时静默丢弃（与原版行为一致）。
	 *
	 * @param outputSlots 输出槽列表
	 * @param stacks      待插入物品栈列表（会被合并）
	 * @return 未成功插入的剩余产物列表（F4：供调用方送入 ApiaryOutputBuffer）
	 */
	private List<ItemStack> distributeToOutput(List<? extends IInventorySlot> outputSlots, List<ItemStack> stacks) {
		if (stacks.isEmpty() || outputSlots.isEmpty()) return new ArrayList<>();
		// mergeStacks 条件化：小批量（≤8 stack）跳过合并（覆盖万象创世 9 stack 场景跳过；PB 原版蜜蜂 2-3 stack 跳过）
		// 仅在 stacks.size() > MERGE_THRESHOLD 时调用，避免小批量场景的 hashCode 预分组纯开销
		List<ItemStack> merged = (stacks.size() > MERGE_THRESHOLD)
				? ItemStackMergeHelper.mergeStacks(stacks)
				: stacks;

		int slotCount = outputSlots.size();
		// F4: 收集未成功插入的剩余产物，返回给调用方送入 ApiaryOutputBuffer
		List<ItemStack> leftovers = new ArrayList<>();
		// 数组复用：槽位数不变时直接复用实例字段数组，避免每 20 tick × 类型数次分配 3 数组（对齐 PbRecipeCompleter 模式）
		if (reusableSlotStacks.length != slotCount) {
			// 防御性：槽位数变化时重新分配（正常场景不触发）
			reusableSlotStacks = new ItemStack[slotCount];
			reusableSlotCounts = new int[slotCount];
			reusableSlotLimits = new int[slotCount];
		} else {
			// 复用：仅清空 slotStacks 引用（slotCounts / slotLimits 会被覆盖写入，无需清空）
			Arrays.fill(reusableSlotStacks, null);
		}
		// 预扫描输出槽当前状态（一次遍历，避免每次 insert 都重新读取+比较）
		for (int i = 0; i < slotCount; i++) {
			ItemStack current = outputSlots.get(i).getStack();
			reusableSlotStacks[i] = current;
			if (current.isEmpty()) {
				reusableSlotCounts[i] = 0;
				reusableSlotLimits[i] = 0; // 空槽 limit 待填入时计算
			} else {
				reusableSlotCounts[i] = current.getCount();
				reusableSlotLimits[i] = outputSlots.get(i).getLimit(current);
			}
		}

		// 逐个合并后的栈分发到槽位
		for (ItemStack stack : merged) {
			if (stack.isEmpty()) continue;
			int remaining = stack.getCount();

			for (int i = 0; i < slotCount && remaining > 0; i++) {
				ItemStack slotStack = reusableSlotStacks[i];
				if (slotStack.isEmpty()) {
				// 空槽：计算 limit 并填入
				int limit = outputSlots.get(i).getLimit(stack);
				if (limit <= 0) continue;
				int canFit = Math.min(remaining, limit);
				// 直写：setStack 替代 insertItem
				ItemStack newStack = stack.copyWithCount(canFit);
				outputSlots.get(i).setStack(newStack);
				// M3-1 修复：setStack 后回读 actual stack，防止 slot 内部截断导致 remaining 计算错误
				// 原实现直接 remaining -= canFit，若 slot 内部因 validator 截断栈大小，会导致产物丢失
				ItemStack actualStack = outputSlots.get(i).getStack();
				int actualCount = actualStack.isEmpty() ? 0 : actualStack.getCount();
				reusableSlotStacks[i] = actualStack;
				reusableSlotCounts[i] = actualCount;
				reusableSlotLimits[i] = limit;
				remaining -= actualCount;
			} else if (slotStack.getItem() == stack.getItem()
					&& ItemStack.isSameItemSameComponents(slotStack, stack)) {
				// Bug 2 修复：同 Item 同 BEE_TYPE 组件才可叠加，防止不同 bee_type 蜜脾互相覆盖
				int space = reusableSlotLimits[i] - reusableSlotCounts[i];
				if (space <= 0) continue;
				int canFit = Math.min(remaining, space);
				// M3-1 修复：显式 setStack 替代 grow，避免依赖 ItemStack 可变性（getStack 可能返回副本）
				// 原实现 reusableSlotStacks[i].grow(canFit) 依赖可变性，若 getStack 返回副本则实际槽位未更新
				ItemStack grownStack = reusableSlotStacks[i].copyWithCount(reusableSlotCounts[i] + canFit);
				outputSlots.get(i).setStack(grownStack);
				// 回读 actual stack，按实际写入量扣减 remaining
				ItemStack actualStack = outputSlots.get(i).getStack();
				int actualCount = actualStack.isEmpty() ? 0 : actualStack.getCount();
				int actualGrown = Math.max(0, actualCount - reusableSlotCounts[i]);
				reusableSlotStacks[i] = actualStack;
				reusableSlotCounts[i] = actualCount;
				remaining -= actualGrown;
			}
			}
			// F4: 收集未成功插入的剩余产物，返回给调用方送入 ApiaryOutputBuffer
			if (remaining > 0) {
				leftovers.add(stack.copyWithCount(remaining));
			}
		}
		return leftovers;
	}

	/**
	 * 注入流体到流体罐（支持任意流体类型）
	 * <br/>
	 * 模块 2+3：原 injectHoneyFluid 硬编码 PB 蜂蜜流体，改为通过 template 参数接收流体类型。
	 * 流体类型由 {@link BeeFluidOutputResolver#resolveFluidOutput} 从离心配方推断：
	 * 蜂蜜蜜蜂注入蜂蜜，非蜂蜜流体蜜蜂不调用此方法（fluidTemplate 为 EMPTY）。
	 * <p>
	 * 超高倍率（如 4096x × 256x）场景下单次 tick 累积量可能超过 Integer.MAX_VALUE，
	 * 因此 amount 使用 long 类型；FluidStack 构造器仅接受 int，需分段注入。
	 *
	 * @param tank     流体罐
	 * @param template 流体模板（含流体类型，amount 字段不使用，由 amount 参数覆盖）
	 * @param amount   注入量（mB），批量场景为累积总量（long 避免溢出）
	 */
	private void injectFluid(IExtendedFluidTank tank, FluidStack template, long amount) {
		if (tank == null || amount <= 0 || template.isEmpty()) return;
		// FluidStack 构造器仅接受 int，long 总量需分段注入
		// 单次上限 Integer.MAX_VALUE（约 21.47 亿 mB），避免溢出
		long remaining = amount;
		while (remaining > 0) {
			int chunk = (int) Math.min(remaining, Integer.MAX_VALUE);
			// 使用 template 的流体类型，覆盖 amount 为当前分段量
			FluidStack stack = template.copyWithAmount(chunk);
			// M3-2 修复：读取 tank.insert 返回值，计算实际注入量
			// 原实现直接 remaining -= chunk，tank 已满时实际注入 0 但 remaining 已扣完，
			// 导致后续 chunk 不再尝试，但实际注入量为 0，流体产物静默丢失
			FluidStack leftover = tank.insert(stack, Action.EXECUTE, AutomationType.INTERNAL);
			int actualInserted = chunk - (leftover.isEmpty() ? 0 : leftover.getAmount());
			remaining -= actualInserted;
			// 实际注入量为 0（tank 已满），跳出避免无限循环
			if (actualInserted == 0 && chunk > 0) break;
		}
	}

	/**
	 * 清空产出配方缓存（静态，正缓存 + 负缓存 + 流体输出缓存）
	 * <br/>
	 * 在配方重载时由 {@link ProductiveBeesGenesis#onTagsReload} 调用，
	 * 防止使用过期配方数据。静态方法确保所有方块实体的缓存同步失效。
	 * 模块 2+3：同步失效 {@link BeeFluidOutputResolver} 流体输出缓存。
	 */
	public static void invalidateCache() {
		produceCache.clear();
		negativeProduceCache.clear();
		BeeFluidOutputResolver.invalidateCache();
	}
}
