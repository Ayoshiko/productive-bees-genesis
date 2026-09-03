package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.Action;
import mekanism.api.RelativeSide;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 按 Mekanism 物品侧面配置，将离心机输入物品转移到相邻容器。 */
final class CentrifugeConfiguredOutputService {
	private static final int RESTORE_ATTEMPTS = 3;

	private CentrifugeConfiguredOutputService() {
	}

	static Result transfer(Level level, BlockEntity blockEntity, List<IInventorySlot> inputSlots) {
		if (!(blockEntity instanceof ISideConfiguration sideConfiguration)) {
			return new Result(0L, 0, 0);
		}
		BlockPos sourcePos = blockEntity.getBlockPos();
		Targets targets = findTargets(level, sourcePos, sideConfiguration);
		if (targets.handlers().isEmpty()) {
			return new Result(0L, targets.configuredOutputSides(), targets.handlers().size());
		}

		long transferred = 0L;
		for (IInventorySlot source : inputSlots) {
			if (source == null || source.isEmpty()) continue;
			for (IItemHandler target : targets.handlers()) {
				if (source.isEmpty()) break;
				transferred += transferToTarget(source, target);
			}
		}
		return new Result(transferred, targets.configuredOutputSides(), targets.handlers().size());
	}

	private static Targets findTargets(Level level, BlockPos sourcePos,
			ISideConfiguration sideConfiguration) {
		ConfigInfo itemConfig = sideConfiguration.getConfig().getConfig(TransmissionType.ITEM);
		if (itemConfig == null) return new Targets(0, List.of());
		Direction facing = sideConfiguration.getDirection();
		int configuredOutputSides = 0;
		List<IItemHandler> handlers = new ArrayList<>(Direction.values().length);
		Set<IItemHandler> uniqueHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Direction worldSide : Direction.values()) {
			RelativeSide relativeSide = RelativeSide.fromDirections(facing, worldSide);
			DataType dataType = itemConfig.getDataType(relativeSide);
			if (!itemConfig.isSideEnabled(relativeSide)
					|| dataType == null || !dataType.canOutput()) continue;
			configuredOutputSides++;
			try {
				IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK,
						sourcePos.relative(worldSide), worldSide.getOpposite());
				if (handler != null && uniqueHandlers.add(handler)) handlers.add(handler);
			} catch (LinkageError | RuntimeException e) {
				LogThrottle.warn("centrifuge_input_return_capability",
						"读取离心机物品输出面 {} 的相邻容器失败: {}", worldSide, e.toString());
			}
		}
		return new Targets(configuredOutputSides, List.copyOf(handlers));
	}

	private static int transferToTarget(IInventorySlot source, IItemHandler target) {
		ItemStack sourceSnapshot = source.getStack().copy();
		int requested = Math.max(0, sourceSnapshot.getCount());
		if (requested <= 0) return 0;
		ItemStack candidate = sourceSnapshot.copy();
		candidate.setCount(requested);
		int accepted;
		try {
			ItemStack simulatedRemainder = ItemHandlerHelper.insertItemStacked(target, candidate, true);
			accepted = requested - clampRemainder(requested, simulatedRemainder);
			accepted = Math.min(accepted, source.shrinkStack(accepted, Action.SIMULATE));
		} catch (LinkageError | RuntimeException e) {
			LogThrottle.warn("centrifuge_input_return_local_simulate",
					"模拟向离心机相邻容器输出物品失败: {}", e.toString());
			return 0;
		}
		if (accepted <= 0) return 0;

		ItemStack extractedStack = sourceSnapshot.copy();
		int extracted;
		try {
			extracted = source.shrinkStack(accepted, Action.EXECUTE);
		} catch (RuntimeException e) {
			LogThrottle.warn("centrifuge_input_return_local_extract",
					"从离心机输入槽提取待输出物品失败: {}", e.toString());
			return 0;
		}
		extracted = Math.max(0, Math.min(accepted, extracted));
		if (extracted <= 0) return 0;
		extractedStack.setCount(extracted);

		int remainderCount;
		try {
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, extractedStack, false);
			remainderCount = clampRemainder(extracted, remainder);
		} catch (LinkageError | RuntimeException e) {
			LogThrottle.warn("centrifuge_input_return_local_insert",
					"向离心机相邻容器输出物品失败，回滚源槽: {}", e.toString());
			remainderCount = extracted;
		}
		int transferred = extracted - remainderCount;
		if (remainderCount > 0) restoreSourceSnapshot(source, sourceSnapshot, transferred);
		return transferred;
	}

	/** 按操作前快照恢复未送达数量；仅允许源槽恢复与日志告警，禁止世界掉落兜底。 */
	private static void restoreSourceSnapshot(IInventorySlot source, ItemStack sourceSnapshot,
			int transferred) {
		int retained = Math.max(0, sourceSnapshot.getCount() - transferred);
		ItemStack expected = retained <= 0 ? ItemStack.EMPTY : sourceSnapshot.copy();
		if (!expected.isEmpty()) expected.setCount(retained);
		RuntimeException lastFailure = null;
		for (int attempt = 0; attempt < RESTORE_ATTEMPTS; attempt++) {
			try {
				if (matches(source.getStack(), expected)) return;
				source.setStack(expected.copy());
				if (matches(source.getStack(), expected)) return;
			} catch (RuntimeException e) {
				lastFailure = e;
			}
		}
		LogThrottle.warn("centrifuge_input_return_local_restore",
				"离心机输入返还连续 {} 次恢复源槽失败，未生成世界掉落物: {}",
				RESTORE_ATTEMPTS, lastFailure == null ? "槽位状态校验失败" : lastFailure.toString());
	}

	private static boolean matches(ItemStack actual, ItemStack expected) {
		if (expected.isEmpty()) return actual.isEmpty();
		return actual.getCount() == expected.getCount()
				&& ItemStack.isSameItemSameComponents(actual, expected);
	}

	private static int clampRemainder(int requested, ItemStack remainder) {
		return remainder == null ? requested : Math.max(0, Math.min(requested, remainder.getCount()));
	}

	record Result(long transferred, int configuredOutputSides, int targetContainers) {
	}

	private record Targets(int configuredOutputSides, List<IItemHandler> handlers) {
	}

}
