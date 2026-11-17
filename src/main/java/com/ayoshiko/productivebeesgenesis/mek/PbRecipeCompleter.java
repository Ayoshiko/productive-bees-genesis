package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor.LocalBatch.Completion;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.function.IntConsumer;

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
	private static final String NBT_PENDING_COUNT = "productivebeesgenesis_count";

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
	private static final ConcurrentHashMap<CentrifugeRecipe, Map<ItemStack,
		ChancedOutput>> recipeOutputsCache = new ConcurrentHashMap<>();

	/** PB配方处理上下文 */
	private final PbRecipeContext context;
	private final int lane;

	/** flush 执行器 — 持有 simStacks 等执行相关实例字段,与 completer 生命周期一致 */
	private final PbRecipeFlusher flusher;
	private final PendingTemplates pendingTemplates;
	private PbFactoryOutputBatch factoryOutputBatch;
	private MyriadFluidOutputHandler myriadFluidOutput;

	/**
	 * 本 tick 尚未插入的 PB 配方输出(按 ItemStack key 累加数量)
	 * <br/>
	 * 按引用识别模板并保留插入顺序；转换上界与实际产物必须以同一顺序认领空槽，
	 * 否则较小产物抢槽会使已通过上界检查的批次被误拒收。
	 */
	private Map<ItemStack, Long> pendingOutputs = new Reference2ObjectLinkedOpenHashMap<>(4);
	/** 普通与万象的未付生成结果独占此缓冲，成功提交后与正式 map 交换。 */
	private PbRecipeCompleter completedBatch;

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
	private long pendingItemCount;
	private ItemStack pendingInputTemplate = ItemStack.EMPTY;
	private java.util.UUID paymentRequest;
	private boolean paymentPending;
	private boolean paymentKnown;
	private long paymentEnergy;
	private int paymentProgress;
	/** 已完成转换的组合批次重试时只排空，不再随升级或配方重载转换。 */
	private boolean preparedOutput;
	private boolean outputUpgradesPrepared;
	private int outputUpgradeMask;
	private long outputUpgradeRevision;
	private CompoundTag invalidPending;
	private PendingFailure retainedPendingFailure;
	/** 只缓存准备结果；每次准入仍复核当前槽位和外部路由状态。 */
	private PbOutputCapacityCache capacityCache;
	private NotificationFailure[] notificationFailures;

	private enum NotificationKind {
		PAYMENT("centrifuge_payment_notification"),
		INPUT("centrifuge_input_notification"),
		SAVE("centrifuge_save_notification");

		final String logKey;

		NotificationKind(String logKey) {
			this.logKey = logKey;
		}
	}

	private static final class NotificationFailure {
		final String phase;
		final Throwable cause;
		final BlockPos position;
		final java.util.UUID request;
		long repeats;
		long lastSummaryNanos = System.nanoTime();

		NotificationFailure(String phase, Throwable cause, BlockPos position, java.util.UUID request) {
			this.phase = phase;
			this.cause = cause;
			this.position = position;
			this.request = request;
		}
	}

	public PbRecipeCompleter(PbRecipeContext context) {
		this(context, -1);
	}

	public PbRecipeCompleter(PbRecipeContext context, int lane) {
		this(context, new PbRecipeFlusher(), new PendingTemplates(), lane);
	}

	private PbRecipeCompleter(PbRecipeContext context, PbRecipeFlusher flusher, PendingTemplates pendingTemplates, int lane) {
		this.context = context;
		this.lane = lane;
		this.flusher = flusher;
		this.pendingTemplates = pendingTemplates;
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
		beforePendingMutation();
		accumulateSingle(recipe, productivityModifier, ThreadLocalRandom.current());
	}

	private void accumulateSingle(CentrifugeRecipe recipe, int productivityModifier, java.util.random.RandomGenerator random) {
		int modifier = Math.max(1, productivityModifier);
		// stability bonus 循环外获取一次（EnumMap O(1) 查询，不在循环内重复）
		float stabilityBonus = context.stabilityBonus();

		// v2.0.9 修复产物锁定 bug：配方变更时同步重置 pendingRecipe 和 pendingRecipeOutputs
		// 原代码每次都赋值 pendingRecipe（无脑赋值），仅当 pendingRecipeOutputs == null 时加载
		// 新代码仅当配方变更时更新，避免残留旧配方的 outputs
		selectRecipe(recipe);
		boolean discardWax = context.suppressesUselessByproducts();

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			if (discardWax && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			// stability bonus 提升非保底产物概率，截断到 1.0
			float adjustedChance = (float) PbOutputChance.adjustedChance(chance, stabilityBonus);
			// adjustedChance >= 1.0 必定通过,跳过 nextFloat
			if (adjustedChance < 1.0f && random.nextFloat() >= adjustedChance) {
				continue;
			}
			int min = Math.max(0, chanced.min());
			int max = Math.max(min, chanced.max());
			int count = SampleUniformSum.sampleSingle(random, min, max);
			long totalCount = Math.multiplyExact((long) count, modifier);
			if (totalCount <= 0) {
				continue;
			}
			addPendingOutput(entry.getKey(), totalCount);
			pendingItemCount = Math.addExact(pendingItemCount, totalCount);
		}

		FluidStack fluidOutput = pendingFluidTemplate;
		if (fluidOutput != null && !fluidOutput.isEmpty()
				&& !(discardWax && UselessByproductUpgradeHelper.isHoney(fluidOutput))) {
			pendingFluidAmount = Math.addExact(pendingFluidAmount,
				Math.multiplyExact((long) fluidOutput.getAmount(), modifier));
		}

		// 修复:每次操作只消耗1个输入,productivityModifier 只影响输出数量不影响输入消耗
		pendingInputShrink = Math.addExact(pendingInputShrink, 1);
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
		beforePendingMutation();
		accumulateBatch(recipe, productivityModifier, batchCount, ThreadLocalRandom.current());
	}

	private void accumulateBatch(CentrifugeRecipe recipe, int productivityModifier, int batchCount,
			java.util.random.RandomGenerator random) {
		if (batchCount <= 0) return;
		if (batchCount == 1) {
			// N=1 走原版路径,保持完全等价
			accumulateSingle(recipe, productivityModifier, random);
			return;
		}
		int modifier = Math.max(1, productivityModifier);
		// stability bonus 循环外获取一次（EnumMap O(1) 查询，不在循环内重复）
		float stabilityBonus = context.stabilityBonus();

		// v2.0.9 修复产物锁定 bug：配方变更时同步重置（与 accumulatePbRecipeOutputs 保持一致）
		selectRecipe(recipe);
		boolean discardWax = context.suppressesUselessByproducts();

		for (Map.Entry<ItemStack, ChancedOutput> entry : pendingRecipeOutputs.entrySet()) {
			if (discardWax && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			ChancedOutput chanced = entry.getValue();
			float chance = chanced.chance();
			int min = Math.max(0, chanced.min());
			int max = chanced.max();
			if (max < min) max = min; // 防御性处理

			// stability bonus 提升非保底产物概率，截断到 1.0
			float adjustedChance = (float) PbOutputChance.adjustedChance(chance, stabilityBonus);

			long totalCount;
			if (adjustedChance >= 1.0f) {
				// 必定通过 — 直接采样 N 次 [min, max] 之和
				totalCount = SampleUniformSum.sample(random, min, max, batchCount, modifier);
			} else {
				// adjustedChance < 1.0 — 保底机制 + 自适应 Binomial 采样(SubTask 4.2/4.3/4.4)
				// 委托 BatchProbabilitySampler:N≤30 精确 Binomial;N>30 且 λ<5 Poisson;否则 CLT
				// 保底:guaranteed=floor(N×p) 确定性产量 + Binomial(remaining, adjustedP) 随机部分
				long k = BatchProbabilitySampler.sampleBinomialWithGuarantee(random, batchCount, adjustedChance);
				if (k <= 0) {
					continue;
				}
				totalCount = SampleUniformSum.sample(random, min, max, k, modifier);
			}

			if (totalCount <= 0) {
				continue;
			}
			addPendingOutput(entry.getKey(), totalCount);
			pendingItemCount = Math.addExact(pendingItemCount, totalCount);
		}

		FluidStack fluidOutput = pendingFluidTemplate;
		if (fluidOutput != null && !fluidOutput.isEmpty()
				&& !(discardWax && UselessByproductUpgradeHelper.isHoney(fluidOutput))) {
			pendingFluidAmount = Math.addExact(pendingFluidAmount,
				Math.multiplyExact((long) fluidOutput.getAmount() * modifier, batchCount));
		}
		pendingInputShrink = Math.addExact(pendingInputShrink, batchCount);
	}

	/** 在原聚合器中准备独占结果；不修改本实例、库存、进度或费用。 */
	PbRecipeCompleter prepare(CentrifugeRecipe recipe, int inputs, long seed, boolean maximum) {
		var batch = new PbRecipeCompleter(context, flusher, pendingTemplates, lane);
		prepareInto(batch, recipe, inputs, seed, maximum);
		return batch;
	}

	/** 只有完整生成和升级后的结果才能进入正式车道；失败残留只存在临时缓冲。 */
	PbRecipeCompleter prepareCompleted(CentrifugeRecipe recipe, int inputs, long seed) {
		PbRecipeCompleter batch = completedBatch();
		prepareInto(batch, recipe, inputs, seed, false);
		return batch;
	}

	/** 模板按原选型顺序抽取，输出按分配顺序直接进入独占批次，只复制一次共享模板。 */
	PbRecipeCompleter prepareMyriadCompleted(List<ResourceLocation> selected, Map<ResourceLocation, Long> allocation,
			Map<ResourceLocation, List<ItemStack>> variants, RandomSource random, FluidStack fluid, long amount,
			ItemStack input, int inputs) {
		PbRecipeCompleter batch = completedBatch();
		batch.clearPendingOutputs();
		batch.resetPendingRecipe();
		ItemStack[] templates = new ItemStack[selected.size()];
		for (int index = 0; index < selected.size(); index++) {
			List<ItemStack> choices = variants.get(selected.get(index));
			if (choices == null || choices.isEmpty()) continue;
			templates[index] = choices.get(choices.size() == 1 ? 0 : random.nextInt(choices.size()));
		}
		for (var entry : allocation.entrySet()) {
			ItemStack template = templates[selected.indexOf(entry.getKey())];
			if (template == null || template.isEmpty()) return null;
			batch.addPendingOutput(template.copyWithCount(1), entry.getValue());
			batch.pendingItemCount = Math.addExact(batch.pendingItemCount, entry.getValue());
		}
		batch.pendingFluidTemplate = fluid.isEmpty() ? null : fluid.copyWithAmount(1);
		batch.pendingFluidAmount = amount;
		batch.pendingInputShrink = inputs;
		batch.freezeCompletedBatch(input);
		return batch;
	}

	void bindMyriadFluidOutput(MyriadFluidOutputHandler handler) {
		myriadFluidOutput = handler;
	}

	private PbRecipeCompleter completedBatch() {
		if (completedBatch == null) {
			completedBatch = new PbRecipeCompleter(context, flusher, pendingTemplates, lane);
		}
		return completedBatch;
	}

	int maximumRepresentableInputs(CentrifugeRecipe recipe) {
		if (capacityCache == null) capacityCache = new PbOutputCapacityCache(this, context);
		return capacityCache.maximumRepresentableInputs(recipe);
	}

	boolean fitsMaximum(CentrifugeRecipe recipe, int lane, int inputs) {
		if (capacityCache == null) capacityCache = new PbOutputCapacityCache(this, context);
		return capacityCache.fits(recipe, lane, inputs);
	}

	private void prepareInto(PbRecipeCompleter batch, CentrifugeRecipe recipe, int inputs, long seed, boolean maximum) {
		batch.clearPendingOutputs();
		if (maximum) {
			batch.selectRecipe(recipe);
			int modifier = Math.max(1, context.productivityModifier());
			for (var entry : batch.pendingRecipeOutputs.entrySet()) {
				var out = entry.getValue();
				if (PbOutputChance.adjustedChance(out.chance(), context.stabilityBonus()) <= 0) continue;
				int max = Math.max(Math.max(0, out.min()), out.max());
				if (context.suppressesUselessByproducts() && UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
				long amount = Math.multiplyExact((long) max * modifier, inputs);
				batch.addPendingOutput(entry.getKey(), amount);
				batch.pendingItemCount = Math.addExact(batch.pendingItemCount, amount);
			}
			if (batch.pendingFluidTemplate != null && !(context.suppressesUselessByproducts()
					&& UselessByproductUpgradeHelper.isHoney(batch.pendingFluidTemplate))) {
				long perInput = (long) batch.pendingFluidTemplate.getAmount() * modifier;
				batch.pendingFluidAmount = Math.multiplyExact(perInput, inputs);
			}
		} else batch.accumulateBatch(recipe, context.productivityModifier(), inputs, new java.util.Random(seed));
		batch.prepareOutputUpgrades(maximum);
		if (context.suppressesUselessByproducts() && batch.pendingFluidTemplate != null
				&& UselessByproductUpgradeHelper.isHoney(batch.pendingFluidTemplate)) batch.pendingFluidAmount = 0;
		batch.pendingInputShrink = 0;
		batch.preparedOutput = true;
	}

	/** 普通与组合共用副产物、精华、粗矿顺序；上界保留可能出现的转换余料。 */
	void prepareOutputUpgrades(boolean maximum) {
		if (preparedOutput) return;
		int mask = (context.suppressesUselessByproducts() ? 1 : 0)
				| (context.productivebeesgenesis$hasEssenceConversionUpgrade() ? 2 : 0)
				| (context.productivebeesgenesis$hasRawOreSmeltingUpgrade() ? 4 : 0)
				| (maximum ? 8 : 0);
		long revision = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (outputUpgradesPrepared && outputUpgradeMask == mask && outputUpgradeRevision == revision) return;
		discardSuppressedWaxOutputs();
		if (maximum) {
			if (context.productivebeesgenesis$hasEssenceConversionUpgrade())
				EssenceConversionUpgradeHelper.maximumPendingLongOutputs(context.level(), pendingOutputs);
			if (context.productivebeesgenesis$hasRawOreSmeltingUpgrade())
				RawOreSmeltingUpgradeHelper.maximumPendingLongOutputs(context.level(), pendingOutputs);
		} else {
			convertPendingEssenceOutputs();
			convertPendingRawOreOutputs();
		}
		if (maximum) {
			pendingItemCount = 0;
			for (long amount : pendingOutputs.values()) pendingItemCount = Math.addExact(pendingItemCount, amount);
		}
		outputUpgradesPrepared = true;
		outputUpgradeMask = mask;
		outputUpgradeRevision = revision;
	}

	boolean fitsPrepared(int lane, int inputs) { return flusher.fitsPrepared(this, lane, inputs); }
	boolean isPreparedOutput() { return preparedOutput; }
	boolean invalidPending() { return invalidPending != null || retainedPendingFailure != null; }
	boolean outputUncertainPending() { return retainedPendingFailure != null && retainedPendingFailure.kind().equals("output_failure")
			|| invalidPending != null && invalidPending.contains("output_failure", Tag.TAG_COMPOUND); }

	/** 输入已由唯一协调者消费后转交一次；准备对象不再保留另一份可排空库存。 */
	void acceptPrepared(PbRecipeCompleter batch) {
		beforePendingMutation();
		if (hasPendingOutputs() || pendingInputShrink != 0 || invalidPending())
			throw new IllegalStateException("Previous centrifuge output still owned");
		takePrepared(batch);
		context.productivebeesgenesis$markForSave();
	}

	/** 交换独占 map，不在正式所有者中逐项生成；原结果只保留一份。 */
	private void takePrepared(PbRecipeCompleter batch) {
		pendingRecipe = batch.pendingRecipe;
		pendingRecipeOutputs = batch.pendingRecipeOutputs;
		Map<ItemStack, Long> reusable = pendingOutputs;
		pendingOutputs = batch.pendingOutputs;
		batch.pendingOutputs = reusable;
		pendingFluidTemplate = batch.pendingFluidTemplate;
		pendingFluidAmount = batch.pendingFluidAmount;
		pendingItemCount = batch.pendingItemCount;
		preparedOutput = true;
		batch.clearPendingOutputs();
	}

	/** 付款状态先于正式产物接管和保存；异常结果只锁定当前车道。 */
	boolean payForPrepared(PbRecipeCompleter batch, ItemStack input, int inputs,
			PbVirtualTickPlan plan, Runnable commitProgress) {
		beforePendingMutation();
		if (paymentPending) return false;
		if (batch != null && (hasPendingOutputs() || pendingInputShrink != 0 || invalidPending())) {
			throw new IllegalStateException("Previous centrifuge output still owned");
		}
		ItemStack inputTemplate = inputs == 0 ? ItemStack.EMPTY : input.copyWithCount(1);
		beginPayment(plan);
		if (batch != null) {
			takePrepared(batch);
			pendingInputTemplate = inputTemplate;
			pendingInputShrink = inputs;
		}
		return finishPayment(commitProgress);
	}


	/** 完成时固定采样与转换；输入仍在源槽时也可保存，拒收不能重新采样。 */
	void freezeCompletedBatch(ItemStack input) {
		beforePendingMutation();
		prepareOutputUpgrades(false);
		if (context.suppressesUselessByproducts() && pendingFluidTemplate != null
				&& UselessByproductUpgradeHelper.isHoney(pendingFluidTemplate)) pendingFluidAmount = 0;
		pendingInputTemplate = input.copyWithCount(1);
		preparedOutput = true;
	}

	/** 万象的确定模板与数量使用同一个已付缓冲，不再通过输出容量重选产物。 */
	void freezeMyriadBatch(Map<ItemStack, Long> items, FluidStack fluid, long fluidAmount,
			ItemStack input, int inputs) {
		beforePendingMutation();
		if (hasPendingOutputs() || pendingInputShrink != 0 || invalidPending())
			throw new IllegalStateException("Previous centrifuge output still owned");
		resetPendingRecipe();
		for (var entry : items.entrySet()) {
			addPendingOutput(entry.getKey().copyWithCount(1), entry.getValue());
			pendingItemCount = Math.addExact(pendingItemCount, entry.getValue());
		}
		pendingFluidTemplate = fluid.isEmpty() ? null : fluid.copyWithAmount(1);
		pendingFluidAmount = fluidAmount;
		pendingInputShrink = inputs;
		freezeCompletedBatch(input);
	}

	/** 付款请求在扣能之前保存；异常或短扣保持请求，禁止排空及重新收费。 */
	boolean payForPlan(PbVirtualTickPlan plan, Runnable commitProgress) {
		if (paymentPending) return false;
		beginPayment(plan);
		return finishPayment(commitProgress);
	}

	private void beginPayment(PbVirtualTickPlan plan) {
		paymentPending = true;
		paymentKnown = true;
		paymentEnergy = plan.energyUsed();
		paymentProgress = plan.remainingProgress();
	}

	private boolean finishPayment(Runnable commitProgress) {
		notifyPaymentChange();
		if (paymentEnergy > 0 && !debitPaymentEnergy()) return false;
		commitProgress.run();
		paymentRequest = null;
		paymentPending = false;
		paymentKnown = false;
		paymentEnergy = 0;
		notifyPaymentChange();
		return true;
	}

	/** 原生容器先提交余额再通知；同次调用按实际差量只保留尚未扣除的费用。 */
	private boolean debitPaymentEnergy() {
		var container = context.energyContainer();
		long before = ((BasicEnergyContainerAccessor) container).productivebeesgenesis$getStored();
		long requested = paymentEnergy;
		long charged;
		paymentKnown = false;
		try {
			charged = container.extract(requested, Action.EXECUTE, AutomationType.INTERNAL);
			completeNotificationRecovery(NotificationKind.PAYMENT);
		} catch (RuntimeException | LinkageError failure) {
			charged = before - ((BasicEnergyContainerAccessor) container).productivebeesgenesis$getStored();
			if (charged < 0 || charged > requested) {
				reportPaymentFailure("本地扣费结果无法核对，原请求继续保管", failure);
				notifyPaymentChange();
				return false;
			}
			reportPaymentFailure("本地扣费通知失败，按已提交余额结清实际费用", failure);
		}
		paymentEnergy -= charged;
		paymentKnown = true;
		if (paymentEnergy > 0) notifyPaymentChange();
		return paymentEnergy == 0;
	}

	/** 保存通知仅标记状态，不接收资产；失败不能把已知未扣费用变成未知扣取。 */
	private void notifyPaymentChange() {
		try {
			context.productivebeesgenesis$markForSave();
			completeNotificationRecovery(NotificationKind.SAVE);
		} catch (RuntimeException | LinkageError failure) {
			reportNotificationFailure(NotificationKind.SAVE, "付款保存通知失败，保留原进度、输入与费用并继续结算", failure);
		}
	}

	private void reportPaymentFailure(String phase, Throwable failure) {
		reportNotificationFailure(NotificationKind.PAYMENT, phase, failure);
	}

	private void reportNotificationFailure(NotificationKind kind, String phase, Throwable failure) {
		if (notificationFailures == null) notificationFailures = new NotificationFailure[NotificationKind.values().length];
		NotificationFailure first = notificationFailures[kind.ordinal()];
		if (first == null) {
			BlockPos position = context instanceof BlockEntity entity ? entity.getBlockPos().immutable() : null;
			first = new NotificationFailure(phase, failure, position, paymentRequest());
			notificationFailures[kind.ordinal()] = first;
			LogThrottle.errorWithCooldown(kind.logKey, 60_000L,
					"离心机 {}，车道 {}，位置 {}，付款请求 {}；保留已确认资产与原故障首因",
					first.phase, lane, first.position, first.request, first.cause);
			return;
		}
		first.repeats++;
		long now = System.nanoTime();
		if (now - first.lastSummaryNanos >= 60_000_000_000L) {
			first.lastSummaryNanos = now;
			LogThrottle.warnWithCooldown(kind.logKey, 60_000L,
					"离心机通知故障持续，车道 {}，位置 {}，付款请求 {}，原阶段 {}，首因 {}，期间重复 {} 次",
					lane, first.position, first.request, first.phase, first.cause.toString(), first.repeats);
		}
	}

	private void completeNotificationRecovery(NotificationKind kind) {
		if (notificationFailures == null) return;
		NotificationFailure first = notificationFailures[kind.ordinal()];
		if (first == null) return;
		notificationFailures[kind.ordinal()] = null;
		LogThrottle.infoWithCooldown("centrifuge_notification_recovery", 60_000L,
				"离心机通知已恢复，车道 {}，位置 {}，付款请求 {}，原阶段 {}，首因 {}，期间重复 {} 次",
				lane, first.position, first.request, first.phase, first.cause.toString(), first.repeats);
	}

	void clearTransientFailures() {
		notificationFailures = null;
	}

	boolean paymentPending() { return paymentPending; }
	boolean knownPaymentPending() { return paymentPending && (paymentKnown || paymentEnergy == 0); }

	java.util.UUID paymentRequest() {
		if (paymentPending && paymentRequest == null) paymentRequest = java.util.UUID.randomUUID();
		return paymentRequest;
	}
	long paymentEnergy() { return paymentEnergy; }

	/** 旧零费用及本地已知余费自动收尾；无凭据的旧正费用请求不能重新扣取。 */
	boolean recoverKnownPayment(int lane, IntConsumer commitProgress) {
		if (!knownPaymentPending() || invalidPending()) return false;
		java.util.UUID request = paymentRequest();
		if (paymentEnergy > 0 && !debitPaymentEnergy()) return false;
		commitProgress.accept(paymentProgress);
		paymentRequest = null;
		paymentPending = false;
		paymentKnown = false;
		LogThrottle.infoWithCooldown("centrifuge_known_payment_recovery", 60_000L,
				"离心机自动结清已知付款：请求 {}，车道 {}，位置 {}；仅扣尚未支付的费用，继续原进度与产物",
				request, lane,
				context instanceof net.minecraft.world.level.block.entity.BlockEntity entity ? entity.getBlockPos() : "unknown");
		notifyPaymentChange();
		return true;
	}

	int confirmPayment(java.util.UUID expected) {
		if (paymentRequest == null || !paymentRequest.equals(expected) || invalidPending()) {
			throw new IllegalStateException("Stale or invalid centrifuge payment request");
		}
		int progress = paymentProgress;
		paymentRequest = null;
		paymentPending = false;
		paymentKnown = false;
		paymentEnergy = 0;
		context.productivebeesgenesis$markForSave();
		return progress;
	}

	/** 源输入与冻结身份匹配时才转交所有权；输出交付由后续 flush 执行。 */
	boolean commitCompletedInput(int processIndex) {
		if (invalidPending() || paymentPending) return false;
		if (pendingInputShrink <= 0) return true;
		if (!preparedOutput || pendingInputTemplate.isEmpty()) return false;
		var slot = context.inputSlot(processIndex);
		ItemStack input = slot.getStack();
		if (!ItemStack.isSameItemSameComponents(input, pendingInputTemplate)
				|| input.getCount() < pendingInputShrink) return false;
		int requested = pendingInputShrink;
		int before = input.getCount();
		Throwable debitFailure = null;
		try {
			slot.shrinkStack(requested, mekanism.api.Action.EXECUTE);
		} catch (RuntimeException | LinkageError failure) {
			debitFailure = failure;
		}
		ItemStack after;
		try {
			after = slot.getStack();
		} catch (RuntimeException | LinkageError readFailure) {
			if (slot instanceof BasicInventorySlot) {
				after = ((BasicInventorySlotAccessor) slot).productivebeesgenesis$getCurrent();
				if (debitFailure == null) debitFailure = readFailure;
				else debitFailure.addSuppressed(readFailure);
			} else {
				Throwable root = debitFailure == null ? readFailure : debitFailure;
				if (debitFailure != null) debitFailure.addSuppressed(readFailure);
				try {
					retainInputFailure(before, requested, root);
				} catch (RuntimeException | LinkageError saveFailure) {
					root.addSuppressed(saveFailure);
				}
				if (root instanceof RuntimeException runtime) throw runtime;
				throw (LinkageError) root;
			}
		}
		int consumed = -1;
		if (after.isEmpty()) consumed = before;
		else if (ItemStack.isSameItemSameComponents(after, pendingInputTemplate)) consumed = before - after.getCount();
		if (consumed >= 0 && consumed <= requested) {
			pendingInputShrink -= consumed;
			if (pendingInputShrink == 0) pendingInputTemplate = ItemStack.EMPTY;
			if (debitFailure != null) {
				reportNotificationFailure(NotificationKind.INPUT, "输入通知失败，按已提交的本地数量结清，不重复扣料", debitFailure);
			} else {
				completeNotificationRecovery(NotificationKind.INPUT);
			}
		} else {
			retainInputFailure(before, requested, new IllegalStateException("Input source changed during debit"));
		}
		try {
			context.productivebeesgenesis$markForSave();
			completeNotificationRecovery(NotificationKind.SAVE);
		} catch (RuntimeException | LinkageError failure) {
			reportNotificationFailure(NotificationKind.SAVE, "输入结算后的保存通知失败，保留已结清数量与原产物", failure);
		}
		return !invalidPending() && pendingInputShrink == 0;
	}

	boolean hasPaidPendingOutputs() {
		return paymentPending || hasCommittedPendingOutputs() || preparedOutput && pendingInputShrink > 0;
	}

	/** 原输入变为不可处理时交给已付物品缓冲；不经过采样、配方转换或能量扣费。 */
	boolean returnInput(int lane) {
		beforePendingMutation();
		if (hasPendingOutputs() || pendingInputShrink != 0 || invalidPending()) return false;
		var slot = context.inputSlot(lane);
		var input = slot.getStack().copy();
		if (input.isEmpty()) return true;
		pendingOutputs.put(input.copyWithCount(1), (long) input.getCount());
		pendingItemCount = input.getCount();
		preparedOutput = true;
		try {
			slot.setStack(ItemStack.EMPTY);
		} catch (RuntimeException failure) {
			// 无外部接收副作用；源槽仍完全保留时撤销本地接管，否则暂停保存两端证据。
			if (ItemStack.isSameItemSameComponents(slot.getStack(), input) && slot.getCount() == input.getCount()) {
				clearPendingOutputs();
			} else {
				invalidPending = saveCommittedPending(context.level().registryAccess());
				invalidPending.put("return_input_before", input.save(context.level().registryAccess()));
			}
			context.productivebeesgenesis$markForSave();
			throw failure;
		}
		context.productivebeesgenesis$markForSave();
		return true;
	}

	/** Adds an output without allocating the BiFunction/boxing path used by Map.merge. */
	private void addPendingOutput(ItemStack key, long amount) {
		if (amount <= 0) return;
		outputUpgradesPrepared = false;
		Long previous = pendingOutputs.get(key);
		if (previous == null) {
			pendingOutputs.put(key, amount);
			return;
		}
		pendingOutputs.put(key, Math.addExact(previous, amount));
	}

	private void selectRecipe(CentrifugeRecipe recipe) {
		if (pendingRecipe == recipe) return;
		Map<ItemStack, ChancedOutput> outputs = getRecipeOutputsCached(recipe);
		FluidStack fluidOutput = recipe.getFluidOutputs();
		FluidStack fluidTemplate = fluidOutput.isEmpty() ? null : fluidOutput.copy();
		if (pendingRecipe != null) clearPendingOutputs();
		pendingRecipeOutputs = outputs;
		pendingFluidTemplate = fluidTemplate;
		pendingRecipe = recipe;
	}

	/**
	 * 将聚合的 PB 配方输出实际插入槽位并扣除输入 — 委托给 {@link PbRecipeFlusher}
	 * <br/>
	 * 调用方语义保持不变:返回 true 全部输出成功;false 空间不足未执行任何修改
	 * （输入已扣除的 pending 例外：会尽量部分排空，返回 false 表示仍有剩余）。
	 *
	 * @param processIndex 进程索引
	 * @return true 全部输出成功插入并扣除输入;false 输出空间不足或仍有剩余 pending
	 */
	public boolean flushPendingPbOutputs(int processIndex) {
		beforePendingMutation();
		return flusher.flush(this, processIndex);
	}

	void setFactoryOutputBatch(PbFactoryOutputBatch batch) {
		beforePendingMutation();
		factoryOutputBatch = batch;
	}

	private void beforePendingMutation() {
		if (factoryOutputBatch != null) factoryOutputBatch.beforeOwnerMutation();
	}

	/** 新完成批次留到同机循环末尾；已冻结旧积压由处理器在生产前单独合并排空。 */
	boolean flushCompletedBatch(int processIndex) {
		beforePendingMutation();
		if (!commitCompletedInput(processIndex)) return false;
		if (factoryOutputBatch != null && factoryOutputBatch.defer(this, processIndex)) return true;
		return flushPendingPbOutputs(processIndex);
	}

	/** 返回 false 时保留准备对象；接管后账户与原 pending 共同等待本轮唯一回执。 */
	boolean deferPrepared(PbRecipeCompleter batch, int lane, Supplier<Completion> completion) {
		if (factoryOutputBatch == null || !factoryOutputBatch.canDefer(this, lane)) return false;
		acceptPrepared(batch);
		Completion pending = completion.get();
		try {
			factoryOutputBatch.defer(this, lane, pending);
		} catch (RuntimeException | Error failure) {
			try {
				pending.uncertain("Factory output registration failed");
			} catch (RuntimeException isolationFailure) {
				failure.addSuppressed(isolationFailure);
			}
			throw failure;
		}
		return true;
	}

	boolean flushAfterFactoryAe(int processIndex) {
		return flusher.flushAfterFactoryAe(this, processIndex);
	}

	/**
	 * 允许延迟提交的 flush — 产物种类多于物理输出槽时的唯一推进方式。
	 * <br/>
	 * 写入放得下的产物、扣除一次输入，剩余产物留在 pending（随方块持久化）等待排空。
	 * 「已提交 pending」存在期间上层不再累积新产物，因此缓冲有界。
	 *
	 * @param processIndex       进程索引
	 * @param maxDeferredPerType 单种产物允许延迟的最大数量（隐形缓冲上限）
	 * @return true 表示输入已提交（全部或部分产物已写出）
	 */
	boolean flushPendingPbOutputsWithDeferral(int processIndex, int maxDeferredPerType) {
		beforePendingMutation();
		PbRecipeFlusher.Outcome outcome = flusher.flush(this, processIndex, maxDeferredPerType);
		return outcome == PbRecipeFlusher.Outcome.COMMITTED
				|| outcome == PbRecipeFlusher.Outcome.DEFERRED;
	}

	/** 上一次 flush 规划中是否存在「完全无处安放」的产物种类（减半批量无效） */
	boolean lastPlanHasUnplaceableType() {
		return flusher.lastPlanHasUnplaceableType();
	}

	/** 上一次 flush 规划中单种产物需要延迟的最大数量 */
	long lastPlanMaxDeferredPerType() {
		return flusher.lastPlanMaxDeferredPerType();
	}

	/** 上一次 flush 规划观测到的单槽容量（延迟量预算基准） */
	int lastPlanSlotCapacity() {
		return flusher.lastPlanSlotCapacity();
	}

	/**
	 * 清空聚合输出缓存(保留当前配方引用与模板,便于同 tick 内继续累加同一配方)。
	 * <br/>
	 * 不清空 {@link #pendingFluidTemplate},同配方流体模板可复用,避免重新调用 getFluidOutputs()。
	 */
	void clearPendingOutputs() {
		// 已回执交空的槽可结束冻结；回调不能抢先清除尚未扣账的原资产。
		if (hasPendingOutputs() || pendingInputShrink != 0 || paymentPending) beforePendingMutation();
		// 空闲进程无需重复清理模板索引。
		if (!pendingOutputs.isEmpty()) pendingOutputs.clear();
		pendingFluidAmount = 0;
		pendingInputShrink = 0;
		pendingInputTemplate = ItemStack.EMPTY;
		paymentRequest = null;
		paymentPending = false;
		paymentKnown = false;
		paymentEnergy = 0;
		paymentProgress = 0;
		pendingItemCount = 0;
		preparedOutput = false;
		outputUpgradesPrepared = false;
	}

	/** 配方变更或输入清空时重置聚合配方引用 */
	public void resetPendingRecipe() {
		beforePendingMutation();
		if (hasPaidPendingOutputs()) return;
		clearPendingRecipe();
	}

	private void clearPendingRecipe() {
		pendingRecipe = null;
		pendingRecipeOutputs = null;
		pendingFluidTemplate = null;
		clearPendingOutputs();
		capacityCache = null;
	}

	/** 本 tick 已聚合的物品总数量(供协调器判断是否达到 flush 阈值) */
	public long pendingItemCount() {
		return pendingItemCount;
	}

	/** Drops wax that was buffered before the upgrade was installed. */
	void discardSuppressedWaxOutputs() {
		if (!context.suppressesUselessByproducts() || pendingOutputs.isEmpty()) return;
		Iterator<Map.Entry<ItemStack, Long>> iterator = pendingOutputs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ItemStack, Long> entry = iterator.next();
			if (!UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			pendingItemCount = Math.max(0, pendingItemCount - Math.max(0, entry.getValue()));
			iterator.remove();
		}
	}

	/** 将当前 pending 物品输出按精华转化升级规则重建，并同步数量统计。 */
	void convertPendingEssenceOutputs() {
		if (!context.productivebeesgenesis$hasEssenceConversionUpgrade()
				|| pendingOutputs.isEmpty() || context.level() == null) return;
		if (!EssenceConversionUpgradeHelper.convertPendingLongOutputs(context.level(), pendingOutputs)) return;
		long total = 0L;
		for (long count : pendingOutputs.values()) {
			total = SaturatingMath.saturatingAdd(total, Math.max(0, count));
		}
		pendingItemCount = total;
	}

	/** 将当前 pending 物品输出按粗矿熔炼升级重建，并同步数量统计。 */
	void convertPendingRawOreOutputs() {
		if (!context.productivebeesgenesis$hasRawOreSmeltingUpgrade()
				|| pendingOutputs.isEmpty() || context.level() == null) return;
		if (!RawOreSmeltingUpgradeHelper.convertPendingLongOutputs(context.level(), pendingOutputs)) return;
		long total = 0L;
		for (long count : pendingOutputs.values()) {
			total = SaturatingMath.saturatingAdd(total, Math.max(0, count));
		}
		pendingItemCount = total;
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

	/** @return 本 tick 尚未插入的物品输出（按引用相等，保持准备顺序） */
	Map<ItemStack, Long> getPendingOutputs() {
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

	/** Direct-AE 路径按实际接收量减少 pending 物品总数。 */
	void consumePendingItemCount(long accepted) {
		if (accepted > 0) {
			debitPendingItemCount(accepted);
			notifyCommittedOutputChange();
		}
	}

	/** 工厂先结清全部来源，再发送宿主的保存通知。 */
	void debitPendingItemCount(long accepted) {
		if (accepted > pendingItemCount) throw new IllegalStateException("Centrifuge item receipt exceeds pending output");
		pendingItemCount -= accepted;
	}

	/** 本地槽已完整接收本轮 pending 物品。 */
	void consumeAllPendingItems() {
		if (!pendingOutputs.isEmpty()) pendingOutputs.clear();
		pendingItemCount = 0;
		notifyCommittedOutputChange();
	}

	/** Direct-AE 路径按实际接收量减少 pending 流体。 */
	void consumePendingFluid(long accepted) {
		if (accepted > 0) {
			debitPendingFluid(accepted);
			notifyCommittedOutputChange();
		}
	}

	void debitPendingFluid(long accepted) {
		if (accepted > pendingFluidAmount) throw new IllegalStateException("Centrifuge fluid receipt exceeds pending output");
		pendingFluidAmount -= accepted;
		if (accepted > 0 && myriadFluidOutput != null) myriadFluidOutput.invalidateFluidTankFullCache();
	}

	/** 保存通知没有接收副作用，失败不能改写确定回执或中断后续来源扣账。 */
	void notifyCommittedOutputChange() {
		if (pendingInputShrink != 0) return;
		try {
			context.productivebeesgenesis$markForSave();
			completeNotificationRecovery(NotificationKind.SAVE);
		} catch (RuntimeException | LinkageError failure) {
			reportNotificationFailure(NotificationKind.SAVE, "产物扣账后的保存通知失败，确定回执已扣账，继续排空与其它车道生产", failure);
		}
	}

	/** Direct-AE 已提交部分产物后，标记对应输入已经且只会被扣除一次。 */
	void markPendingInputConsumed() {
		pendingInputShrink = 0;
		pendingInputTemplate = ItemStack.EMPTY;
		context.productivebeesgenesis$markForSave();
	}

	/** @return 是否仍有尚未写入 AE 或本地槽的产物。 */
	boolean hasPendingOutputs() {
		return !pendingOutputs.isEmpty() || pendingFluidAmount > 0;
	}

	/**
	 * @return true 表示输入已经提交，但仍有产物等待输出；失败时必须保留 pending 状态重试。
	 */
	boolean hasCommittedPendingOutputs() {
		return invalidPending() || pendingInputShrink == 0 && hasPendingOutputs();
	}

	/** @return 本 tick 尚未扣除的输入数量 */
	int getPendingInputShrink() {
		return pendingInputShrink;
	}

	@Nullable
	CompoundTag saveCommittedPending(HolderLookup.Provider provider) {
		if (factoryOutputBatch != null) factoryOutputBatch.cancelDeferredOutput();
		if (invalidPending != null) return invalidPending.copy();
		if (retainedPendingFailure != null) encodeRetainedPendingFailure(provider);
		if (invalidPending != null) return invalidPending.copy();
		if (!hasPaidPendingOutputs()) return null;
		return pendingSnapshot(provider, pendingOutputs, pendingFluidTemplate, pendingFluidAmount);
	}

	private CompoundTag pendingSnapshot(HolderLookup.Provider provider, Map<ItemStack, Long> outputs,
			FluidStack fluidTemplate, long fluidAmount) {
		CompoundTag root = new CompoundTag();
		root.putInt("version", 2);
		if (preparedOutput) root.putBoolean("prepared", true);
		if (paymentPending) {
			root.putUUID("payment_request", paymentRequest());
			root.putLong("payment_energy", paymentEnergy);
			root.putInt("payment_progress", paymentProgress);
			if (paymentKnown) root.putBoolean("payment_known", true);
		}
		if (pendingInputShrink > 0) {
			root.put("input", pendingInputTemplate.save(provider));
			root.putInt("input_count", pendingInputShrink);
		}
		ListTag items = new ListTag();
		for (Map.Entry<ItemStack, Long> entry : outputs.entrySet()) {
			long count = Math.max(0, entry.getValue());
			if (count <= 0 || entry.getKey().isEmpty()) continue;
			Tag encoded = pendingTemplates.item(entry.getKey(), provider);
			if (encoded instanceof CompoundTag stackTag) {
				stackTag.putLong(NBT_PENDING_COUNT, count);
				items.add(stackTag);
			}
		}
		if (!items.isEmpty()) root.put("items", items);
		if (fluidTemplate != null && !fluidTemplate.isEmpty() && fluidAmount > 0L) {
			root.put("fluid", fluidTemplate.copyWithAmount(1).save(provider));
			root.putLong("fluid_amount", fluidAmount);
		}
		return root;
	}

	/** 仅交付后未知结果冻结来源；完整前后产物、原贡献和首次根因一起保管。 */
	void retainItemOutputFailure(Map<ItemStack, Long> originalSources, CompoundTag evidence) {
		if (invalidPending()) return;
		Map<ItemStack, Long> before = new Reference2ObjectLinkedOpenHashMap<>(pendingOutputs);
		before.putAll(originalSources);
		retainOutputFailure(before, pendingFluidTemplate, pendingFluidAmount, evidence);
	}

	void retainFluidOutputFailure(FluidStack originalTemplate, long originalAmount, CompoundTag evidence) {
		if (invalidPending()) return;
		retainOutputFailure(new Reference2ObjectLinkedOpenHashMap<>(pendingOutputs),
				originalTemplate, originalAmount, evidence);
	}

	private void retainOutputFailure(Map<ItemStack, Long> originalOutputs, FluidStack originalFluid,
			long originalAmount, CompoundTag evidence) {
		retainedPendingFailure = new PendingFailure(originalOutputs,
				originalFluid == null ? null : originalFluid.copyWithAmount(1), originalAmount, evidence.copy(), "output_failure");
		Level level = context.level();
		if (level != null) encodeRetainedPendingFailure(level.registryAccess());
		context.productivebeesgenesis$markForSave();
	}

	private void encodeRetainedPendingFailure(HolderLookup.Provider registries) {
		PendingFailure retained = retainedPendingFailure;
		CompoundTag after = pendingSnapshot(registries, pendingOutputs, pendingFluidTemplate, pendingFluidAmount);
		CompoundTag before = pendingSnapshot(registries, retained.items(), retained.fluid(), retained.amount());
		CompoundTag failure = retained.evidence().copy();
		failure.put("after", after);
		before.put(retained.kind(), failure);
		invalidPending = before;
		retainedPendingFailure = null;
	}

	private void retainInputFailure(int before, int requested, Throwable error) {
		CompoundTag evidence = new CompoundTag();
		evidence.putInt("input_before", before);
		evidence.putInt("requested", requested);
		evidence.putString("error_type", error.getClass().getName());
		evidence.putString("error", String.valueOf(error.getMessage()));
		retainedPendingFailure = new PendingFailure(new Reference2ObjectLinkedOpenHashMap<>(pendingOutputs),
				pendingFluidTemplate == null ? null : pendingFluidTemplate.copyWithAmount(1),
				pendingFluidAmount, evidence, "input_failure");
		Level level = context.level();
		if (level != null) encodeRetainedPendingFailure(level.registryAccess());
		context.productivebeesgenesis$markForSave();
	}

	private record PendingFailure(Map<ItemStack, Long> items, FluidStack fluid, long amount, CompoundTag evidence, String kind) {}


	/** 仅同一机器的服务端线程复用；批次产物和返回 NBT 始终独立保管。 */
	private static final class PendingTemplates {
		private final List<EncodedItem> items = new ArrayList<>(4);
		private HolderLookup.Provider provider;

		Tag item(ItemStack template, HolderLookup.Provider registries) {
			if (provider != registries) {
				items.clear();
				provider = registries;
			}
			// 转换升级会重建等价模板；比较独立快照，既能复用编码也不会受源模板修改影响。
			for (int index = 0; index < items.size(); index++) {
				EncodedItem cached = items.get(index);
				if (ItemStack.isSameItemSameComponents(template, cached.template())) {
					return cached.encoded().copy();
				}
			}
			ItemStack snapshot = template.copyWithCount(1);
			Tag encoded = snapshot.save(registries);
			if (items.size() == 32) {
				items.removeFirst();
			}
			items.add(new EncodedItem(snapshot, encoded));
			return encoded.copy();
		}

		private record EncodedItem(ItemStack template, Tag encoded) {}
	}

	void loadCommittedPending(CompoundTag root, HolderLookup.Provider provider) {
		beforePendingMutation();
		pendingTemplates.items.clear();
		pendingTemplates.provider = null;
		invalidPending = null;
		retainedPendingFailure = null;
		clearPendingRecipe();
		if (root.contains("output_failure", Tag.TAG_COMPOUND) || root.contains("input_failure", Tag.TAG_COMPOUND)) {
			invalidPending = root.copy();
			return;
		}
		try {
			if (!java.util.Set.of("version", "items", "fluid", "fluid_amount", "prepared", "process",
					"input", "input_count", "payment_request", "payment_energy", "payment_progress", "payment_known").containsAll(root.getAllKeys())
					|| root.contains("version") && (!root.contains("version", Tag.TAG_INT) || root.getInt("version") != 2)
					|| root.contains("prepared") && !root.contains("prepared", Tag.TAG_BYTE)) {
				throw new IllegalArgumentException("Unknown committed output structure");
			}
			if (root.contains("items")) {
				if (!(root.get("items") instanceof ListTag items)
						|| !items.isEmpty() && items.getElementType() != Tag.TAG_COMPOUND)
					throw new IllegalArgumentException("Invalid pending item list");
				for (var entry : items) {
					var stackTag = (CompoundTag) entry;
					boolean longCount = stackTag.contains(NBT_PENDING_COUNT, Tag.TAG_LONG);
					if (!longCount && !stackTag.contains(NBT_PENDING_COUNT, Tag.TAG_INT))
						throw new IllegalArgumentException("Invalid pending item amount");
					long count = longCount ? stackTag.getLong(NBT_PENDING_COUNT) : stackTag.getInt(NBT_PENDING_COUNT);
					if (count <= 0) throw new IllegalArgumentException("Invalid pending item amount");
					ItemStack stack = ItemStack.parse(provider, stackTag).orElseThrow();
					pendingOutputs.put(stack.copyWithCount(1), count);
					pendingItemCount = Math.addExact(pendingItemCount, count);
				}
			}
			if (root.contains("fluid") || root.contains("fluid_amount")) {
				if (!root.contains("fluid", Tag.TAG_COMPOUND) || !root.contains("fluid_amount", Tag.TAG_LONG)
						|| root.getLong("fluid_amount") <= 0) throw new IllegalArgumentException("Invalid pending fluid");
				pendingFluidTemplate = FluidStack.parse(provider, root.getCompound("fluid")).orElseThrow().copyWithAmount(1);
				pendingFluidAmount = root.getLong("fluid_amount");
			}
			preparedOutput = root.getBoolean("prepared");
			if (root.contains("payment_request") || root.contains("payment_energy") || root.contains("payment_progress")
					|| root.contains("payment_known")) {
				if (!root.hasUUID("payment_request") || !root.contains("payment_energy", Tag.TAG_LONG)
						|| root.getLong("payment_energy") < 0 || !root.contains("payment_progress", Tag.TAG_INT)
						|| root.getInt("payment_progress") < 0
						|| root.contains("payment_known") && (!root.contains("payment_known", Tag.TAG_BYTE)
								|| root.getByte("payment_known") != 0 && root.getByte("payment_known") != 1))
					throw new IllegalArgumentException("Invalid payment request");
				paymentRequest = root.getUUID("payment_request");
				paymentPending = true;
				paymentKnown = root.getBoolean("payment_known");
				paymentEnergy = root.getLong("payment_energy");
				paymentProgress = root.getInt("payment_progress");
			}
			if (root.contains("input") || root.contains("input_count")) {
				if (!preparedOutput || !root.contains("input", Tag.TAG_COMPOUND)
						|| !root.contains("input_count", Tag.TAG_INT) || root.getInt("input_count") <= 0)
					throw new IllegalArgumentException("Invalid frozen pending input");
				pendingInputTemplate = ItemStack.parse(provider, root.getCompound("input")).orElseThrow();
				if (pendingInputTemplate.getCount() != 1) throw new IllegalArgumentException("Invalid input identity");
				pendingInputShrink = root.getInt("input_count");
			}
		} catch (RuntimeException invalid) {
			invalidPending = root.copy();
		}
	}

	/**
	 * 共享只读输出模板，供输出检查、聚合和 flush 复用，避免 PB 每次复制产物表。
	 *
	 * @param recipe PB配方
	 * @return 配方输出表(可能为 null,若 recipe 为 null)
	 */
	@Nullable
	static Map<ItemStack, ChancedOutput> getRecipeOutputsCached(@Nullable CentrifugeRecipe recipe) {
		if (recipe == null) return null;
		if (recipe instanceof PbCentrifugeRecipeSnapshot.FrozenRecipe) return recipe.getRecipeOutputs();
		Map<ItemStack, ChancedOutput> cached = recipeOutputsCache.get(recipe);
		return cached != null ? cached
				: recipeOutputsCache.computeIfAbsent(recipe, CentrifugeRecipe::getRecipeOutputs);
	}
}
