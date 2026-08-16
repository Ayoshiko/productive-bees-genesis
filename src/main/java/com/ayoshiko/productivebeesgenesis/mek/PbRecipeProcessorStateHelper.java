package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import java.util.List;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.container.MekanismContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * PB progress/processing state helpers: per-process arrays, progress sync,
	 * container trackers, NBT persistence and the per-game-tick fluid reservation cache
	 * (split from {@link PbRecipeProcessor}, SRP). All methods are stateless; the
	 * processor keeps ownership of the arrays and publishes results.
	 */
final class PbRecipeProcessorStateHelper {

	private PbRecipeProcessorStateHelper() {
	}

	/** Task 23: progress sync interval (5 ticks); mirrors the original processor constant. */
	private static final int PROGRESS_SYNC_INTERVAL = 5;
	/**
	 * 在工厂处理各进程前为不同流体输出预留槽位。
	 * 配方查找命中现有缓存，且只在多槽工厂启用；不会为同一种流体重复扩容。
	 */
	static long reserveActiveFluidOutputTypes(@NotNull PbRecipeContext context,
			@NotNull List<IInventorySlot> inputSlots, @NotNull PbRecipeFinder recipeFinder,
			long lastFluidReservationTick) {
		if (context.fluidOutputTankCount() <= 1) return lastFluidReservationTick;
		Level level = context.level();
		long tick = level == null ? Long.MIN_VALUE : level.getGameTime();
		if (tick == lastFluidReservationTick) return lastFluidReservationTick;
		lastFluidReservationTick = tick;
		for (int i = 0, size = inputSlots.size(); i < size; i++) {
			ItemStack input = inputSlots.get(i).getStack();
			if (input.isEmpty()
					|| MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(input)
					|| MyriadCreationsEventHandler.isMyriadCreationsCombBlock(input)) {
				continue;
			}
			RecipeHolder<CentrifugeRecipe> recipe = recipeFinder.findPbRecipe(input);
			if (recipe == null) continue;
			var fluid = recipe.value().getFluidOutputs();
			if (!fluid.isEmpty() && !(context.suppressesUselessByproducts()
					&& UselessByproductUpgradeHelper.isHoney(fluid))) {
				context.reserveFluidOutputType(fluid);
			}
		}
		return lastFluidReservationTick;
	}

	/** 清除指定进程的PB处理状态（同时关闭该进程的激活位，避免进度箭头残留） */
	static void clearPbState(int processIndex, boolean[] pbProcessing, int[] pbOperatingTicks,
			int[] pbProcessingTime, @Nullable RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes,
			@NotNull PbRecipeCompleter completer, @NotNull PbRecipeContext context) {
		// 防御性：移除 pbProcessing 守卫，无条件清零所有 PB 状态字段
		// 避免守卫导致 pbOperatingTicks/cachedPbRecipes 残留（与 resetPbState 行为统一）
		pbProcessing[processIndex] = false;
		pbOperatingTicks[processIndex] = 0;
		pbProcessingTime[processIndex] = 0;
		cachedPbRecipes[processIndex] = null;
		// v2.0.9 修复产物锁定 bug：清除 PB 状态时同步重置 completer
		if (!completer.hasCommittedPendingOutputs()) completer.resetPendingRecipe();
		// 关闭该进程的激活位，防止进度箭头残留
		context.setPbActiveState(false, processIndex);
	}

	/** 强制重置指定进程的 PB 处理状态（不调用 setPbActiveState，供基础机器 SMELTING 检查命中时使用） */
	static void resetPbState(int processIndex, boolean[] pbProcessing, int[] pbOperatingTicks,
			int[] pbProcessingTime, @Nullable RecipeHolder<CentrifugeRecipe>[] cachedPbRecipes,
			@NotNull PbRecipeCompleter completer) {
		pbProcessing[processIndex] = false;
		pbOperatingTicks[processIndex] = 0;
		pbProcessingTime[processIndex] = 0;
		cachedPbRecipes[processIndex] = null;
		// v2.0.9 修复产物锁定 bug：重置 PB 状态时同步重置 completer
		if (!completer.hasCommittedPendingOutputs()) completer.resetPendingRecipe();
	}

	/** 检查指定进程是否正在处理PB配方 */
	static boolean isPbProcessing(boolean[] pbProcessing, int process) {
		return pbProcessing[process];
	}

	/**
	 * 获取PB处理的缩放进度（0.0~1.0） — 读 syncedOperatingTicks（与 trackArray 监控一致）。
	 * 修复 #9：processingTime <= 0 守卫，避免除零（CREATIVE 升级下 baseTicksRequired 可能为 0）。
	 */
	static double getPbScaledProgress(int i, int process, int[] pbProcessingTime,
			int[] syncedOperatingTicks, int baseTicksRequired) {
		int processingTime = pbProcessingTime[process] > 0 ? pbProcessingTime[process] : baseTicksRequired;
		if (processingTime <= 0) return 0.0;
		return Math.min(1.0, (double) syncedOperatingTicks[process] * i / processingTime);
	}

	/**
	 * Task 23: 每 tick 调用，高进程时节流进度同步。
	 * 高进程（≥9）时每 5 tick 将 pbOperatingTicks 复制到 syncedOperatingTicks，
	 * trackArray 检测到变化才发网络包，频率降低 80%。低进程（<9）时每 tick 同步。
	 */
	static int tickProgressSync(int progressSyncCounter, int processes, int[] pbOperatingTicks,
			int[] syncedOperatingTicks) {
		if (processes < 9 || ++progressSyncCounter % PROGRESS_SYNC_INTERVAL == 0) {
			System.arraycopy(pbOperatingTicks, 0, syncedOperatingTicks, 0, pbOperatingTicks.length);
		}
		return progressSyncCounter;
	}

	/**
	 * 同步PB进度到客户端。syncedOperatingTicks/pbProcessing/pbProcessingTime 同步给客户端用于GUI显示。
	 */
	static void addContainerTrackers(@NotNull MekanismContainer container, int processes,
			int[] syncedOperatingTicks, boolean[] pbProcessing, int[] pbProcessingTime) {
		// DataSlot 越界守卫：数组长度与 processes 不一致时跳过注册（防御性检查）
		if (syncedOperatingTicks.length != processes) {
			return;
		}
		container.trackArray(syncedOperatingTicks);
		container.trackArray(pbProcessing);
		container.trackArray(pbProcessingTime);
	}

	/**
	 * 持久化PB配方处理状态（修复 #10：pbProcessing/pbProcessingTime 同步持久化避免重启后 GUI 状态不一致）。
	 * pbProcessing 以 byte 数组持久化（boolean 数组 NBT 支持不一致）。
	 */
	static void saveAdditional(@NotNull CompoundTag nbt, int[] pbOperatingTicks,
			boolean[] pbProcessing, int[] pbProcessingTime) {
		nbt.putIntArray("productivebeesgenesis_pb_progress", pbOperatingTicks);
		byte[] processingBytes = new byte[pbProcessing.length];
		for (int i = 0; i < pbProcessing.length; i++) processingBytes[i] = (byte) (pbProcessing[i] ? 1 : 0);
		nbt.putByteArray("productivebeesgenesis_pb_processing", processingBytes);
		nbt.putIntArray("productivebeesgenesis_pb_processing_time", pbProcessingTime);
	}

	/** 加载PB配方处理状态（兼容旧存档仅含 pb_progress 的情形） */
	static void loadAdditional(@NotNull CompoundTag nbt, int[] pbOperatingTicks,
			boolean[] pbProcessing, int[] pbProcessingTime) {
		if (nbt.contains("productivebeesgenesis_pb_progress", Tag.TAG_INT_ARRAY)) {
			int[] saved = nbt.getIntArray("productivebeesgenesis_pb_progress");
			System.arraycopy(saved, 0, pbOperatingTicks, 0, Math.min(pbOperatingTicks.length, saved.length));
		}
		if (nbt.contains("productivebeesgenesis_pb_processing", Tag.TAG_BYTE_ARRAY)) {
			byte[] saved = nbt.getByteArray("productivebeesgenesis_pb_processing");
			int len = Math.min(pbProcessing.length, saved.length);
			for (int i = 0; i < len; i++) pbProcessing[i] = saved[i] != 0;
		}
		if (nbt.contains("productivebeesgenesis_pb_processing_time", Tag.TAG_INT_ARRAY)) {
			int[] saved = nbt.getIntArray("productivebeesgenesis_pb_processing_time");
			System.arraycopy(saved, 0, pbProcessingTime, 0, Math.min(pbProcessingTime.length, saved.length));
		}
	}

}
