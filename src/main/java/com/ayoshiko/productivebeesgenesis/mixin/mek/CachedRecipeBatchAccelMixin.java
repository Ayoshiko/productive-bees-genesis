package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.mek.ICachedRecipeBatchAccel;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import mekanism.api.recipes.cache.CachedRecipe;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
	 * {@link CachedRecipe} 批量快速推进 Mixin — smelt（电力熔炼炉）配方时间加速的深度性能优化。
	 * <br/>
	 * 借鉴 JDTE {@code CoalescedAcceleratedMachine} 的合并 flush 思想：把批量收获模式下
	 * batchMultiplier - 1 次逐 tick 的 {@code process()} 补调，合并为
	 * "一次完整计算（calculateOperationsThisTick）+ 预算内循环快速推进"：
	 * <ul>
	 *   <li>快速路径只做字段级推进（useEnergy / operatingTicks++ / 周期完成判定），
	 *       完全跳过每 tick 的 getRecipeInput / 创建输出 ItemStack / 输出空间检查 / 能量计算；</li>
	 *   <li>配方周期完成点（operatingTicks 归零、输入消耗、产出写入）自动退出快速路径，
	 *       下一次调用走完整计算重算（输入/输出槽已变化，必须重算），然后继续快速推进；</li>
	 *   <li>能量不足 / 配方错误暂停 / 持有者（红石等）不可用 / 预算耗尽时自动终止，
	 *       剩余交给原版 {@code process()} 逻辑，错误标记与激活状态由原逻辑维护；</li>
	 *   <li>未激活批量时（预算 <= 0）注入零开销（两次 int 比较），常态性能不受影响。</li>
	 * </ul>
	 * 语义等价于逐次调用 {@code process()}（能量消耗、输入消费、产出、进度推进一致），
	 * 完整计算的次数从 M 次降为 M / T（配方周期时长）+ 2，256x 加速下 MSPT 占用极低。
	 * <p>
	 * 版本敏感性：@Shadow 依赖 Mekanism 10.7.19.85 的 {@code CachedRecipe} 私有字段/方法名，
	 * 字段重命名时需同步更新；{@code calculateOperationsThisTick} 的注入点描述符包含
	 * {@code CachedRecipe$OperationTracker} 内部类名。
	 */
@Mixin(value = CachedRecipe.class, remap = false)
public abstract class CachedRecipeBatchAccelMixin implements ICachedRecipeBatchAccel {

	// ===== @Shadow：CachedRecipe 私有成员（仅快速路径使用） =====

	@Shadow
	private boolean pausedForErrors;

	@Shadow
	private BooleanSupplier canHolderFunction;

	@Shadow
	private BooleanConsumer setActive;

	@Shadow
	private IntSupplier requiredTicks;

	@Shadow
	private Runnable onFinish;

	@Shadow
	private LongSupplier perTickEnergy;

	@Shadow
	private LongSupplier storedEnergy;

	@Shadow
	private LongConsumer useEnergy;

	@Shadow
	private IntSupplier baselineMaxOperations;

	@Shadow
	private int operatingTicks;

	@Shadow
	private IntConsumer operatingTicksChanged;

	@Shadow
	protected abstract void finishProcessing(int operations);

	@Shadow
	protected abstract void useResources(int operations);

	@Shadow
	protected abstract void resetCache();

	// ===== @Unique：批量推进状态（实例级，每个缓存配方独立） =====

	/** 批量预算：剩余可快速推进的配方 tick 数（<= 0 表示未激活/已耗尽） */
	@Unique
	private int productivebeesgenesis$batchTicksLeft;

	/** 快速推进操作数（0 表示需要走完整计算重算；> 0 时快速路径直接复用） */
	@Unique
	private int productivebeesgenesis$batchFastOps;

	/** 当前完整 process 调用正在等待最终操作数；提前返回时用于终止剩余补调。 */
	@Unique
	private boolean productivebeesgenesis$awaitingFinalOperations;

	/** Only recipes created by this mod opt into marginal high-parallel energy pricing. */
	@Unique
	private boolean productivebeesgenesis$marginalEnergyPricing;

	// ===== 接口实现 =====

	@Override
	public void productivebeesgenesis$enableMarginalEnergyPricing() {
		productivebeesgenesis$marginalEnergyPricing = true;
	}

	@Override
	public void productivebeesgenesis$startBatch(int ticks) {
		productivebeesgenesis$batchTicksLeft = Math.max(0, ticks);
		productivebeesgenesis$batchFastOps = 0;
		productivebeesgenesis$awaitingFinalOperations = false;
	}

	@Override
	public boolean productivebeesgenesis$isBatchExhausted() {
		return productivebeesgenesis$batchTicksLeft <= 0;
	}

	/**
	 * Replaces Mekanism's linear stored-energy division with the same inverse marginal curve
	 * used by PB processing. The surrounding vanilla method still owns error reporting and
	 * the final {@code maxForEnergy} cap, so reduced-rate warnings retain their normal timing.
	 */
	@ModifyExpressionValue(method = "calculateOperationsThisTick",
			at = @At(value = "INVOKE",
					target = "Lmekanism/api/math/MathUtils;clampToInt(J)I"))
	private int productivebeesgenesis$priceFullRecipeTick(int linearAffordableOperations) {
		if (!productivebeesgenesis$marginalEnergyPricing) {
			return linearAffordableOperations;
		}
		return MekCentrifugeEnergyScaling.affordableOperations(
				perTickEnergy.getAsLong(), Math.max(0, baselineMaxOperations.getAsInt()),
				storedEnergy.getAsLong());
	}

	/** Charges a normal full-calculation tick with the same curve as the accelerated fast path. */
	@Inject(method = "useEnergy", at = @At("HEAD"), cancellable = true)
	private void productivebeesgenesis$chargeFullRecipeTick(int operations, CallbackInfo ci) {
		if (!productivebeesgenesis$marginalEnergyPricing) return;
		long energyPerTick = perTickEnergy.getAsLong();
		if (energyPerTick > 0L && operations > 0) {
			useEnergy.accept(MekCentrifugeEnergyScaling.batchEnergyCost(
					energyPerTick, operations, 1));
		}
		ci.cancel();
	}

	// ===== process() HEAD：批量快速路径 =====

	/**
	 * 批量激活且已有可用操作数时，在预算内循环快速推进：
	 * 每 tick 仅做 operatingTicks++ + 周期完成判定（字段级成本），
	 * 跳过完整的 calculateOperationsThisTick；周期完成点退出并标记需要重算。
	 * <p>
	 * v1.0.2 能量批量扣除：原实现每虚拟 tick 调一次 {@code useEnergy(ops)}
	 * （每次触发容器 extract → onContentsChanged → setChanged → markChunkDirty），
	 * 1024 倍加速下每 gameTick 执行 1024 次容器写与 chunk 标脏。现改为循环前一次
	 * 计算能量预算（可支撑的虚拟 tick 数），循环后一次 {@code useEnergy.accept}
	 * 扣除总额（共享的高并行边际计费 × {@code ran}），与本模组完整配方 tick 的
	 * 能量曲线一致，同时将容器写次数从 N 次降为 1 次。
	 */
	@Inject(method = "process", at = @At("HEAD"), cancellable = true, require = 0)
	private void productivebeesgenesis$onProcessHead(CallbackInfo ci) {
		int ticksLeft = productivebeesgenesis$batchTicksLeft;
		if (ticksLeft <= 0) {
			productivebeesgenesis$awaitingFinalOperations = false;
			return; // 批量未激活：走原版逐 tick 逻辑
		}
		int ops = productivebeesgenesis$batchFastOps;
		if (ops <= 0) {
			productivebeesgenesis$awaitingFinalOperations = true;
			return; // 需要完整重算：本次调用走原版逻辑，onAfterCalculate 恢复快速模式
		}
		productivebeesgenesis$awaitingFinalOperations = false;
		// 配方被错误暂停或持有者不可用（红石等）：终止批量，交给原版逻辑维护错误/激活状态
		if (pausedForErrors || !canHolderFunction.getAsBoolean()) {
			productivebeesgenesis$batchTicksLeft = 0;
			productivebeesgenesis$batchFastOps = 0;
			return;
		}
		long energyPerTick = perTickEnergy.getAsLong();
		int ticksRequired = requiredTicks.getAsInt();
		// 能量预算：storedEnergy 可支撑的虚拟 tick 数（与原版 capAtMaxForEnergy 语义一致）
		int ticksToRun = ticksLeft;
		if (energyPerTick > 0L) {
			long stored = storedEnergy.getAsLong();
			long perVirtualTick = MekCentrifugeEnergyScaling.batchEnergyCost(energyPerTick, ops, 1);
			ticksToRun = (int) Math.min(ticksLeft, stored / perVirtualTick);
			if (ticksToRun <= 0) {
				// 能量不足以支撑当前操作数：终止批量，交给原版逻辑
				// （原版会 cap 降 ops 继续或标记 NOT_ENOUGH_ENERGY，语义与逐 tick 一致）
				productivebeesgenesis$batchTicksLeft = 0;
				productivebeesgenesis$batchFastOps = 0;
				return;
			}
		}
		// 快速推进期间 active 保持 true，只在进入时设置一次（Mekanism setActive 内部有状态比较）
		setActive.accept(true);
		int ran = 0;
		while (ran < ticksToRun) {
			ran++;
			operatingTicks++;
			if (operatingTicks >= ticksRequired) {
				operatingTicks = 0;
				finishProcessing(ops);
				onFinish.run();
				resetCache();
				if (ticksRequired > 1) {
					operatingTicksChanged.accept(operatingTicks);
				}
				// 周期完成：输入/输出槽已变化，退出快速路径，下次调用完整重算
				productivebeesgenesis$batchFastOps = 0;
				break;
			}
			useResources(ops);
			if (ticksRequired > 1) {
				operatingTicksChanged.accept(operatingTicks);
			}
		}
		// 批量能量一次性扣除（等价于逐虚拟 tick useEnergy(ops) 的累加总额）
		if (energyPerTick > 0L && ran > 0) {
			useEnergy.accept(MekCentrifugeEnergyScaling.batchEnergyCost(energyPerTick, ops, ran));
		}
		// 剩余预算处理（区分退出原因）：
		// - 能量预算耗尽（ticksToRun < ticksLeft）：终止批量，下个虚拟 tick 走原版完整重算，
		//   由原版 cap 降 ops 或标记 NOT_ENOUGH_ENERGY（对齐原实现能量不足即终止的语义）
		// - 能量充足但周期完成 break：保留剩余批量（原实现语义），下次调用完整重算后继续快速推进
		productivebeesgenesis$batchTicksLeft = ticksToRun < ticksLeft ? 0 : ticksLeft - ran;
		ci.cancel();
	}

	// ===== process() 完整计算点：捕获最终可执行操作数并恢复快速模式 =====

	/**
	 * 在 {@code postProcessOperations} 与 {@code capAtMaxForEnergy} 都完成后，包裹原版读取
	 * {@code tracker.currentMax} 的最终字段访问。这样缓存的操作数与本次原版实际执行值完全一致，
	 * 不会绕过工厂排序、升级或能量阶段对操作数的二次裁剪；
	 * 无法推进（输入空/输出满/能量不足/配方不匹配）时终止批量。
	 */
	@WrapOperation(method = "process", at = @At(value = "FIELD",
			target = "Lmekanism/api/recipes/cache/CachedRecipe$OperationTracker;currentMax:I",
			opcode = Opcodes.GETFIELD))
	private int productivebeesgenesis$captureFinalOperations(CachedRecipe.OperationTracker tracker,
			Operation<Integer> original) {
		int ops = original.call(tracker);
		if (productivebeesgenesis$batchTicksLeft <= 0 || productivebeesgenesis$batchFastOps > 0) {
			return ops;
		}
		productivebeesgenesis$awaitingFinalOperations = false;
		if (ops <= 0) {
			productivebeesgenesis$batchTicksLeft = 0;
			productivebeesgenesis$batchFastOps = 0;
			return ops;
		}
		productivebeesgenesis$batchFastOps = ops;
		productivebeesgenesis$batchTicksLeft--;
		return ops;
	}

	/** 原版完成配方周期后已改变输入/输出，下一虚拟 tick 必须重新计算操作数。 */
	@WrapOperation(method = "process", at = @At(value = "INVOKE",
			target = "Lmekanism/api/recipes/cache/CachedRecipe;resetCache()V"))
	private void productivebeesgenesis$invalidateAfterRecipeBoundary(CachedRecipe<?> recipe,
			Operation<Void> original) {
		original.call(recipe);
		productivebeesgenesis$batchFastOps = 0;
	}

	/** paused/canHolder 等分支可能在最终操作数字段读取前返回；此时停止本轮补调。 */
	@Inject(method = "process", at = @At("RETURN"))
	private void productivebeesgenesis$stopBatchAfterEarlyReturn(CallbackInfo ci) {
		if (productivebeesgenesis$awaitingFinalOperations) {
			productivebeesgenesis$awaitingFinalOperations = false;
			productivebeesgenesis$batchTicksLeft = 0;
			productivebeesgenesis$batchFastOps = 0;
		}
	}
}
