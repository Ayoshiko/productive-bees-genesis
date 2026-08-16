package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModFluids;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

/**
	 * 万象创世流体输出处理器 — 封装配方定义流体（蜂蜜）的输出逻辑
	 * <br/>
	 * 从 {@link MyriadCreationsHandler} 抽取，遵循单一职责原则：
	 * 只负责将 PB 离心配方定义的流体输出按输入数量缩放后写入流体槽，
	 * 不涉及随机蜜脾/蜜脾块物品的产出。
	 * <p>
	 * <b>修复背景：</b>原 MyriadCreationsHandler 仅产出随机蜜脾/蜜脾块物品，
	 * 未产出配方定义的蜂蜜流体。此处理器查找输入对应的 PB 离心配方，
	 * 将配方流体输出按输入消耗数量缩放后写入流体槽。
	 * <p>
	 * <b>性能优化：</b>缓存流体槽满载状态，避免循环内重复调用
	 * {@link IExtendedFluidTank#getFluidAmount()} 和 {@link IExtendedFluidTank#getCapacity()}。
	 * <p>
	 * <b>线程安全：</b>方块实体在服务端单线程执行，volatile 保证可见性即可，无需 synchronized。
	 *
	 * @since 1.0.0
	 */
public class MyriadFluidOutputHandler {
	private static final String NBT_PENDING_FLUID = "productivebeesgenesis_myriad_pending_fluid";

	public enum InsertResult {
		REJECTED(false),
		COMPLETE(true),
		COMMITTED_PENDING(true);

		private final boolean committed;

		InsertResult(boolean committed) {
			this.committed = committed;
		}

		public boolean committed() {
			return committed;
		}
	}

	/** PB配方处理上下文 */
	private final PbRecipeContext context;

	/** PB配方查找器 — 查找输入对应的离心配方 */
	private final PbRecipeFinder recipeFinder;

	/** 日志前缀（区分原版/ME/EME工厂） */
	private final String logPrefix;

	/** 每进程的流体诊断日志冷却器 — 避免流体不产出时 WARN 刷屏 */
	private final LogThrottle[] fluidDiagThrottles;
	private final long[] pendingFluidAmounts;

	/**
	 * Task 3 性能优化：每 tick 缓存的流体槽满载状态
	 * <br/>
	 * 避免循环内重复调用 {@link IExtendedFluidTank#getFluidAmount()} 和
	 * {@link IExtendedFluidTank#getCapacity()}。在 {@link #initFluidTankFullCache()} 中计算，
	 * {@link #insertFluidOutput} 后通过 {@link #refreshFluidTankFullCache()} 更新。
	 */
	private volatile boolean cachedFluidTankFull = false;
	private long lastFullCacheTick = Long.MIN_VALUE;

	/**
	 * 构造流体输出处理器
	 *
	 * @param context     PB配方处理上下文
	 * @param recipeFinder PB配方查找器
	 * @param logPrefix   日志前缀（区分原版/ME/EME工厂）
	 * @param processes   进程总数
	 */
	public MyriadFluidOutputHandler(PbRecipeContext context, PbRecipeFinder recipeFinder,
			String logPrefix, int processes) {
		this.context = context;
		this.recipeFinder = recipeFinder;
		this.logPrefix = logPrefix;
		this.fluidDiagThrottles = new LogThrottle[processes];
		this.pendingFluidAmounts = new long[processes];
		for (int i = 0; i < processes; i++) {
			this.fluidDiagThrottles[i] = new LogThrottle();
		}
	}

	/**
	 * 初始化流体槽满载缓存（在每 tick 处理开始时调用）
	 * <br/>
	 * Task 3 性能优化：每 tick 缓存流体槽满载状态，避免循环内重复调用
	 * getFluidAmount/getCapacity。MU 256× 加速下循环内 isFluidTankFull() 被高频调用，
	 * 缓存后变为 O(1) 字段读取。
	 * <p>
	 * Task 4 修复：使用复合判断 {@code areAllFluidTanksFull() && !canAllocateNewFluidTank()},
	 * MULTI_PER_FLUID 模式下检查所有已分配槽 + 是否还能分配新槽。
	 * 暂停语义:所有槽满载且无法分配新槽才暂停(新流体类型可写入新槽时不暂停)。
	 */
	public void initFluidTankFullCache() {
		if (context.suppressesUselessByproducts()) {
			cachedFluidTankFull = false;
			return;
		}
		Level level = context.level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		if (tick == lastFullCacheTick) return;
		cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		lastFullCacheTick = tick;
	}

	/**
	 * 检查流体输出槽是否已满（返回每 tick 缓存值）
	 * <br/>
	 * 万象创世配方定义了蜂蜜流体输出，处理完成时需写入流体槽。
	 * 流体槽满时暂停处理，避免 tank.insert() 静默丢弃流体（输入仍被扣除）。
	 *
	 * @return true 如果流体槽已满
	 */
	public boolean isFluidTankFull() {
		return cachedFluidTankFull;
	}

	/**
	 * 刷新流体槽满载缓存（在 insertFluidOutput 后调用）
	 * <br/>
	 * insertFluidOutput 通过 tank.insert 改变流体槽内容，可能使槽变满。
	 * 调用此方法更新缓存，避免循环内 isFluidTankFull() 返回 stale 值。
	 * fluidOutputTank() 为 O(1) 字段访问，每次产物插入后调用一次无性能影响。
	 * <p>
	 * Task 4 修复:使用复合判断,与 initFluidTankFullCache 保持一致。
	 */
	public void refreshFluidTankFullCache() {
		if (context.suppressesUselessByproducts()) {
			cachedFluidTankFull = false;
			return;
		}
		cachedFluidTankFull = context.areAllFluidTanksFull() && !context.canAllocateNewFluidTank();
		Level level = context.level();
		lastFullCacheTick = level == null ? Long.MIN_VALUE : level.getGameTime();
	}

	/**
	 * 计算在当前流体槽剩余空间下可支持的最大批量操作数
	 * <br/>
	 * <b>Task 2 修复背景：</b>STACK 升级下 {@code completeMyriadCreationsBatch} 传入的 batchSize
	 * 可能很大（如 49152），而 {@link MyriadBatchPlanner#planOrFindMaxBatch} 仅考虑物品槽容量
	 * 不考虑流体槽空间。当 {@code insertFluidOutput(input, currentBatch * productivityMod)}
	 * 一次性插入大量流体（如 49152 × 250 mB = 12,288,000 mB）超出流体槽剩余空间时返回 false，
	 * 整个批次被放弃，机器保持 active 但不产出，形成无限失败循环。
	 * <p>
	 * 此方法在批量规划前调用，将 batchSize 限制为流体槽可容纳的最大操作数，
	 * 避免 {@link #insertFluidOutput} 因空间不足失败。
	 *
	 * @param input          输入物品（用于查找配方获取流体量）
	 * @param productivityMod 生产力倍率（影响每次操作的流体产量）
	 * @return 可支持的最大批量操作数；流体槽无限制时返回 {@link Integer#MAX_VALUE}；
	 *         类型不匹配或无配方时返回 0
	 */
	public int getMaxBatchForFluid(ItemStack input, int productivityMod) {
		if (productivityMod <= 0) return Integer.MAX_VALUE;
		// Task 8 修复：万象创世蜜脾没有 PB 配方，直接使用蜂蜜流体作为副产物
		// 避免对万象创世蜜脾调用 findPbRecipe 返回 null 后产生不必要的"未找到PB配方"警告
		FluidStack fluidOutput = resolveFluidOutput(input, 0);
		if (context.suppressesUselessByproducts()
				&& UselessByproductUpgradeHelper.isHoney(fluidOutput)) {
			return Integer.MAX_VALUE;
		}
		// Task 11: 使用 fluidOutputTankForInsert 路由到目标槽
		// MULTI_PER_FLUID 模式下查询对应槽的剩余空间（而非主槽），保证批量计算准确
		// SINGLE 模式下 fluidOutputTankForInsert 默认返回主槽，行为与修改前完全一致
		IExtendedFluidTank tank = context.fluidOutputTankForInsert(fluidOutput);
		if (tank == null) return Integer.MAX_VALUE;
		FluidStack current = tank.getFluid();
		if (!current.isEmpty() && !FluidStack.isSameFluidSameComponents(current, fluidOutput)) {
			return 0; // 类型不匹配，无法插入任何流体
		}
		int space = tank.getCapacity() - tank.getFluidAmount();
		if (space < 0) space = 0;
		long perBatch = SaturatingMath.saturatingMultiply(fluidOutput.getAmount(), productivityMod);
		if (perBatch <= 0) return Integer.MAX_VALUE;
		long directCapacity = context.productivebeesgenesis$isDirectAeOutputEnabled()
				? context.productivebeesgenesis$simulateGeneratedFluidToAe(fluidOutput, Long.MAX_VALUE)
				: 0L;
		long available = SaturatingMath.saturatingAdd(Math.max(0L, space), Math.max(0L, directCapacity));
		return SaturatingMath.saturatingToInt(available / perBatch);
	}

	/**
	 * 插入配方定义的流体输出到流体槽
	 * <br/>
	 * 万象创世蜜脾/蜜脾块配方定义了蜂蜜流体输出（productivebees:honey），
	 * 但原 MyriadCreationsHandler 仅产出随机蜜脾/蜜脾块物品，未产出流体。
	 * 此方法查找输入对应的 PB 离心配方，将配方流体输出按输入数量缩放后写入流体槽。
	 * <br/>
	 * 蜜脾块配方在 CentrifugeRecipeIndex 中由蜜脾配方派生，流体量已乘以蜜脾块倍率，
	 * 因此此处只需按输入消耗数量（modifier/batchSize）缩放，无需额外乘以蜜脾块倍率。
	 * <p>
	 * 原实现先 shrinkStack 再 insert，空间不足时会静默丢弃流体。现在先模拟 AE2 与本地容量；
	 * 完全未提交时返回 {@link InsertResult#REJECTED}，已经部分提交时把剩余量持久化后返回
	 * {@link InsertResult#COMMITTED_PENDING}，调用方不会重复产出或丢失流体。
	 *
	 * @param input        输入物品（万象创世蜜脾或蜜脾块）
	 * @param amount       输入消耗数量（单件=modifier，批量=batchSize）
	 * @param processIndex 进程索引（用于日志冷却）
	 * @return {@link InsertResult#REJECTED} 表示没有提交任何流体；{@link InsertResult#COMPLETE} 表示全部提交或无需流体；{@link InsertResult#COMMITTED_PENDING} 表示已提交部分且剩余量已进入持久化 pending 缓冲
	 */
	public InsertResult insertFluidOutput(ItemStack input, long amount, int processIndex) {
		if (amount <= 0) {
			logThrottledWarn(processIndex,
					"{}insertFluidOutput 跳过：amount<=0 amount={} input={}", logPrefix, amount, input);
			return InsertResult.COMPLETE;
		}
		// Task 8 修复：万象创世蜜脾没有 PB CentrifugeRecipe（其产物是动态随机蜜脾），
		// 查找 PB 配方必然返回 null。原实现因此每次都输出"未找到PB配方"警告（30+ 次），
		// 误导用户以为是配方查找 bug。实际是预期行为：万象创世蜜脾的流体副产物固定为蜂蜜。
		// 修复：识别万象创世蜜脾后跳过 PB 配方查找，直接使用蜂蜜流体，避免无谓警告日志。
		FluidStack fluidOutput = resolveFluidOutput(input, processIndex);
		if (context.suppressesUselessByproducts()
				&& UselessByproductUpgradeHelper.isHoney(fluidOutput)) {
			return InsertResult.COMPLETE;
		}
		long requestedAmount = SaturatingMath.saturatingMultiply(fluidOutput.getAmount(), amount);
		if (requestedAmount <= 0L) return InsertResult.COMPLETE;

		IExtendedFluidTank tank = context.fluidOutputTankForInsert(fluidOutput);
		long localCapacity = availableLocalCapacity(tank, fluidOutput);
		long simulatedAeCapacity = context.productivebeesgenesis$isDirectAeOutputEnabled()
				? SaturatingMath.clampToRequest(
						context.productivebeesgenesis$simulateGeneratedFluidToAe(fluidOutput, requestedAmount),
						requestedAmount)
				: 0L;
		if (SaturatingMath.saturatingAdd(localCapacity, simulatedAeCapacity) < requestedAmount) {
			return InsertResult.REJECTED;
		}

		long acceptedByAe = 0L;
		if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
			acceptedByAe = SaturatingMath.clampToRequest(
					context.productivebeesgenesis$pushGeneratedFluidToAe(fluidOutput, requestedAmount),
					requestedAmount);
		}
		long remainingAmount = requestedAmount - acceptedByAe;
		if (remainingAmount <= 0L) return InsertResult.COMPLETE;

		long insertedLocally = insertLocally(tank, fluidOutput, remainingAmount);
		remainingAmount -= insertedLocally;
		if (remainingAmount <= 0L) return InsertResult.COMPLETE;

		if (acceptedByAe <= 0L && insertedLocally <= 0L) {
			return InsertResult.REJECTED;
		}
		addPendingFluid(processIndex, remainingAmount);
		return InsertResult.COMMITTED_PENDING;
	}

	private long availableLocalCapacity(IExtendedFluidTank tank, FluidStack fluid) {
		if (tank == null) return 0L;
		FluidStack current = tank.getFluid();
		if (!current.isEmpty() && !FluidStack.isSameFluidSameComponents(current, fluid)) return 0L;
		return Math.max(0L, (long) tank.getCapacity() - tank.getFluidAmount());
	}

	private long insertLocally(IExtendedFluidTank tank, FluidStack fluid, long amount) {
		if (tank == null || amount <= 0L || amount > Integer.MAX_VALUE) return 0L;
		FluidStack scaledFluid = fluid.copyWithAmount((int) amount);
		FluidStack remainder = tank.insert(scaledFluid, Action.EXECUTE, AutomationType.INTERNAL);
		long inserted = scaledFluid.getAmount() - (remainder.isEmpty() ? 0L : remainder.getAmount());
		if (inserted > 0L && LocalFluidDrainPolicy.shouldDrainAfterCommit(
				tank.getNeeded(), amount)) {
			context.productivebeesgenesis$onLocalFluidOutputCommitted();
		}
		return inserted;
	}

	public boolean flushPendingFluid(int processIndex) {
		long pending = pendingFluidAmounts[processIndex];
		if (pending <= 0L) return true;
		if (context.suppressesUselessByproducts()) {
			setPendingFluid(processIndex, 0L);
			return true;
		}

		FluidStack honey = new FluidStack(ModFluids.HONEY.get(), 1);
		if (context.productivebeesgenesis$isDirectAeOutputEnabled()) {
			long accepted = SaturatingMath.clampToRequest(
					context.productivebeesgenesis$pushGeneratedFluidToAe(honey, pending), pending);
			if (accepted > 0L) {
				pending -= accepted;
				setPendingFluid(processIndex, pending);
			}
		}
		if (pending <= 0L) return true;

		IExtendedFluidTank tank = context.fluidOutputTankForInsert(honey);
		if (availableLocalCapacity(tank, honey) < pending) return false;
		long inserted = insertLocally(tank, honey, pending);
		if (inserted > 0L) {
			pending -= inserted;
			setPendingFluid(processIndex, pending);
		}
		return pending <= 0L;
	}

	private void addPendingFluid(int processIndex, long amount) {
		setPendingFluid(processIndex,
				SaturatingMath.saturatingAdd(pendingFluidAmounts[processIndex], Math.max(0L, amount)));
	}

	private void setPendingFluid(int processIndex, long amount) {
		long normalized = Math.max(0L, amount);
		if (pendingFluidAmounts[processIndex] == normalized) return;
		pendingFluidAmounts[processIndex] = normalized;
		context.productivebeesgenesis$markForSave();
	}

	public void saveAdditional(CompoundTag nbt) {
		nbt.putLongArray(NBT_PENDING_FLUID, pendingFluidAmounts);
	}

	public void loadAdditional(CompoundTag nbt) {
		if (!nbt.contains(NBT_PENDING_FLUID, Tag.TAG_LONG_ARRAY)) return;
		long[] saved = nbt.getLongArray(NBT_PENDING_FLUID);
		int length = Math.min(saved.length, pendingFluidAmounts.length);
		for (int i = 0; i < length; i++) {
			pendingFluidAmounts[i] = Math.max(0L, saved[i]);
		}
	}

	/**
	 * 解析输入物品对应的流体输出 — Task 8 修复核心方法
	 * <br/>
	 * <b>根因诊断</b>：spec 潜在问题 25 中"EME 工厂 insertFluidOutput 未找到 PB 配方"的根因是：
	 * input 是万象创世蜜脾（BEE_TYPE = productivebees:myriadcreations），它没有对应的
	 * PB CentrifugeRecipe（万象创世蜜脾的产物是动态随机蜜脾，由 MyriadCreationsHandler 处理）。
	 * 因此 findPbRecipe 返回 null 是预期行为，原实现的"未找到PB配方"警告属于误报。
	 * <p>
	 * <b>修复策略</b>：
	 * <ul>
	 *   <li>万象创世蜜脾：跳过 PB 配方查找，直接使用 ModFluids.HONEY（蜂蜜是万象创世的固定副产物）</li>
	 *   <li>普通 PB 蜜脾：保留原查找逻辑（findPbRecipe + getFluidOutputs + extractFluidFromIngredient fallback）</li>
	 *   <li>普通蜜脾查找失败：保留 WARN 日志（真正异常，非万象创世场景）</li>
	 * </ul>
	 *
	 * @param input        输入物品（万象创世蜜脾或普通 PB 蜜脾）
	 * @param processIndex 进程索引（用于日志冷却）
	 * @return 解析出的流体输出栈（非 EMPTY）
	 */
	private FluidStack resolveFluidOutput(ItemStack input, int processIndex) {
		// 路径 1：万象创世蜜脾/蜜脾块 — 无 PB 配方，蜂蜜为固定副产物
		// v13 修复：与 PB 原版蜜脾(100mB)/蜜脾块(400mB)标准对齐,4 倍比例
		// 之前硬编码 250mB 导致：1) 蜜脾产出比 PB 原版多 150mB；2) 蜜脾块产出比 PB 原版少 150mB
		boolean isCombBlock = MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input);
		boolean isHoneycomb = MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input);
		if (isCombBlock) {
			// 蜜脾块 = 4 个蜜脾,流体产出 4 × 100mB = 400mB
			return new FluidStack(ModFluids.HONEY.get(), 400);
		}
		if (isHoneycomb) {
			// 蜜脾 = 100mB,与 PB 原版 honeycomb.json 标准一致
			return new FluidStack(ModFluids.HONEY.get(), 100);
		}

		// 路径 2：普通 PB 蜜脾 — 查找 CentrifugeRecipe 获取配方定义的流体
		RecipeHolder<CentrifugeRecipe> recipeHolder = recipeFinder.findPbRecipe(input);
		if (recipeHolder != null) {
			CentrifugeRecipe recipe = recipeHolder.value();
			FluidStack fluidOutput = recipe.getFluidOutputs();
			if (fluidOutput.isEmpty()) {
				// getFluidOutputs() 返回 EMPTY 时（plib 的 getPreferredFluidStackByMod 无匹配 mod），
				// 直接访问 fluidOutput 字段构造 FluidStack 作为 fallback
				fluidOutput = extractFluidFromIngredient(recipe);
			}
			if (!fluidOutput.isEmpty()) return fluidOutput;
		} else {
			// 普通 PB 蜜脾查找失败 — 真正异常（非万象创世场景），保留 WARN 日志
			logThrottledWarn(processIndex,
					"{}resolveFluidOutput 未找到PB配方（非万象创世蜜脾），使用蜂蜜兜底：input={}",
					logPrefix, input);
		}
		// 兜底：蜂蜜流体（普通蜜脾查找失败或配方流体为空时）
		return new FluidStack(ModFluids.HONEY.get(), 250);
	}

	/**
	 * 从 CentrifugeRecipe.fluidOutput 字段直接构造 FluidStack（fallback）
	 * <br/>
	 * 当 {@link CentrifugeRecipe#getFluidOutputs()}（内部调用 plib 的 getPreferredFluidStackByMod）
	 * 返回 EMPTY 时使用此方法。直接访问 fluidOutput 字段（public final SizedFluidIngredient），
	 * 通过 getFluids() 获取流体类型，并用 amount() 覆盖数量，确保构造的 FluidStack 携带正确的流体量。
	 *
	 * @param recipe PB离心配方
	 * @return 构造的 FluidStack，若 ingredient 无匹配流体则返回 EMPTY
	 */
	private FluidStack extractFluidFromIngredient(CentrifugeRecipe recipe) {
		try {
			SizedFluidIngredient sized = recipe.fluidOutput;
			if (sized == null) return FluidStack.EMPTY;
			// getFluids() 返回的栈可能 amount=0（仅表示流体类型），用 sized.amount() 保证正确数量
			for (FluidStack stack : sized.getFluids()) {
				return stack.copyWithAmount(sized.amount());
			}
			return FluidStack.EMPTY;
		} catch (Exception e) {
			LogThrottle.warn("fluid_extract", "extractFluidFromIngredient 异常: {}", e.getMessage());
			return FluidStack.EMPTY;
		}
	}

	/**
	 * 记录流体诊断 WARN 日志（带冷却和抑制计数）
	 * <br/>
	 * L-28 修复：冷却期内仅计数不输出，冷却结束后输出日志并附加上次抑制次数，
	 * 避免 256x 加速下多进程同时阻塞时 WARN 刷屏。
	 *
	 * @param processIndex 进程索引
	 * @param pattern      日志消息模板（SLF4J 风格 {}）
	 * @param args         模板参数
	 */
	private void logThrottledWarn(int processIndex, String pattern, Object... args) {
		Level level = context.level();
		if (level == null) return;
		long currentTick = level.getGameTime();
		LogThrottle throttle = fluidDiagThrottles[processIndex];
		if (throttle.canLog(currentTick)) {
			throttle.logged(currentTick);
			ProductiveBeesGenesis.LOGGER.warn(pattern, args);
		} else {
			throttle.incrementSuppressed();
		}
	}
}
