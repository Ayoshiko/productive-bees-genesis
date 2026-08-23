package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	/** 升级处理器引用 — 用于应用生产力倍率 */
	private final ApiaryUpgradeHandler upgradeHandler;
	private final TileEntityMekApiary apiary;

	/** 基因采样器产出处理器 — 委托生成 TYPE 基因物品 */
	private final GeneSampler geneSampler = new GeneSampler();

	/** 蜜脾→蜜脾块转换器 — 委托执行蜜脾块升级转换 */
	private final CombBlockConverter combBlockConverter = new CombBlockConverter();

	/** 万象创世产出预聚合器 — 替代原 576 ItemStack 路径，将产出聚合为 ≤9 个聚合 stack */
	private final MyriadAggregatedStacksBuilder myriadAggregatedBuilder = new MyriadAggregatedStacksBuilder();

	/** 产物分发器（直写输出槽 + 分段流体注入，复用数组跨 tick 零扩容） */
	private final BeeProduceOutputDispatcher outputDispatcher = new BeeProduceOutputDispatcher();
	private final ArrayList<ItemStack> reusableProducedItems = new ArrayList<>();
	/** 每次 flush 复用的四档生产力基因计数，避免按蜜蜂类型分组时反复分配数组。 */
	private final long[] reusableProductivityCounts = new long[BeeProductivityGene.VERY_HIGH + 1];


	/**
	 * 构造蜜蜂产出处理器
	 *
	 * @param upgradeHandler 升级处理器（提供生产力倍率等）
	 */
	public BeeProduceProcessor(ApiaryUpgradeHandler upgradeHandler, TileEntityMekApiary apiary) {
		this.upgradeHandler = upgradeHandler;
		this.apiary = apiary;
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
	 * @param feederManager 喂食槽管理器（Wanna Bee 动态琥珀产出使用）
	 * @param origin       蜂箱位置（动态战利品上下文使用）
	 * @param level       世界实例（万象创世随机产物生成用，可为 null）
	 * @param outputBuffer F4 产物溢出缓冲区（null 时剩余产物丢弃，与原版行为一致）
	 */
	public void processBatchProduce(BeeSlot[] beeSlots, int[] pendingCounts,
									OrderedSlotIndex groupSlotIndices,
									ResourceLocation beeTypeKey, Map<ItemStack, ChancedOutput> produceList,
									ApiarySlotManager slotManager, FeederSlotManager feederManager,
									BlockPos origin, Level level,
									ApiaryOutputBuffer outputBuffer) {
		if (beeSlots == null || pendingCounts == null || groupSlotIndices == null
				|| produceList == null
				|| (produceList.isEmpty() && !PBConstants.WANNA_TYPE.equals(beeTypeKey)
						&& !MultiFlowerBeeAdapter.isMultiFlowerBee(beeTypeKey))) {
			return;
		}

		// 累积所有蜜蜂的产出物品，待批量合并插入
		List<ItemStack> allItems = reusableProducedItems;
		reusableProducedItems.clear();
		long totalFluidAmount = 0L;
		long aggregatedCount = 0L;  // 累积同组产出次数，循环外统一调用 BeeProduceBatchSampler（聚合取整修复）
		// 累积总产出次数 — 基因采样器按每次产出独立判定概率（与 PB 原版语义一致）
		long totalProduceCount = 0L;
		long[] productivityCounts = reusableProductivityCounts;
		Arrays.fill(productivityCounts, 0L);
		boolean isMyriad = PBConstants.MYRIADCREATIONS_TYPE.equals(beeTypeKey);

		// 循环外预算生产力倍率 — 升级安装数量不随蜜蜂槽变化，
		// 避免每次采样重复触发 4 次 getInstalledUpgrades EnumMap 查询
		float productivityMultiplier = upgradeHandler.getProductivityMultiplier();
		boolean discardUselessByproducts =
				apiary.getPbUpgradeInstalledCount(PbUpgradeType.USELESS_BYPRODUCT) > 0;

		// 模块 2+3：循环外查询流体输出类型（同组蜜蜂共享 beeTypeKey，流体类型一致）
		// BeeFluidOutputResolver 从离心配方推断流体类型：蜂蜜返回 FluidStack(honey, 250)，
		// 非蜂蜜流体（如时间流体）返回 EMPTY，无配方返回蜂蜜（向后兼容）
		FluidStack fluidTemplate = (!discardUselessByproducts && beeTypeKey != null && level != null)
				? BeeFluidOutputResolver.resolveFluidOutput(beeTypeKey, level)
				: FluidStack.EMPTY;

		// Bug 3: 仅遍历当前组的槽位索引，避免混养时其他组槽位被错误处理
		for (int position = 0; position < groupSlotIndices.size(); position++) {
			int idx = groupSlotIndices.get(position);
			int count = pendingCounts[idx];
			if (count <= 0) continue;
			BeeSlot slot = beeSlots[idx];
			if (slot == null || slot.isEmpty()) continue;

			aggregatedCount = SaturatingMath.saturatingAdd(aggregatedCount, count);
			// 模块 2+3：仅当流体模板非空时累积流体量（非蜂蜜流体蜜蜂不注入蜂蜜）
			if (!fluidTemplate.isEmpty()) {
				long fluidAmount = SaturatingMath.saturatingMultiply(fluidTemplate.getAmount(), count);
				totalFluidAmount = SaturatingMath.saturatingAdd(totalFluidAmount, fluidAmount);
			}
			totalProduceCount = SaturatingMath.saturatingAdd(totalProduceCount, count);
			int productivityLevel = slot.getProductivityLevel();
			productivityCounts[productivityLevel] = SaturatingMath.saturatingAdd(
					productivityCounts[productivityLevel], count);
			// Bug 3: 处理完立即清零，防止其他组重复处理同一槽位导致产出翻倍
			pendingCounts[idx] = 0;
		}

		// 按四档生产力等级分别批量采样。PB 原版公式含逐栈取整，混养时不能用平均等级替代。
		if (aggregatedCount > 0) {
			boolean multiFlowerBee = MultiFlowerBeeAdapter.isMultiFlowerBee(beeTypeKey);
			for (int productivityLevel = BeeProductivityGene.NORMAL;
					productivityLevel <= BeeProductivityGene.VERY_HIGH; productivityLevel++) {
				int sampledProductionCount = SaturatingMath.saturatingToInt(
						productivityCounts[productivityLevel]);
				if (sampledProductionCount <= 0) continue;
				// 机械蜂箱当前无 stability 升级，stabilityBonus = 0.0
				if (multiFlowerBee) {
					ItemStack feederProduce = MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
							beeTypeKey, feederManager, level);
					BeeProduceBatchSampler.sampleGuaranteedInto(allItems, feederProduce,
							sampledProductionCount, productivityMultiplier, productivityLevel);
				} else {
					BeeProduceBatchSampler.sampleInto(allItems, produceList,
							sampledProductionCount, productivityMultiplier, 0.0f, productivityLevel);
				}
				if (PBConstants.WANNA_TYPE.equals(beeTypeKey)
						&& level instanceof ServerLevel serverLevel) {
					allItems.addAll(WannaBeeAmberAdapter.sampleBatch(serverLevel, origin, feederManager,
							sampledProductionCount, productivityMultiplier, productivityLevel));
				}
			}
		}
		// 万象创世追加产物也按每只蜜蜂的生产力等级计算，避免与主产物倍率脱节。
		double scaledMyriadCount = 0.0D;
		if (isMyriad) {
			for (int productivityLevel = BeeProductivityGene.NORMAL;
					productivityLevel <= BeeProductivityGene.VERY_HIGH; productivityLevel++) {
				scaledMyriadCount += productivityCounts[productivityLevel]
						* (double) productivityMultiplier
						* BeeProductivityGene.adjustStackCount(1, productivityLevel);
			}
		}
		int effectiveMyriadCount = scaledMyriadCount >= Integer.MAX_VALUE
				? Integer.MAX_VALUE : Math.max(0, (int) scaledMyriadCount);

		// Bug 10: 万象创世蜜蜂追加随机蜜脾/蜜脾块
		// 机械蜂箱绕过 BeeHelperMixin 注入（调用 BeeInfoHelper.getBeeProduce 而非 BeeHelper.getBeeProduce），
		// 需在此动态追加。随机产物不进入静态缓存 BeeProduceCache，避免所有蜂箱共享同一份随机结果。
		// Task 7: 改用 MyriadAggregatedStacksBuilder 预聚合，将原 576 ItemStack 降为 ≤9 个聚合 stack，
		// 后续 distributeToOutput 迭代次数从 576 降为 9。
		if (effectiveMyriadCount > 0 && level != null) {
			try {
				// cappedMyriadCount 作为 totalCount 上限保护输出槽总容量（9 槽 × 64 = 576，实际 ItemStack ≤9）
				int cappedMyriadCount = Math.min(effectiveMyriadCount, MYRIAD_RANDOM_CAP);
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
					beeTypeKey, SaturatingMath.saturatingToInt(totalProduceCount),
					upgradeHandler.getGeneSamplerCount(), level);
			if (!geneStacks.isEmpty()) {
				allItems.addAll(geneStacks);
			}
		}

		if (discardUselessByproducts) {
			allItems.removeIf(UselessByproductUpgradeHelper::isPollenPuff);
		}

		if (allItems.isEmpty() && totalFluidAmount == 0) return;

		// Bug 5修复：安装omega升级后，将蜜脾转换为蜜脾块（1:1替换，保持数量）
		// 转换结果不写入静态缓存 BeeProduceCache（不同蜂箱升级状态不同），每次动态转换
		if (upgradeHandler.hasCombBlockUpgrade()) {
			allItems = combBlockConverter.convertCombsToBlocksInPlace(allItems);
		}

		// 批量插入合并后的物品到输出槽
		if (apiary.isDirectAeOutputEnabled() && !allItems.isEmpty()) {
			List<ItemStack> aeLeftovers = null;
			for (ItemStack stack : allItems) {
				if (stack.isEmpty()) continue;
				if (apiary.shouldHoldForCentrifuge(stack)) {
					if (aeLeftovers == null) aeLeftovers = new ArrayList<>();
					aeLeftovers.add(stack);
					continue;
				}
				int accepted = apiary.pushGeneratedItemToAe(stack);
				if (accepted < stack.getCount()) {
					ItemStack remaining = stack.copy();
					remaining.shrink(Math.max(0, accepted));
					if (aeLeftovers == null) aeLeftovers = new ArrayList<>();
					aeLeftovers.add(remaining);
				}
			}
			allItems = aeLeftovers == null ? List.of() : aeLeftovers;
		}
		// 离心机优先产出直连：蜜脾跳过输出槽中转直接进离心机输入槽（低频 flush 路径）
		// 输出槽保留给非蜜脾产物，降低输出满触发蜜蜂停工的概率；
		// 离心机也满时剩余回落输出槽 → 缓冲区 → 直连重试（渐进降级，防溢出语义不变）
		if (!allItems.isEmpty() && apiary.isCentrifugePriorityEnabled()
				&& apiary.isDirectEjectEnabled()) {
			allItems = apiary.directTransferProducedToCentrifuges(allItems);
		}
		List<ItemStack> leftovers = outputDispatcher.distribute(slotManager.getOutputSlots(), allItems);
		// F4: 将未成功插入的剩余产物送入缓冲区，下 tick 重试注入
		// 离心机优先：蜜脾满时不淘汰，超出输出上限的溢出部分推 AE（不再丢弃）
		if (!leftovers.isEmpty() && outputBuffer != null) {
			apiary.offerLeftoversWithCentrifugeHold(leftovers);
		}

		// 模块 2+3：批量注入累积流体（类型由 BeeFluidOutputResolver 推断）
		// fluidTemplate 为 EMPTY 时 totalFluidAmount 始终为 0，不会注入
		if (totalFluidAmount > 0 && !fluidTemplate.isEmpty()) {
			long remainingFluid = totalFluidAmount;
			if (apiary.isDirectAeOutputEnabled()) {
				long accepted = apiary.pushGeneratedFluidToAe(fluidTemplate, remainingFluid);
				remainingFluid -= Math.min(remainingFluid, Math.max(0L, accepted));
			}
			if (remainingFluid > 0) {
				remainingFluid = outputDispatcher.injectFluid(slotManager.getFluidTank(), fluidTemplate, remainingFluid);
			}
			if (remainingFluid > 0) {
				// The local tank and AE2 can both accept only a prefix. Keep the suffix
				// outside FluidStack so values above Integer.MAX_VALUE remain lossless.
				apiary.addPendingHoneyFluid(fluidTemplate, remainingFluid);
			}
		}
	}

	/**
	 * Retries the fluid suffix before a new production batch is started.
	 *
	 * @return true when there is no blocked fluid left and production may continue
	 */
	boolean flushPendingFluid() {
		long amount = apiary.getPendingHoneyFluidAmount();
		if (amount <= 0) return true;
		FluidStack template = apiary.getPendingHoneyFluidTemplate();
		if (template == null || template.isEmpty()) {
			// A malformed legacy tag must not permanently stall the machine.
			apiary.clearPendingHoneyFluid();
			return true;
		}
		long remaining = amount;
		if (apiary.isDirectAeOutputEnabled()) {
			long accepted = apiary.pushGeneratedFluidToAe(template, remaining);
			remaining -= Math.min(remaining, Math.max(0L, accepted));
		}
		if (remaining > 0) {
			remaining = outputDispatcher.injectFluid(apiary.getFluidTank(), template, remaining);
		}
		if (remaining <= 0) {
			apiary.clearPendingHoneyFluid();
			return true;
		}
		if (remaining != amount) {
			apiary.setPendingHoneyFluid(remaining, template);
		}
		return false;
	}

	boolean hasPendingFluid() {
		return apiary.getPendingHoneyFluidAmount() > 0;
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
		return getCachedProduce(beeTypeKey, level, null);
	}

	/**
	 * 获取指定蜜蜂类型键的缓存产出配方输出表（模块 1：支持 multi-flower 蜜蜂）
	 * <br/>
	 * 模块 1 修复：新增 feeder 参数，对 lumber_bee/quarry_bee/dye_bee 等 multi-flower 蜜蜂：
	 * <ul>
	 *   <li>跳过正/负缓存（产物依赖喂食槽实时状态，不能静态缓存）</li>
	 *   <li>调用 MultiFlowerBeeAdapter.sampleProduceFromFeeder 从喂食槽推断产物</li>
	 *   <li>喂食槽无匹配返回空 Map（不写入负缓存，下次喂食槽有内容时重新查询）</li>
	 * </ul>
	 * 非 multi-flower 蜜蜂走原有正/负缓存路径，行为不变。
	 *
	 * @param beeTypeKey 蜜蜂类型键
	 * @param level      世界实例
	 * @param feeder     喂食槽管理器（null 时 multi-flower 蜜蜂返回空 Map，普通蜜蜂不受影响）
	 * @return 配方输出表，无配方返回空 Map，永不为 null
	 */
	public Map<ItemStack, ChancedOutput> getCachedProduce(ResourceLocation beeTypeKey, Level level,
			FeederSlotManager feeder) {
		if (beeTypeKey == null || level == null) return Map.of();

		// 模块 1：multi-flower 蜜蜂走喂食槽推断路径，不经过缓存
		if (MultiFlowerBeeAdapter.isMultiFlowerBee(beeTypeKey)) {
			List<ItemStack> feederItems = MultiFlowerBeeAdapter.sampleProduceFromFeeder(beeTypeKey, feeder, level);
			if (feederItems.isEmpty()) return Map.of();
			// 包装为 ChancedOutput（min=max=1, chance=1.0 必产），由 BeeProduceBatchSampler 处理 rolls
			Map<ItemStack, ChancedOutput> result = new LinkedHashMap<>(feederItems.size());
			for (ItemStack stack : feederItems) {
				result.put(stack, new ChancedOutput(Ingredient.of(stack), 1, 1, 1.0f));
			}
			return result;
		}

		// 1. 查正缓存（有产出配方的蜜蜂）
		Map<ItemStack, ChancedOutput> cached = BeeProduceCache.getProduce(beeTypeKey);
		if (cached != null) return cached;

		// 2. 查负缓存（无产出配方的蜜蜂）— 命中则跳过全量遍历，返回空 Map
		if (BeeProduceCache.isNegative(beeTypeKey)) {
			return Map.of();
		}

		// 3. 双缓存未命中 — 查询 BeeInfoHelper.getBeeProduce（已缓存 getRecipeOutputs 结果）
		Map<ItemStack, ChancedOutput> result = BeeInfoHelper.getBeeProduce(level, beeTypeKey);
		if (result == null || result.isEmpty()) {
			// 无配方：写入负缓存（LRU 淘汰），返回空 Map
			BeeProduceCache.putNegative(beeTypeKey);
			return Map.of();
		}

		// 有配方：写入正缓存（BeeInfoHelper 已返回不可变视图，直接缓存）
		BeeProduceCache.putProduce(beeTypeKey, result);
		return result;
	}

	/** 分发产物到输出槽 — 委托 {@link BeeProduceOutputDispatcher#distribute} */
	private List<ItemStack> distributeToOutput(List<? extends IInventorySlot> outputSlots, List<ItemStack> stacks) {
		return outputDispatcher.distribute(outputSlots, stacks);
	}

	/** 注入流体到流体罐 — 委托 {@link BeeProduceOutputDispatcher#injectFluid} */
	private long injectFluid(IExtendedFluidTank tank, FluidStack template, long amount) {
		return outputDispatcher.injectFluid(tank, template, amount);
	}

/**
	 * 清空产出配方缓存（静态，正缓存 + 负缓存 + 流体输出缓存）
	 * <br/>
	 * 在配方重载时由 {@link ProductiveBeesGenesis#onTagsReload} 调用，
	 * 防止使用过期配方数据。静态方法确保所有方块实体的缓存同步失效。
	 * 模块 2+3：同步失效 {@link BeeFluidOutputResolver} 流体输出缓存。
	 */
	public static void invalidateCache() {
		BeeProduceCache.invalidate();
		BeeFluidOutputResolver.invalidateCache();
	}
}
