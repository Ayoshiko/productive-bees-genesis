package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import mekanism.api.inventory.IInventorySlot;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.client.recipe_viewer.type.IRecipeViewerRecipeType;
import mekanism.common.recipe.IMekanismRecipeTypeProvider;
import mekanism.common.recipe.lookup.cache.InputRecipeCache.SingleItem;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * JDTE flush / onUpdateServer tail-slot reclaim and recipe-viewer support,
	 * split from {@link AbstractMekCentrifugeFactory} (SRP).
	 * <p>
	 * The JDTE semantics in {@link #flushAcceleratedTicks} and {@link #onUpdateServer}
	 * (per-game-tick batch guard, shared batch multiplier, and the 100-tick empty-tank
	 * reclaim) are preserved from the original implementation without logic changes.
	 */
final class AbstractMekCentrifugeFactoryJdteSupport {

	private AbstractMekCentrifugeFactoryJdteSupport() {
	}

	/**
	 * JDTE {@code CoalescedAcceleratedMachine} 合并接口委托（仅 JDTE 加载时经 Mixin 生效）。
	 * <br/>
	 * accumulate：仅入账虚拟 tick 银行；flush：从共享预算取批量倍率并执行一次<b>完整 tick</b>
	 * （能量注入 + super + PB 配方 + AE2 推送 + 槽位回收），与基础机/蜂箱的 flush 语义一致。
	 * <p>
	 * 与 {@link #onUpdateServer} 共享同 gameTick 门控（{@link TickBatchSkipState#tryBeginGameTick}）：
	 * 无论 JDTE flush 在 ticker 之前还是之后调用，同一 gameTick 只执行一次完整处理，避免双跑。
	 */
	static void accumulateAcceleratedTicks(@NotNull AbstractMekCentrifugeFactory factory, int ticks) {
		TickAccelTracker tracker = factory.productivebeesgenesis$getTickAccelTracker();
		if (tracker != null) {
			tracker.addVirtualTicks(ticks);
		}
	}

	/** JDTE 合并接口 flush 委托（见 {@link #productivebeesgenesis$accumulateAcceleratedTicks}） */
	static void flushAcceleratedTicks(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull List<IInventorySlot> inputSlots, @NotNull BooleanSupplier superCall) {
		Level level = factory.productivebeesgenesis$getAe2Level();
		if (level == null || level.isClientSide) {
			return;
		}
		TickAccelTracker tracker = factory.productivebeesgenesis$getTickAccelTracker();
		TickBatchSkipState skipState = factory.productivebeesgenesis$getTickBatchSkipState();
		if (tracker == null || skipState == null) {
			return;
		}
		long gameTick = level.getGameTime();
		if (!skipState.tryBeginGameTick(gameTick)) {
			// 同 gameTick 已由 ticker 完整处理：跳过，避免双跑
			return;
		}
		int batchMultiplier = skipState.takeSharedBatchMultiplier(tracker, gameTick);
		FactoryUpgradeStateHelper.onCoalescedFlush(factory, inputSlots, batchMultiplier,
				superCall);
		// 同步流体槽位数与空槽回收（对齐 onUpdateServer 尾部逻辑，flush 是唯一完整处理入口时必须补上）
		factory.fluidOutputTankCount = factory.fluidOutputHolder instanceof MultiFluidTankHolder h ? h.getTankCount() : 1;
		if (factory.fluidOutputHolder instanceof MultiFluidTankHolder multiHolder
				&& level != null && level.getGameTime() % 100 == 0) {
			multiHolder.reclaimEmptyTanks();
		}
	}

	/** 先走SMELTING管线，再处理PB配方，末尾推送输出到AE2网络 */
	static boolean onUpdateServer(@NotNull AbstractMekCentrifugeFactory factory,
			@NotNull List<IInventorySlot> inputSlots, @NotNull BooleanSupplier superCall) {
		boolean result = FactoryUpgradeStateHelper.onUpdateServer(factory, inputSlots, superCall);
		// Task 8: 同步流体槽位数到客户端(供 GUI 决定是否显示多流体槽 Tab)
		factory.fluidOutputTankCount = factory.fluidOutputHolder instanceof MultiFluidTankHolder h ? h.getTankCount() : 1;
		// v2.0.9: 每 100 tick 回收空槽映射,防止长时间运行槽位耗尽
		// 触发条件:MultiFluidTankHolder 模式 + gameTime 为 100 的倍数
		// 回收的是 tanksByFluidKey 映射关系,tanksInOrder 槽位固定不变(DataSlot 偏移不会发生)
		Level level = factory.productivebeesgenesis$getAe2Level();
		if (factory.fluidOutputHolder instanceof MultiFluidTankHolder multiHolder
				&& level != null && level.getGameTime() % 100 == 0) {
			multiHolder.reclaimEmptyTanks();
		}
		return result;
	}

	/** Returns the SMELTING recipe type (delegates to {@link MekCentrifugeFactoryHelper}). */
	@NotNull
	static IMekanismRecipeTypeProvider<SingleRecipeInput, ItemStackToItemStackRecipe,
			SingleItem<ItemStackToItemStackRecipe>> getRecipeType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeType();
	}

	/** Returns the SMELTING JEI recipe viewer type (delegates to {@link MekCentrifugeFactoryHelper}). */
	@NotNull
	static IRecipeViewerRecipeType<ItemStackToItemStackRecipe> recipeViewerType() {
		return MekCentrifugeFactoryHelper.getSmeltingRecipeViewerType();
	}

	/**
	 * Returns the active recipe for a cache index; SMELTING is only consulted when PB has no recipe
	 * (split from {@link AbstractMekCentrifugeFactory#getRecipe(int)}).
	 */
	@Nullable
	static ItemStackToItemStackRecipe getRecipe(@NotNull IInputHandler[] inputHandlers, int cacheIndex,
			@NotNull PbRecipeProcessor pbProcessor,
			@NotNull Function<IInputHandler, ItemStackToItemStackRecipe> findFirstRecipe,
			boolean allowSmelting) {
		return CentrifugeFactoryCommonLogic.getRecipe(inputHandlers, cacheIndex, pbProcessor, findFirstRecipe, allowSmelting);
	}
}
