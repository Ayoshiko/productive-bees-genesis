package com.ayoshiko.productivebeesgenesis.mek;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.Nullable;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * PB配方输出聚合器 — 封装配方输出的批量聚合逻辑
 * <br/>
 * 从 {@link PbRecipeProcessor} 抽取,遵循单一职责原则:将多次配方完成的输出累加到内存缓冲,
 * 达到阈值或 tick 结束时由 {@link PbRecipeFlusher} 统一 flush 到输出槽,
 * 减少高倍加速下 insertItem/onContentsChanged 调用。
 * <p>
 * 职责拆分(M1-1):
 * <ul>
 *   <li>{@link PbRecipeCompleter} — 聚合缓冲区状态管理 + accumulate 方法</li>
 *   <li>{@link PbRecipeFlusher} — flush 执行(planAndExecute + 流体插入 + 输入扣除)</li>
 *   <li>{@link SampleUniformSum} — 均匀分布求和采样(CLT 数学工具)</li>
 * </ul>
 * <p>
 * 不持有进程级共享状态,仅管理自身 pending 缓冲区,可安全从协调器委托调用。
 * 配方变更时由调用方调用 {@link #resetPendingRecipe()}。
 * <p>
 * 线程安全:服务端单线程执行,无需同步锁。
 * 静态缓存 {@link #recipeOutputsCache} 使用 {@link ConcurrentHashMap} 保证 JEI 客户端
 * 与服务端 tick 并发访问安全。
 */
public class PbRecipeCompleter {

	/** 触发 flush 的物品数量阈值(约一个栈),防止输出槽溢出 */
	public static final int PENDING_FLUSH_THRESHOLD = 64;

	/**
	 * 静态缓存:按 CentrifugeRecipe 实例缓存 getRecipeOutputs() 结果
	 * <br/>
	 * PB 的 getRecipeOutputs() 每次新建 LinkedHashMap,256× 加速下累计 4-5 ms/tick。
	 * P0-3 修复:原 IdentityHashMap 非线程安全,JEI 客户端配方查询与服务端 tick 可能并发访问
	 * (invalidateRecipeOutputsCache 也会被外部调用),改为 ConcurrentHashMap 保证线程安全。
	 * CentrifugeRecipe 未重写 equals/hashCode,ConcurrentHashMap 默认使用 Object.equals/hashCode
	 * (引用相等语义),与原 IdentityHashMap 行为一致,无性能损失。
	 */
	private static final ConcurrentHashMap<CentrifugeRecipe, Map<ItemStack, ChancedOutput>> recipeOutputsCache = new ConcurrentHashMap<>();

	/** PB配方处理上下文 */
	private final PbRecipeContext context;

	/** flush 执行器 — 持有 simStacks 等执行相关实例字段,与 completer 生命周期一致 */
	private final PbRecipeFlusher flusher = new PbRecipeFlusher();

	/**
	 * 本 tick 尚未插入的 PB 配方输出(按 ItemStack key 累加数量)
	 * <br/>
	 * 使用 {@link IdentityHashMap}:key 实例稳定,引用相等即可,
	 * 避免 merge/get 调用 {@link ItemStack#hashCode()}(遍历数据组件,开销高)。
	 */
	private final Map<ItemStack, Integer> pendingOutputs = new IdentityHashMap<>(4);

	/** 当前聚合输出对应的 PB 配方(用于 flush 时按原顺序插入) */
	@Nullable
	private CentrifugeRecipe pendingRecipe;

	/** 当前聚合输出对应的 PB 配方输出表(缓存避免每次重复创建 LinkedHashMap) */
	@Nullable
	private Map<ItemStack, ChancedOutput> pendingRecipeOutputs;

	/** 本 tick 尚未插入的流体输出模板(amount=0) */
	@Nullable
	private FluidStack pendingFluidTemplate;

	/** 本 tick 尚未插入的流体输出总量(long 防止高倍加速下累加溢出) */
	private long pendingFluidAmount;

	/** 本 tick 尚未扣除的输入数量(= 已完成配方数 × 生产力倍率) */
	private int pendingInputShrink;

	/** 本 tick 已聚合的物品总数量,用于触发提前 flush */
	private int pendingItemCount;

	public PbRecipeCompleter(PbRecipeContext context) {
		this.context = context;
	}

	/**
	 * 聚合一次 PB 配方完成所产生的输出。
	 * <br/>
	 * 不立即调用 insertItem,而是把物品/流体数量累加到 {@link #pendingOutputs},
	 * 在 tick 结束或达到阈值后统一 flush,减少高倍加速下 listener 触发次数。
	 *
	 * @param recipe               PB离心配方
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 */
	public void accumulatePbRecipeOutputs(CentrifugeRecipe recipe, int processIndex, int productivityModifier) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int modifier = Math.max(1, productivityModifier);

		// 每进程独立 completer,无需配方切换检测
		this.pendingRecipe = recipe;
		if (pendingRecipeOutputs == null) {
			pendingRecipeOutputs = recipeOutputsCache.computeIfAbsent(recipe, CentrifugeRecipe::getRecipeOutputs);
		}

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			// chance >= 1.0 必定通过,跳过 nextFloat
			if (chance < 1.0f && random.nextFloat() >= chance) {
				continue;
			}
			int count = chanced.min();
			int max = chanced.max();
			if (max > count) {
				count += random.nextInt(max - count + 1);
			}
			// long 域计算防止溢出为负,溢出截断到 Integer.MAX_VALUE
			long totalCount = (long) count * modifier;
			if (totalCount <= 0) {
				continue;
			}
			count = (int) Math.min(totalCount, Integer.MAX_VALUE);
			pendingOutputs.merge(entry.getKey(), count, Integer::sum);
			pendingItemCount += count;
		}

		FluidStack fluidOutput = recipe.getFluidOutputs();
		if (!fluidOutput.isEmpty()) {
			if (pendingFluidTemplate == null || pendingFluidTemplate.isEmpty()) {
				// 1.21修复:copyWithAmount(0) 会返回 isEmpty() 的 FluidStack,使用 copy() 保留原始 amount
				pendingFluidTemplate = fluidOutput.copy();
			}
			pendingFluidAmount += (long) fluidOutput.getAmount() * modifier;
		}

		// 修复:每次操作只消耗1个输入,productivityModifier 只影响输出数量不影响输入消耗
		pendingInputShrink += 1;
	}

	/**
	 * 批量聚合 N 次 PB 配方完成的输出 — 使用统计期望值计算,将 N 次循环减为 O(outputs) 次。
	 * <br/>
	 * 设计动机:STACK 升级满级时 effectiveOps=65536,原版循环是 TPS 下降首要根因(20+ ms/tick)。
	 * <p>
	 * 数学等价性(N=1 时与 {@link #accumulatePbRecipeOutputs} 完全一致):
	 * <ul>
	 *   <li>chance=1.0 且 min=max: N * min * modifier(无随机)</li>
	 *   <li>chance=1.0 且 min&lt;max: Normal 近似 N 次 [min,max] 均匀分布之和(CLT)</li>
	 *   <li>chance&lt;1.0: 保底机制 + 自适应 Binomial 采样(委托 {@link BatchProbabilitySampler})</li>
	 * </ul>
	 * N&gt;1 时分布近似但数学期望一致。概率产物采样:remaining≤30 精确 Binomial;
	 * remaining&gt;30 且 λ&lt;5 Poisson;remaining&gt;30 且 λ≥5 CLT 正态近似。
	 *
	 * @param recipe               PB离心配方
	 * @param processIndex         进程索引
	 * @param productivityModifier 生产力倍率
	 * @param batchCount           批量操作数(必须 &gt; 0)
	 */
	public void accumulatePbRecipeOutputsBatch(CentrifugeRecipe recipe, int processIndex,
			int productivityModifier, int batchCount) {
		if (batchCount <= 0) return;
		if (batchCount == 1) {
			// N=1 走原版路径,保持完全等价
			accumulatePbRecipeOutputs(recipe, processIndex, productivityModifier);
			return;
		}
		ThreadLocalRandom random = ThreadLocalRandom.current();
		int modifier = Math.max(1, productivityModifier);

		this.pendingRecipe = recipe;
		if (pendingRecipeOutputs == null) {
			pendingRecipeOutputs = recipeOutputsCache.computeIfAbsent(recipe, CentrifugeRecipe::getRecipeOutputs);
		}

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			int min = chanced.min();
			int max = chanced.max();
			if (max < min) max = min; // 防御性处理

			long totalCount;
			if (chance >= 1.0f) {
				// 必定通过 — 直接采样 N 次 [min, max] 之和
				totalCount = SampleUniformSum.sample(random, min, max, batchCount, modifier);
			} else {
				// chance < 1.0 — 保底机制 + 自适应 Binomial 采样(SubTask 4.2/4.3/4.4)
				// 委托 BatchProbabilitySampler:N≤30 精确 Binomial;N>30 且 λ<5 Poisson;否则 CLT
				// 保底:guaranteed=floor(N×p) 确定性产量 + Binomial(remaining, adjustedP) 随机部分
				long k = BatchProbabilitySampler.sampleBinomialWithGuarantee(random, batchCount, chance);
				if (k <= 0) {
					continue;
				}
				totalCount = SampleUniformSum.sample(random, min, max, k, modifier);
			}

			if (totalCount <= 0) {
				continue;
			}
			int countInt = (int) Math.min(totalCount, Integer.MAX_VALUE);
			pendingOutputs.merge(entry.getKey(), countInt, Integer::sum);
			pendingItemCount += countInt;
		}

		FluidStack fluidOutput = recipe.getFluidOutputs();
		if (!fluidOutput.isEmpty()) {
			if (pendingFluidTemplate == null || pendingFluidTemplate.isEmpty()) {
				pendingFluidTemplate = fluidOutput.copy();
			}
			pendingFluidAmount += (long) fluidOutput.getAmount() * modifier * batchCount;
		}
		pendingInputShrink += batchCount;
	}

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入 — 委托给 {@link PbRecipeFlusher}
	 * <br/>
	 * 调用方语义保持不变:返回 true 全部输出成功;false 空间不足未执行任何修改。
	 *
	 * @param processIndex 进程索引
	 * @return true 全部输出成功插入并扣除输入;false 输出空间不足,未执行任何修改
	 */
	public boolean flushPendingPbOutputs(int processIndex) {
		return flusher.flush(this, processIndex);
	}

	/**
	 * 清空聚合输出缓存(保留当前配方引用与模板,便于同 tick 内继续累加同一配方)。
	 * <br/>
	 * 不清空 {@link #pendingFluidTemplate},同配方流体模板可复用,避免重新调用 getFluidOutputs()。
	 */
	void clearPendingOutputs() {
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

	/** 本 tick 已聚合的物品总数量(供协调器判断是否达到 flush 阈值) */
	public int pendingItemCount() {
		return pendingItemCount;
	}

	/** 本 tick 尚未扣除的输入数量(供协调器判断剩余输入是否足够) */
	public int pendingInputShrink() {
		return pendingInputShrink;
	}

	/** 清空静态配方输出缓存 — 配方重载时由主类调用,防止使用过期 getRecipeOutputs 结果 */
	public static void invalidateRecipeOutputsCache() {
		recipeOutputsCache.clear();
	}

	// ===== 包私有 getter — 供 PbRecipeFlusher 访问 pending 状态 =====
	// 这些访问器仅限同包使用,不对外暴露内部聚合状态,保持封装性。

	/** @return PB配方处理上下文 */
	PbRecipeContext getContext() {
		return context;
	}

	/** @return 当前聚合的 PB 配方(可能为 null) */
	@Nullable
	CentrifugeRecipe getPendingRecipe() {
		return pendingRecipe;
	}

	/** @return 当前聚合输出的配方输出表(可能为 null) */
	@Nullable
	Map<ItemStack, ChancedOutput> getPendingRecipeOutputs() {
		return pendingRecipeOutputs;
	}

	/** @return 本 tick 尚未插入的物品输出(IdentityHashMap,引用相等 key) */
	Map<ItemStack, Integer> getPendingOutputs() {
		return pendingOutputs;
	}

	/** @return 本 tick 尚未插入的流体输出模板(可能为 null) */
	@Nullable
	FluidStack getPendingFluidTemplate() {
		return pendingFluidTemplate;
	}

	/** @return 本 tick 尚未插入的流体输出总量 */
	long getPendingFluidAmount() {
		return pendingFluidAmount;
	}

	/** @return 本 tick 尚未扣除的输入数量 */
	int getPendingInputShrink() {
		return pendingInputShrink;
	}

	/**
	 * 静态缓存查询 — 供 PbRecipeFlusher 在 pendingRecipeOutputs 为 null 时回退使用
	 *
	 * @param recipe PB配方
	 * @return 配方输出表(可能为 null,若 recipe 为 null)
	 */
	@Nullable
	static Map<ItemStack, ChancedOutput> getRecipeOutputsCached(@Nullable CentrifugeRecipe recipe) {
		if (recipe == null) return null;
		return recipeOutputsCache.computeIfAbsent(recipe, CentrifugeRecipe::getRecipeOutputs);
	}
}
