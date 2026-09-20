package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

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
	 *   <li>{@link PbRecipeOutputSampler} — 数量采样与只读模板适配</li>
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

	/** 本 tick 尚未插入的流体输出模板(保留单次配方量) */
	@Nullable
	private FluidStack pendingFluidTemplate;

	/** 本 tick 尚未插入的流体输出总量(long 防止高倍加速下累加溢出) */
	private long pendingFluidAmount;

	/** 本 tick 尚未扣除的输入数量(= 已完成配方数) */
	private int pendingInputShrink;

	/** 本 tick 已聚合的物品总数量,用于触发提前 flush */
	private int pendingItemCount;

	public PbRecipeCompleter(PbRecipeContext context) {
		this.context = context;
	}

	private final PbRecipeOutputSampler.QuantityOutput quantityOutput = new PbRecipeOutputSampler.QuantityOutput() {
		@Override
		public void item(ItemStack template, long baseAmount, int multiplier) {
			int count = SaturatingMath.saturatingToInt(SaturatingMath.saturatingMultiply(baseAmount, multiplier));
			addPendingOutput(template, count);
			pendingItemCount = SaturatingMath.saturatingToInt(SaturatingMath.saturatingAdd(pendingItemCount, count));
		}
		@Override
		public void fluid(FluidStack template, long baseAmount, int multiplier) {
			pendingFluidAmount = SaturatingMath.saturatingAdd(pendingFluidAmount,
					SaturatingMath.saturatingMultiply(baseAmount, multiplier));
		}
	};

	/** 聚合单次结果；与批量入口共用数量内核及物理投影。 */
	public void accumulatePbRecipeOutputs(CentrifugeRecipe recipe, int processIndex, int productivityModifier) {
		accumulatePbRecipeOutputsBatch(recipe, processIndex, productivityModifier, 1);
	}

	/**
	 * 聚合有界批次；N=1 保留单次随机顺序，N>1 沿用保底及 CLT 近似。
	 * 输入只扣完成次数；生产力倍率仅放大输出，不放大输入消耗。
	 */
	public void accumulatePbRecipeOutputsBatch(CentrifugeRecipe recipe, int processIndex,
			int productivityModifier, int batchCount) {
		if (batchCount <= 0) return;
		selectRecipe(recipe);
		PbRecipeOutputSampler.sampleAmounts(quantityOutput, pendingRecipeOutputs, pendingFluidTemplate,
				batchCount, productivityModifier, context.stabilityBonus(),
				context.suppressesUselessByproducts(), ThreadLocalRandom.current());
		pendingInputShrink = SaturatingMath.saturatingToInt(
				SaturatingMath.saturatingAdd(pendingInputShrink, batchCount));
	}

	/** Adds an output without allocating the BiFunction/boxing path used by Map.merge. */
	private void addPendingOutput(ItemStack key, int amount) {
		if (amount <= 0) return;
		Integer previous = pendingOutputs.get(key);
		if (previous == null) {
			pendingOutputs.put(key, amount);
			return;
		}
		long combined = (long) previous + amount;
		pendingOutputs.put(key, (int) Math.min(combined, Integer.MAX_VALUE));
	}

	private void selectRecipe(CentrifugeRecipe recipe) {
		if (pendingRecipe == recipe) return;
		if (pendingRecipe != null) clearPendingOutputs();
		pendingRecipe = recipe;
		pendingRecipeOutputs = recipeOutputsCache.computeIfAbsent(recipe, CentrifugeRecipe::getRecipeOutputs);
		FluidStack fluidOutput = recipe.getFluidOutputs();
		pendingFluidTemplate = fluidOutput.isEmpty() ? null : fluidOutput.copy();
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
		return flusher.flush(this, processIndex);
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
		PbRecipeFlusher.Outcome outcome = flusher.flush(this, processIndex, maxDeferredPerType);
		return outcome == PbRecipeFlusher.Outcome.COMMITTED
				|| outcome == PbRecipeFlusher.Outcome.DEFERRED;
	}

	/** 上一次 flush 规划中是否存在「完全无处安放」的产物种类（减半批量无效） */
	boolean lastPlanHasUnplaceableType() {
		return flusher.lastPlanHasUnplaceableType();
	}

	/** 上一次 flush 规划中单种产物需要延迟的最大数量 */
	int lastPlanMaxDeferredPerType() {
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

	/** Drops wax that was buffered before the upgrade was installed. */
	void discardSuppressedWaxOutputs() {
		if (!context.suppressesUselessByproducts() || pendingOutputs.isEmpty()) return;
		Iterator<Map.Entry<ItemStack, Integer>> iterator = pendingOutputs.entrySet().iterator();
		while (iterator.hasNext()) {
			Map.Entry<ItemStack, Integer> entry = iterator.next();
			if (!UselessByproductUpgradeHelper.isWax(entry.getKey())) continue;
			pendingItemCount = Math.max(0, pendingItemCount - Math.max(0, entry.getValue()));
			iterator.remove();
		}
	}

	/** 将当前 pending 物品输出按精华转化升级规则重建，并同步数量统计。 */
	void convertPendingEssenceOutputs() {
		if (!context.productivebeesgenesis$hasEssenceConversionUpgrade()
				|| pendingOutputs.isEmpty() || context.level() == null) return;
		if (!EssenceConversionUpgradeHelper.convertPendingOutputs(context.level(), pendingOutputs)) return;
		long total = 0L;
		for (int count : pendingOutputs.values()) {
			total = SaturatingMath.saturatingAdd(total, Math.max(0, count));
		}
		pendingItemCount = SaturatingMath.saturatingToInt(total);
	}

	/** 将当前 pending 物品输出按粗矿熔炼升级重建，并同步数量统计。 */
	void convertPendingRawOreOutputs() {
		if (!context.productivebeesgenesis$hasRawOreSmeltingUpgrade()
				|| pendingOutputs.isEmpty() || context.level() == null) return;
		if (!RawOreSmeltingUpgradeHelper.convertPendingOutputs(context.level(), pendingOutputs)) return;
		long total = 0L;
		for (int count : pendingOutputs.values()) {
			total = SaturatingMath.saturatingAdd(total, Math.max(0, count));
		}
		pendingItemCount = SaturatingMath.saturatingToInt(total);
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

	/** Direct-AE 路径按实际接收量减少 pending 物品总数。 */
	void consumePendingItemCount(int accepted) {
		if (accepted > 0) {
			pendingItemCount = Math.max(0, pendingItemCount - accepted);
			if (pendingInputShrink == 0) context.productivebeesgenesis$markForSave();
		}
	}

	/** 本地槽已完整接收本轮 pending 物品。 */
	void consumeAllPendingItems() {
		pendingOutputs.clear();
		pendingItemCount = 0;
		if (pendingInputShrink == 0) context.productivebeesgenesis$markForSave();
	}

	/** Direct-AE 路径按实际接收量减少 pending 流体。 */
	void consumePendingFluid(long accepted) {
		if (accepted > 0) {
			pendingFluidAmount = Math.max(0L, pendingFluidAmount - accepted);
			if (pendingInputShrink == 0) context.productivebeesgenesis$markForSave();
		}
	}

	/** Direct-AE 已提交部分产物后，标记对应输入已经且只会被扣除一次。 */
	void markPendingInputConsumed() {
		pendingInputShrink = 0;
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
		return pendingInputShrink == 0 && hasPendingOutputs();
	}

	/** @return 本 tick 尚未扣除的输入数量 */
	int getPendingInputShrink() {
		return pendingInputShrink;
	}

	@Nullable
	CompoundTag saveCommittedPending(HolderLookup.Provider provider) {
		if (!hasCommittedPendingOutputs()) return null;
		CompoundTag root = new CompoundTag();
		ListTag items = new ListTag();
		for (Map.Entry<ItemStack, Integer> entry : pendingOutputs.entrySet()) {
			int count = Math.max(0, entry.getValue());
			if (count <= 0 || entry.getKey().isEmpty()) continue;
			Tag encoded = entry.getKey().copyWithCount(1).save(provider);
			if (encoded instanceof CompoundTag stackTag) {
				stackTag.putInt(NBT_PENDING_COUNT, count);
				items.add(stackTag);
			}
		}
		if (!items.isEmpty()) root.put("items", items);
		if (pendingFluidTemplate != null && !pendingFluidTemplate.isEmpty() && pendingFluidAmount > 0L) {
			root.put("fluid", pendingFluidTemplate.copyWithAmount(1).save(provider));
			root.putLong("fluid_amount", pendingFluidAmount);
		}
		return root.isEmpty() ? null : root;
	}

	void loadCommittedPending(CompoundTag root, HolderLookup.Provider provider) {
		resetPendingRecipe();
		if (root.contains("items", Tag.TAG_LIST)) {
			ListTag items = root.getList("items", Tag.TAG_COMPOUND);
			for (int i = 0; i < items.size(); i++) {
				CompoundTag stackTag = items.getCompound(i);
				int count = stackTag.getInt(NBT_PENDING_COUNT);
				ItemStack stack = ItemStack.parse(provider, stackTag).orElse(ItemStack.EMPTY);
				if (count <= 0 || stack.isEmpty()) continue;
				stack.setCount(1);
				pendingOutputs.put(stack, count);
				pendingItemCount = SaturatingMath.saturatingAddToInt(pendingItemCount, count);
			}
		}
		if (root.contains("fluid", Tag.TAG_COMPOUND)) {
			FluidStack fluid = FluidStack.parseOptional(provider, root.getCompound("fluid"));
			long amount = root.getLong("fluid_amount");
			if (!fluid.isEmpty() && amount > 0L) {
				pendingFluidTemplate = fluid.copyWithAmount(1);
				pendingFluidAmount = amount;
			}
		}
		pendingInputShrink = 0;
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
