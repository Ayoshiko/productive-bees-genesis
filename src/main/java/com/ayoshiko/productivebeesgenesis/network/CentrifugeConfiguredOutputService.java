package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
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

		OutputCustody custody = OutputCustody.of(blockEntity);
		long transferred = 0L;
		for (IInventorySlot source : inputSlots) {
			if (source == null || source.isEmpty()) continue;
			for (IItemHandler target : targets.handlers()) {
				if (source.isEmpty() || custody.blocked()) break;
				transferred += transferToTarget(source, target, custody,
						level.dimension().location() + ":" + sourcePos + ":input-return");
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

	static int transferToTarget(IInventorySlot source, IItemHandler target, OutputCustody custody,
			String destination) {
		if (custody.blocked()) return 0;
		ItemStack sourceSnapshot = source.getStack().copy();
		int requested = sourceSnapshot.getCount();
		if (requested <= 0) return 0;
		int accepted;
		try {
			ItemStack candidate = sourceSnapshot.copy();
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, candidate, true);
			accepted = requested - remainderCount(sourceSnapshot, requested, remainder);
			accepted = Math.min(accepted, source.shrinkStack(accepted, Action.SIMULATE));
		} catch (LinkageError | RuntimeException e) {
			LogThrottle.warn("centrifuge_input_return_local_simulate",
					"模拟向离心机相邻容器输出物品失败: {}", e.toString());
			return 0;
		}
		if (accepted <= 0) return 0;
		// 原生槽位没有外部提交回调；先把确切快照移入保管，再允许邻居接收。
		ItemStack extracted = sourceSnapshot.copyWithCount(accepted);
		source.setStack(sourceSnapshot.copyWithCount(requested - accepted));
		long handed = custody.handoffItem(extracted, destination, () -> {
			ItemStack remainder = ItemHandlerHelper.insertItemStacked(target, extracted.copy(), false);
			return acceptedCount(extracted, remainder);
		});
		int knownRemainder = accepted - (int) handed;
		if (knownRemainder > 0) {
			// 只返还明确未被接收的数量；未知结果由 custody 持有，绝不回填整份快照。
			custody.retainItem(extracted.copyWithCount(knownRemainder), destination + ":known-remainder");
			custody.recoverItem(stack -> {
				ItemStack current = source.getStack();
				if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) return 0;
				int space = Math.max(0, source.getLimit(stack) - current.getCount());
				int restored = Math.min(space, stack.getCount());
				if (restored > 0) source.setStack(stack.copyWithCount(current.getCount() + restored));
				return restored;
			});
		}
		return custody.blocked() && !custody.failure().isEmpty() ? 0 : (int) handed;
	}

	private static int acceptedCount(ItemStack offered, ItemStack remainder) {
		return offered.getCount() - remainderCount(offered, offered.getCount(), remainder);
	}

	private static int remainderCount(ItemStack offered, int requested, ItemStack remainder) {
		if (remainder == null || remainder.getCount() > requested
				|| !remainder.isEmpty() && !ItemStack.isSameItemSameComponents(offered, remainder)) {
			throw new IllegalStateException("Invalid item insertion remainder");
		}
		return remainder.isEmpty() ? 0 : remainder.getCount();
	}

	record Result(long transferred, int configuredOutputSides, int targetContainers) {
	}

	private record Targets(int configuredOutputSides, List<IItemHandler> handlers) {
	}

}
