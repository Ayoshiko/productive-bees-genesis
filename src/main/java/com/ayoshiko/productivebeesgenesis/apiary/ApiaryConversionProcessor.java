package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 机械蜂箱（Mek Apiary）转化处理器
 * <br/>
 * 为机械蜂箱的内置模拟升级复刻 PB 原版蜂箱的两类转化能力：
 * <ul>
 *   <li><b>物品转化</b>（PB {@code item_conversion}，如烈焰蜜蜂把黑曜石蜜蜂刷怪蛋转化为无限蜜蜂刷怪蛋）：
 *       转化原料放入蜂箱的饲养板（喂食槽），蜜蜂每完成一个生产周期尝试一次；
 *       成功后消耗 1 个原料并把产物写入蜂箱输出槽（对应 PB 模拟蜂箱中 {@code addOutput} 行为）。</li>
 *   <li><b>方块转化</b>（PB {@code block_conversion}）：
 *       以饲养板（喂食槽）中的 BlockItem 为转化目标（对应 PB Feeder 内 BlockItem 转化分支），
 *       每完成一个生产周期按配方概率消耗 1 个方块物品并输出转化后的方块物品。
 *       <b>不检测世界中的方块</b>——机械蜂箱的转化目标统一走饲养板 TAB，与物品转化一致。</li>
 * </ul>
 * 尝试顺序：遍历饲养板 → BlockItem 方块转化 → 物品转化（每周期仅命中第一个匹配项）；
 * 对齐 PB 语义：配方 {@code pollinates=false} 时，该转化周期会占用蜜蜂（hasConverted），
 * 该周期不再产出蜜脾（通过扣减 pendingProductions 实现）。
 * <p>
 * <b>性能设计</b>（常态 + tick 加速）：
 * <ul>
 *   <li>快速路径：蜜蜂无任何转化配方 / 饲养板全空 / 无对应类型配方时，零遍历直接返回</li>
 *   <li>配置开关每次批量刷新只读取一次（避免每槽每蜜蜂重复读 NeoForge 配置）</li>
 *   <li>节流：每个批量刷新周期（10 tick）每只蜜蜂最多尝试一次转化，正常速度下等价于
 *       每完成一个生产周期一次；超高加速场景下防止转化原料被瞬间耗尽</li>
 *   <li>产物直写输出槽（{@link BeeProduceOutputDispatcher}），满载剩余进入溢出缓冲区，零丢弃</li>
 * </ul>
 * 线程安全：由服务端 tick 线程（BeeSlotTickProcessor.flushPendingProductions）串行调用。
 */
final class ApiaryConversionProcessor {

	/** 所属方块实体引用 */
	private final TileEntityMekApiary tile;

	/** 槽位管理器引用（输出槽写入） */
	private final ApiarySlotManager slotManager;

	/** 喂食器管理器引用（饲养板槽位） */
	private final FeederSlotManager feederManager;

	/** 产物分发器（直写输出槽 + 剩余产物入缓冲区，与产出处理器同款） */
	private final BeeProduceOutputDispatcher outputDispatcher = new BeeProduceOutputDispatcher();

	/**
	 * 饲养板匹配结果单条目缓存（任务：转化性能优化）
	 * <br/>
	 * 同一蜜蜂类型组的多次转化尝试共享一次饲养板扫描：
	 * 缓存键 = (蜜蜂类型, 饲养板花朵缓存版本号)，版本号由 FeederSlotManager 在
	 * 任意喂食槽内容变化时递增——消耗原料后缓存自动失效，下一只蜜蜂重新扫描。
	 * 多蜜蜂同类型场景（如 42 只蜜蜂 × 60 槽）将扫描次数从 O(N×M) 降为 O(M)。
	 */
	private ResourceLocation cachedMatchBeeType;
	private int cachedMatchFeederVersion = -1;
	private boolean cachedMatchItemEnabled;
	private boolean cachedMatchBlockEnabled;
	private ConversionMatch cachedMatch;

	ApiaryConversionProcessor(TileEntityMekApiary tile, ApiarySlotManager slotManager,
			FeederSlotManager feederManager) {
		this.tile = tile;
		this.slotManager = slotManager;
		this.feederManager = feederManager;
	}

	/**
	 * 处理一组蜜蜂的转化尝试（生产刷新前调用）
	 * <br/>
	 * 对组内每只已累积完成周期的蜜蜂执行一次转化尝试；若尝试命中
	 * {@code pollinates=false} 的配方，则该周期的产出被占用（扣减 pendingProductions），
	 * 与 PB 的 hasConverted 语义一致。
	 * <p>
	 * 快速路径顺序：per-tile 喂食槽转化开关 → 配置开关 → 配方索引就绪 → 蜜蜂有转化配方 → 饲养板非空，
	 * 全部通过后才进入逐槽尝试。
	 *
	 * @param beeTypeKey          蜜蜂类型键（同组共享）
	 * @param level               世界实例
	 * @param beeSlots            蜜蜂槽数组
	 * @param slotIndices         组内蜜蜂槽索引列表
	 * @param pendingProductions  待产出计数数组（转化占用时原地扣减）
	 */
	void processGroupConversions(ResourceLocation beeTypeKey, Level level, BeeSlot[] beeSlots,
			OrderedSlotIndex slotIndices, int[] pendingProductions) {
		if (beeTypeKey == null || level == null || beeSlots == null || slotIndices == null
				|| pendingProductions == null) {
			return;
		}
		// per-tile 喂食槽转化开关（默认关闭）：关闭时零遍历直接返回，
		// 放在配置读取之前作为最便宜的快速路径；缓存键只依赖饲养板版本与全局配置，
		// 关闭期间不会消耗原料，故重新开启后沿用缓存仍然正确。
		if (!tile.isFeederConversionEnabled()) {
			return;
		}
		// 配置开关单次读取（避免每槽每蜜蜂重复读 NeoForge 配置）
		boolean itemEnabled = ModConfig.SERVER.apiaryItemConversionEnabled.get();
		boolean blockEnabled = ModConfig.SERVER.apiaryBlockConversionEnabled.get();
		if (!itemEnabled && !blockEnabled) {
			return;
		}
		BeeConversionQueries.ensureLoaded(level);
		// 快速路径：该蜜蜂无任何转化配方时直接返回
		if (!BeeConversionQueries.hasAnyConversionRecipe(beeTypeKey)) {
			return;
		}
		// 快速路径：饲养板全空时无任何转化可执行（比逐槽配方查找便宜得多）
		if (!feederManager.hasAnyFlower()) {
			return;
		}
		for (int position = 0; position < slotIndices.size(); position++) {
			int idx = slotIndices.get(position);
			if (idx < 0 || idx >= beeSlots.length || idx >= pendingProductions.length) {
				continue;
			}
			if (pendingProductions[idx] <= 0) {
				continue;
			}
			ConversionOutcome outcome = attemptConversion(beeTypeKey, level, itemEnabled, blockEnabled);
			if (outcome == ConversionOutcome.NONE) {
				continue;
			}
			// PB 语义：pollinates=false 的转化配方占用该周期（hasConverted），该周期不产出蜜脾
			if (outcome == ConversionOutcome.NO_POLLINATES && pendingProductions[idx] > 0) {
				pendingProductions[idx]--;
			}
		}
	}

	/**
	 * 单次转化尝试（仅检测饲养板）
	 * <br/>
	 * 遍历饲养板（喂食槽）：
	 * <ol>
	 *   <li>BlockItem 匹配方块转化配方 → 消耗 1 个并输出转化方块物品（PB block_conversion）</li>
	 *   <li>物品匹配物品转化配方 → 消耗 1 个并输出配方产物（PB item_conversion）</li>
	 * </ol>
	 * 命中任一配方即返回（即使概率失败，该周期同样被占用 — 与 PB 无条件
	 * {@code setHasConverted(!pollinates)} 的行为一致）。
	 * <p>
	 * 性能：先按配方类型细分快速路径（无对应类型配方则跳过该类型检查），
	 * 配置开关由调用方传入避免重复读取。
	 *
	 * @param beeTypeKey   蜜蜂类型键
	 * @param level        世界实例
	 * @param itemEnabled  物品转化总开关（调用方已读取）
	 * @param blockEnabled 方块转化总开关（调用方已读取）
	 * @return 转化尝试结果
	 */
	private ConversionOutcome attemptConversion(ResourceLocation beeTypeKey, Level level,
			boolean itemEnabled, boolean blockEnabled) {
		// 匹配缓存命中（饲养板内容与配置开关均未变化）：直接应用缓存结果，不再重扫饲养板
		// 负结果（无匹配）同样缓存：42 蜜蜂 × 60 槽无配方原料场景避免每次 flush 重复 O(N×M) 扫描
		int feederVersion = feederManager.getFlowerCacheVersion();
		if (beeTypeKey.equals(cachedMatchBeeType) && cachedMatchFeederVersion == feederVersion
				&& cachedMatchItemEnabled == itemEnabled && cachedMatchBlockEnabled == blockEnabled) {
			return cachedMatch == null ? ConversionOutcome.NONE : applyMatch(cachedMatch, level);
		}
		// 缓存未命中：扫描饲养板找第一个匹配项（每蜜蜂类型每饲养板状态每配置组合最多一次）
		cachedMatchBeeType = beeTypeKey;
		cachedMatchFeederVersion = feederVersion;
		cachedMatchItemEnabled = itemEnabled;
		cachedMatchBlockEnabled = blockEnabled;
		cachedMatch = findFirstMatch(beeTypeKey, itemEnabled, blockEnabled);
		if (cachedMatch == null) {
			return ConversionOutcome.NONE;
		}
		return applyMatch(cachedMatch, level);
	}

	/**
	 * 扫描饲养板，返回第一个匹配项（BlockItem 方块转化优先于物品转化，与 PB 一致）。
	 * <br/>
	 * 仅记录匹配槽索引与配方数据，不掷概率、不消耗——概率与消耗在 {@link #applyMatch} 执行。
	 *
	 * @return 匹配项，无则 null
	 */
	private ConversionMatch findFirstMatch(ResourceLocation beeTypeKey,
			boolean itemEnabled, boolean blockEnabled) {
		// 细分快速路径：该蜜蜂无对应类型配方时跳过（避免无谓遍历饲养板）
		boolean hasBlockRecipes = blockEnabled && BeeConversionQueries.hasBlockConversionRecipes(beeTypeKey);
		boolean hasItemRecipes = itemEnabled && BeeConversionQueries.hasItemConversionRecipes(beeTypeKey);
		if (!hasBlockRecipes && !hasItemRecipes) {
			return null;
		}
		for (int i = 0; i < feederManager.getFeederSlotCount(); i++) {
			IInventorySlot slot = feederManager.getFeederSlot(i);
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				continue;
			}
			// 饲养板 BlockItem 方块转化（对应 PB Feeder 内 BlockItem 转化分支）
			if (hasBlockRecipes) {
				try {
					RecipeHolder<BlockConversionRecipe> blockRecipe =
							BeeConversionQueries.findBlockConversionRecipeForItem(beeTypeKey, stack);
					if (blockRecipe != null) {
						BlockConversionRecipe recipe = blockRecipe.value();
						return new ConversionMatch(i, recipe.chance, recipe.pollinates,
								new ItemStack(recipe.stateTo.getBlock().asItem()));
					}
				} catch (Exception e) {
					// 单配方异常隔离：畸形配方（stateTo 为空等）只跳过当前槽位，不影响其他槽位与产出
					LogThrottle.warn("apiary_block_conversion_match",
							"蜂箱方块转化配方匹配异常，跳过槽位 {}", i, e);
				}
			}
			// 饲养板物品转化（对应 PB item_conversion 配方）
			if (hasItemRecipes) {
				try {
					RecipeHolder<ItemConversionRecipe> itemRecipe =
							BeeConversionQueries.findItemConversionRecipe(beeTypeKey, stack);
					if (itemRecipe != null) {
						ItemConversionRecipe recipe = itemRecipe.value();
						return new ConversionMatch(i, recipe.chance, recipe.pollinates,
								recipe.output.copy());
					}
				} catch (Exception e) {
					// 单配方异常隔离：畸形配方只跳过当前槽位
					LogThrottle.warn("apiary_item_conversion_match",
							"蜂箱物品转化配方匹配异常，跳过槽位 {}", i, e);
				}
			}
		}
		return null;
	}

	/**
	 * 应用匹配结果：按配方概率掷骰，成功则消耗原料并输出。
	 * <br/>
	 * 对齐 PB：即使概率失败，该周期同样被占用（调用方依据 pollinates 扣减 pendingProductions）。
	 */
	private ConversionOutcome applyMatch(ConversionMatch match, Level level) {
		try {
			IInventorySlot slot = feederManager.getFeederSlot(match.slotIndex);
			ItemStack stack = slot.getStack();
			if (stack.isEmpty()) {
				// 原料已被消耗（防御：缓存失效前被外部取走）
				return ConversionOutcome.NONE;
			}
			if (level.random.nextFloat() <= match.chance) {
				consumeFromFeeder(slot, stack);
				if (match.output != null && !match.output.isEmpty()) {
					outputItem(match.output.copy());
				}
			}
			return match.pollinates ? ConversionOutcome.POLLINATES : ConversionOutcome.NO_POLLINATES;
		} catch (Exception e) {
			// 单次转化应用异常隔离：不中断整箱产出 flush
			LogThrottle.warn("apiary_conversion_apply",
					"蜂箱转化应用异常（槽位 {}），本次转化跳过", match.slotIndex, e);
			return ConversionOutcome.NONE;
		}
	}

	/** 饲养板匹配结果快照（slotIndex + 配方数据，概率与消耗在执行时进行） */
	private static final class ConversionMatch {
		final int slotIndex;
		final boolean pollinates;
		final float chance;
		final ItemStack output;

		ConversionMatch(int slotIndex, float chance, boolean pollinates, ItemStack output) {
			this.slotIndex = slotIndex;
			this.chance = chance;
			this.pollinates = pollinates;
			this.output = output;
		}
	}

	/** 消耗饲养板槽位 1 个原料 */
	private void consumeFromFeeder(IInventorySlot slot, ItemStack stack) {
		ItemStack remaining = stack.copy();
		remaining.shrink(1);
		slot.setStack(remaining);
		tile.setChanged();
	}

	/** 将转化产物写入蜂箱输出槽；输出槽满载时送入溢出缓冲区下 tick 重试 */
	private void outputItem(ItemStack stack) {
		if (stack.isEmpty()) {
			return;
		}
		List<ItemStack> leftovers = outputDispatcher.distribute(slotManager.getOutputSlots(), List.of(stack));
		if (!leftovers.isEmpty()) {
			tile.getOutputBuffer().offer(leftovers);
		}
		tile.markDirectEjectDirty();
	}

	/** 转化尝试结果 */
	private enum ConversionOutcome {
		/** 未找到任何转化配方 */
		NONE,
		/** 找到配方并完成尝试，pollinates=true，不占用产出周期 */
		POLLINATES,
		/** 找到配方并完成尝试，pollinates=false，占用该产出周期（该周期不产出蜜脾） */
		NO_POLLINATES
	}
}
