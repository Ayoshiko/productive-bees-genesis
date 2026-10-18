package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
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
import net.minecraft.nbt.CompoundTag;
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

	/** 升级处理器引用 — 用于应用生产力倍率 */
	private final ApiaryUpgradeHandler upgradeHandler;
	private final TileEntityMekApiary apiary;
	final ApiaryProductionState production;



	/** 产物分发器（直写输出槽 + 分段流体注入，复用数组跨 tick 零扩容） */
	private final BeeProduceOutputDispatcher outputDispatcher;
	private final MultiFlowerProductionCache multiFlowerProductionCache = new MultiFlowerProductionCache();
	private final BeeProductionGenerator generator = new BeeProductionGenerator(this, multiFlowerProductionCache);
	private final OrderedSlotIndex resourceIndex = new OrderedSlotIndex();
	private final BeeSlot[] resourceSlots = new BeeSlot[1];
	private final int[] resourceCounts = new int[1];


	/**
	 * 构造蜜蜂产出处理器
	 *
	 * @param upgradeHandler 升级处理器（提供生产力倍率等）
	 */
	public BeeProduceProcessor(ApiaryUpgradeHandler upgradeHandler, TileEntityMekApiary apiary) {
		this.upgradeHandler = upgradeHandler;
		this.apiary = apiary;
		this.production = apiary == null ? null : new ApiaryProductionState(apiary.getBeeSlotCount(), apiary::setChanged);
		resourceIndex.reset(1);
		resourceIndex.add(0);
		this.outputDispatcher = new BeeProduceOutputDispatcher(
				apiary == null ? null : apiary::productivebeesgenesis$outputContentsVersion);
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
									ApiaryOutputBuffer outputBuffer,
									ApiaryBatchUpgradeSnapshot upgrades, boolean multiFlowerBee) {
		var generated = generator.prepare(beeSlots, pendingCounts, groupSlotIndices, beeTypeKey, produceList,
				feederManager, origin, level, upgrades, multiFlowerBee, true, -1,
				upgrades.hasEssenceConversionUpgrade() && level != null ? EssenceConversionUpgradeHelper.plan(level) : null, null, 0);
		if (production != null) {
            production.freeze(generated);
            flushFrozen(upgrades);
        } else {
            if (pendingCounts != null && groupSlotIndices != null)
                for (int i = 0; i < groupSlotIndices.size(); i++) pendingCounts[groupSlotIndices.get(i)] = 0;
            dispatch(generated, slotManager, outputBuffer, upgrades);
        }
	}

	/** 已生成的本批结果独占物品与流体模板；准备阶段不扣费用、不推进槽位、不写入接收方。 */
	record Generated(List<ItemStack> items, FluidStack fluid, long fluidAmount) {
		static final Generated EMPTY = new Generated(List.of(), FluidStack.EMPTY, 0);
		Generated { items = List.copyOf(items); fluid = fluid.copy(); }
	}

	/** 资源接入只提供已预约的周期／轮数，实际产物与普通生产使用同一生成入口。 */
	Generated prepareResourceBatch(BeeSlot slot, int cycles, ResourceLocation type, Map<ItemStack, ChancedOutput> outputs,
			ApiaryBatchUpgradeSnapshot upgrades, long rolls, EssenceConversionUpgradeHelper.BatchPlan conversion,
			GeneSampler.Snapshot sampling, long seed, boolean nativeProduction) {
		resourceSlots[0] = slot;
		resourceCounts[0] = cycles;
		try {
			return generator.prepare(resourceSlots, resourceCounts, resourceIndex, type, outputs, apiary.getFeederSlotManager(), apiary.getBlockPos(),
					apiary.getLevel(), upgrades, MultiFlowerBeeAdapter.isMultiFlowerBee(type), nativeProduction, rolls, conversion, sampling, seed);
		} finally {
			resourceSlots[0] = null;
			resourceCounts[0] = 0;
		}
	}


	private void dispatch(Generated generated, ApiarySlotManager slotManager, ApiaryOutputBuffer outputBuffer,
			ApiaryBatchUpgradeSnapshot upgrades) {
		List<ItemStack> allItems = generated.items();
		var fluidTemplate = generated.fluid();
		long totalFluidAmount = generated.fluidAmount();

		// 批量插入合并后的物品到输出槽
		if (upgrades.directAeOutputEnabled() && !allItems.isEmpty()) {
			// 推 AE 前先按物品+组件聚合：四档生产力基因会为同一蜜脾各生成一个栈，
			// 逐栈 insert 就是 N 次完整 ME 网络遍历（昂贵外部存储单次 0.3-10ms）。
			// 聚合后 insert 次数 = 物品种类数，产物数量一件不少（AE insert 接受任意 count）。
			allItems = ItemStackMergeHelper.mergeForBulkTransfer(allItems);
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
		if (!allItems.isEmpty() && upgrades.centrifugeDirectTransferEnabled()) {
			allItems = apiary.directTransferProducedToCentrifuges(allItems);
		}
		// 产物直通：离心机直连之后、写输出槽之前，把产物直接交给已配置输出面的相邻容器。
		// 与离心机 PbRecipeFlusher 同级的收益（跳过输出槽中转、不占用输出容量）；
		// 待离心的蜜脾（shouldHoldForCentrifuge）不直通，否则离心机优先会被外部容器抢走产物。
		allItems = pushProducedToNeighbors(allItems, upgrades.directContainerOutputEnabled());
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
			if (upgrades.directAeOutputEnabled()) {
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
	 * 产物直通相邻容器 — 写输出槽前的最后一道快速通道
	 * <br/>
	 * 与离心机 {@code PbRecipeFlusher.pushPendingDirectToNeighbors} 同构的入账协议：
	 * 按<b>实际</b>接收量扣减（外部容器写入不可回滚），全部接收则移出列表，
	 * 部分接收则回写剩余数量继续走输出槽 → 缓冲区，因此永不丢物。
	 * <p>
	 * 待离心蜜脾（{@link TileEntityMekApiary#shouldHoldForCentrifuge}）跳过直通：
	 * 否则相邻箱子/管道会先把蜜脾拿走，离心机优先功能失效。
	 * <p>
	 * 性能：全部保留时返回原列表（零分配）；只在真正发生直通时才建新列表。
	 *
	 * @param stacks 产出列表（元素会被原地扣减 count）
	 * @param directContainerOutputEnabled per-tile 产物直通开关（由本轮快照提供，避免逐组重复读取）
	 * @return 未被相邻容器接收的剩余列表
	 */
	private List<ItemStack> pushProducedToNeighbors(List<ItemStack> stacks, boolean directContainerOutputEnabled) {
		if (stacks.isEmpty() || !directContainerOutputEnabled) return stacks;
		List<ItemStack> remaining = null;
		for (int i = 0; i < stacks.size(); i++) {
			ItemStack stack = stacks.get(i);
			if (stack == null || stack.isEmpty()) continue;
			if (!apiary.shouldHoldForCentrifuge(stack)) {
				int accepted = Math.max(0, Math.min(stack.getCount(),
						apiary.pushGeneratedItemToNeighbors(stack)));
				// 按实际接收量原地扣减：外部写入已发生，绝不能按请求量记账
				if (accepted > 0) stack.shrink(accepted);
			}
			if (!stack.isEmpty()) {
				if (remaining == null) remaining = new ArrayList<>(stacks.size());
				remaining.add(stack);
			}
		}
		return remaining == null ? stacks : remaining;
	}

	boolean flushFrozen(ApiaryBatchUpgradeSnapshot upgrades) {
        var custody = com.ayoshiko.productivebeesgenesis.logistics.OutputCustody.of(apiary);
        if (production.suspended() || custody.blocked()) return false;
        for (ItemStack stack : production.items()) {
            if (stack.isEmpty() || custody.blocked()) continue;
            if (upgrades.directAeOutputEnabled() && !apiary.shouldHoldForCentrifuge(stack)) {
                stack.shrink(apiary.pushGeneratedItemToAe(stack));
                apiary.setChanged();
            }
            if (!stack.isEmpty() && upgrades.centrifugeDirectTransferEnabled() && !custody.blocked()) {
                apiary.directTransferProducedToCentrifuges(List.of(stack));
                apiary.setChanged();
            }
            if (!stack.isEmpty() && upgrades.directContainerOutputEnabled() && !apiary.shouldHoldForCentrifuge(stack)) {
                stack.shrink(apiary.pushGeneratedItemToNeighbors(stack));
                apiary.setChanged();
            }
            if (!stack.isEmpty() && !custody.blocked()) {
                stack.shrink(com.ayoshiko.productivebeesgenesis.logistics.LocalOutputTransfer.insert(
                        apiary.getOutputSlots(), stack, custody));
                apiary.setChanged();
            }
            if (!stack.isEmpty()) {
                boolean hold = apiary.shouldHoldForCentrifuge(stack);
                stack.shrink(apiary.getOutputBuffer().accept(stack, hold));
                if (!stack.isEmpty() && hold && !custody.blocked()) stack.shrink(apiary.pushGeneratedItemToAe(stack));
                apiary.setChanged();
            }
        }
        production.itemsChanged();
        if (production.fluidAmount() > 0 && !custody.blocked()) {
            if (upgrades.directAeOutputEnabled())
                production.debitFluid(apiary.pushGeneratedFluidToAe(production.fluid(), production.fluidAmount()));
            if (production.fluidAmount() > 0 && !custody.blocked()) {
                int amount = (int) Math.min(Integer.MAX_VALUE, production.fluidAmount());
                FluidStack offered = production.fluid().copyWithAmount(amount);
                long handed = custody.handoffFluid(offered, amount, "apiary-tank", () ->
                        amount - apiary.getFluidTank().insert(offered.copy(), mekanism.api.Action.EXECUTE,
                                mekanism.api.AutomationType.INTERNAL).getAmount());
                production.debitFluid(handed);
            }
        }
        return !production.hasFrozen() && !custody.blocked();
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

		// 全花源模式按槽位和配方版本复用模板；随机模式仍每次抽样。
		if (MultiFlowerBeeAdapter.isMultiFlowerBee(beeTypeKey)) {
			List<ItemStack> feederItems = BalanceConfig.apiaryProduceAllFlowers()
					? multiFlowerProductionCache.get(beeTypeKey, feeder, level)
					: MultiFlowerBeeAdapter.sampleProduceFromFeeder(beeTypeKey, feeder, level);
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
