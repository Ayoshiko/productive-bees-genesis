package com.ayoshiko.productivebeesgenesis.mek;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.Nullable;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * PB配方输出聚合器 — 封装配方输出的批量聚合与插入逻辑
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取，遵循单一职责原则：只负责将多次配方完成的输出
 * 累加到内存缓冲，达到阈值或 tick 结束时统一 flush 到输出槽，减少高倍加速下
 * insertItem/onContentsChanged 的调用次数。
 * <p>
 * 不持有任何进程级共享状态（pbOperatingTicks 等），仅管理自身的 pending 缓冲区，
 * 因此可安全地从协调器委托调用。配方变更时由调用方调用 {@link #resetPendingRecipe()}。
 * <p>
 * 线程安全：方块实体在服务端单线程执行，无需同步锁。
 */
public class PbRecipeCompleter {

	/** 触发 flush 的物品数量阈值（约一个栈），防止输出槽溢出 */
	public static final int PENDING_FLUSH_THRESHOLD = 64;

	/** PB配方处理上下文 */
	private final PbRecipeContext context;

	/** 可复用的输出槽列表（避免每次完成配方都创建新ArrayList） */
	private final List<IInventorySlot> reusableOutputSlots = new ArrayList<>(3);

	/**
	 * 本 tick 尚未插入的 PB 配方输出（按 ItemStack key 累加数量）
	 * <p>
	 * 使用 {@link IdentityHashMap}：key 来自 {@code pendingRecipeOutputs.entrySet()} 的
	 * {@code entry.getKey()}，同一配方的 key 实例稳定不变，引用相等即可。
	 * 避免每次 merge/get 调用 {@link ItemStack#hashCode()}（需遍历全部数据组件，开销高）。
	 */
	private final Map<ItemStack, Integer> pendingOutputs = new IdentityHashMap<>(4);

	/** 当前聚合输出对应的 PB 配方（用于 flush 时按原顺序插入） */
	@Nullable
	private CentrifugeRecipe pendingRecipe;

	/** 当前聚合输出对应的 PB 配方输出表（缓存避免每次重复创建 LinkedHashMap） */
	@Nullable
	private Map<ItemStack, ChancedOutput> pendingRecipeOutputs;

	/** 本 tick 尚未插入的流体输出模板（amount=0） */
	@Nullable
	private FluidStack pendingFluidTemplate;

	/** 本 tick 尚未插入的流体输出总量 */
	private int pendingFluidAmount;

	/** 本 tick 尚未扣除的输入数量（= 已完成配方数 × 生产力倍率） */
	private int pendingInputShrink;

	/** 本 tick 已聚合的物品总数量，用于触发提前 flush */
	private int pendingItemCount;

	public PbRecipeCompleter(PbRecipeContext context) {
		this.context = context;
	}

	/**
	 * 聚合一次 PB 配方完成所产生的输出。
	 * <br/>
	 * 不再立即调用 insertItem，而是把物品/流体数量累加到 {@link #pendingOutputs} 中，
	 * 在 tick 结束或达到阈值后统一 flush，显著减少高倍加速下的槽位 listener 触发次数。
	 *
	 * @param recipe               PB离心配方
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 */
	public void accumulatePbRecipeOutputs(CentrifugeRecipe recipe, int processIndex, int productivityModifier) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int modifier = Math.max(1, productivityModifier);

		if (pendingRecipe != null && pendingRecipe != recipe) {
			// 配方变更时先写入旧配方的聚合输出，再清空配方缓存
			flushPendingPbOutputs(processIndex);
			resetPendingRecipe();
		}
		pendingRecipe = recipe;
		if (pendingRecipeOutputs == null) {
			pendingRecipeOutputs = recipe.getRecipeOutputs();
		}

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			if (random.nextFloat() >= chanced.chance()) {
				continue;
			}
			int count = chanced.min();
			if (chanced.max() > chanced.min()) {
				count += random.nextInt(chanced.max() - chanced.min() + 1);
			}
			count *= modifier;
			if (count <= 0) {
				continue;
			}
			pendingOutputs.merge(entry.getKey(), count, Integer::sum);
			pendingItemCount += count;
		}

		FluidStack fluidOutput = recipe.getFluidOutputs();
		if (!fluidOutput.isEmpty()) {
			if (pendingFluidTemplate == null) {
				pendingFluidTemplate = fluidOutput.copyWithAmount(0);
			}
			pendingFluidAmount += fluidOutput.getAmount() * modifier;
		}

		pendingInputShrink += modifier;
	}

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入。
	 * <br/>
	 * 使用 {@link PbRecipeContext#productivebeesgenesis$beginOutputBatch()} /
	 * {@link PbRecipeContext#productivebeesgenesis$endOutputBatch(int)} 包装，
	 * 使输出槽 listener 只在本批次结束时扫描一次标志位。
	 *
	 * @param processIndex 进程索引
	 * @return true（与原有 completePbRecipe 语义一致，便于后续扩展）
	 */
	public boolean flushPendingPbOutputs(int processIndex) {
		// 修复：纯流体输出配方（如 oritech 石油蜜蜂的蜜脾）没有物品输出，但仍有流体和输入扣除待处理
		if (pendingRecipe == null
				|| (pendingOutputs.isEmpty() && pendingFluidAmount <= 0 && pendingInputShrink <= 0)) {
			clearPendingOutputs();
			return true;
		}

		context.productivebeesgenesis$beginOutputBatch();
		try {
			reusableOutputSlots.clear();
			reusableOutputSlots.add(context.primaryOutputSlot(processIndex));
			IInventorySlot secondary = context.secondaryOutputSlot(processIndex);
			if (secondary != null) {
				reusableOutputSlots.add(secondary);
			}
			reusableOutputSlots.add(context.tertiaryOutputSlot(processIndex));

			int slotIndex = 0;
			Map<ItemStack, ChancedOutput> recipeOutputs = pendingRecipeOutputs != null
					? pendingRecipeOutputs
					: pendingRecipe.getRecipeOutputs();
			for (Map.Entry<ItemStack, ChancedOutput> entry : recipeOutputs.entrySet()) {
				Integer count = pendingOutputs.get(entry.getKey());
				if (count == null || count <= 0) {
					continue;
				}
				ItemStack output = entry.getKey().copyWithCount(count);

				if (slotIndex < reusableOutputSlots.size()) {
					ItemStack remainder = reusableOutputSlots.get(slotIndex)
							.insertItem(output, Action.EXECUTE, AutomationType.INTERNAL);
					if (!remainder.isEmpty()) {
						for (int i = slotIndex + 1; i < reusableOutputSlots.size(); i++) {
							remainder = reusableOutputSlots.get(i)
									.insertItem(remainder, Action.EXECUTE, AutomationType.INTERNAL);
							if (remainder.isEmpty()) {
								break;
							}
						}
						// 输出槽满时静默丢弃（与原版行为一致，避免日志刷屏）
					}
				}
				slotIndex++;
			}

			if (pendingFluidTemplate != null && pendingFluidAmount > 0) {
				FluidStack scaledFluid = pendingFluidTemplate.copyWithAmount(pendingFluidAmount);
				IExtendedFluidTank tank = context.fluidOutputTank();
				if (tank != null) {
					tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}

			if (pendingInputShrink > 0) {
				context.inputSlot(processIndex).shrinkStack(pendingInputShrink, Action.EXECUTE);
			}
		} finally {
			context.productivebeesgenesis$endOutputBatch(processIndex);
			clearPendingOutputs();
		}
		return true;
	}

	/**
	 * 清空聚合输出缓存（保留当前配方引用与模板，便于同 tick 内继续累加同一配方）。
	 * <br/>
	 * 注意：不清空 {@link #pendingFluidTemplate}，因为同一配方的流体模板可以复用，
	 * 避免每次 flush 后重新调用 {@link CentrifugeRecipe#getFluidOutputs()}。
	 */
	private void clearPendingOutputs() {
		pendingOutputs.clear();
		pendingFluidAmount = 0;
		pendingInputShrink = 0;
		pendingItemCount = 0;
	}

	/** 配方变更或输入清空时重置聚合配方引用 */
	public void resetPendingRecipe() {
		pendingRecipe = null;
		pendingRecipeOutputs = null;
		pendingFluidTemplate = null;
		clearPendingOutputs();
	}

	/** 本 tick 已聚合的物品总数量（供协调器判断是否达到 flush 阈值） */
	public int pendingItemCount() {
		return pendingItemCount;
	}

	/** 本 tick 尚未扣除的输入数量（供协调器判断剩余输入是否足够） */
	public int pendingInputShrink() {
		return pendingInputShrink;
	}
}
