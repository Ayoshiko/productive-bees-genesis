package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionRecipeIndex.Conversion;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionRecipeIndex.ConversionSnapshot;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 精华转化升级的输出转换与库存原子写回工具。
 * <p>
 * “精华”在这里表示任意具有纯同物压缩配方的低级资源形态，因此覆盖粒、尘埃、碎片和整合包
 * 自定义资源。唯一候选无需反向配方；存在多个候选时，仅接受其中唯一可逆的一项。粗矿、锭、
 * 宝石和方块输入不参与转换，并通过压缩关系图只保留最下级的一跳，避免后续生产周期继续压缩。
 * <p>
 * 配方查询由独立的不可变索引提供；无候选库存会在创建聚合列表和容量快照前返回。
 */
public final class EssenceConversionUpgradeHelper {

	private EssenceConversionUpgradeHelper() {
	}

	/** 判断方块实体是否安装了精华转化升级。 */
	public static boolean hasUpgrade(BlockEntity blockEntity) {
		return blockEntity instanceof IPbUpgradeProvider provider
				&& provider.getPbUpgradeInstalledCount(PbUpgradeType.ESSENCE_CONVERSION) > 0
				|| blockEntity instanceof cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity upgradeable
				&& upgradeable.getUpgradeCount(
						com.ayoshiko.productivebeesgenesis.init.ModItems.ESSENCE_CONVERSION_UPGRADE.get()) > 0;
	}

	/**
	 * 转换一批物品产物。
	 *
	 * @param level 配方所在世界
	 * @param drops 待转换产物
	 * @return 合并并转换后的新列表
	 */
	public static List<ItemStack> convert(Level level, List<ItemStack> drops) {
		if (level == null || drops == null || drops.isEmpty()) return drops;
		return convert(EssenceConversionRecipeIndex.snapshotFor(level), drops);
	}

	private static List<ItemStack> convert(ConversionSnapshot snapshot, List<ItemStack> drops) {
		List<ItemStack> merged = new ArrayList<>(drops.size());
		for (ItemStack stack : drops) {
			if (stack != null && !stack.isEmpty()) addAmount(merged, stack, stack.getCount());
		}
		if (merged.isEmpty()) return merged;

		List<ItemStack> converted = new ArrayList<>(merged.size());
		for (ItemStack stack : merged) {
			convertStack(snapshot, converted, stack);
		}
		return converted;
	}

	private static void convertStack(ConversionSnapshot snapshot, List<ItemStack> converted,
			ItemStack source) {
		Conversion conversion = snapshot.find(source);
		if (conversion == null) {
			addAmount(converted, source, source.getCount());
			return;
		}

		AmountConversion first = calculateAmounts(
				source.getCount(), conversion.inputCount(), conversion.result().getCount());
		if (first.resultAmount() == 0) {
			addAmount(converted, source, source.getCount());
			return;
		}
		addAmount(converted, source, first.remainder());

		if (!conversion.continueChain()) {
			addAmount(converted, conversion.result(), first.resultAmount());
			return;
		}
		Conversion next = snapshot.find(conversion.resultKey());
		if (next == null) {
			addAmount(converted, conversion.result(), first.resultAmount());
			return;
		}
		AmountConversion second = calculateAmounts(
				first.resultAmount(), next.inputCount(), next.result().getCount());
		addAmount(converted, conversion.result(), second.remainder());
		addAmount(converted, next.result(), second.resultAmount());
	}

	static AmountConversion calculateAmounts(long sourceAmount, int inputCount, int resultCount) {
		if (sourceAmount <= 0 || inputCount <= 0 || resultCount <= 0) return AmountConversion.EMPTY;
		long crafts = sourceAmount / inputCount;
		return new AmountConversion(sourceAmount % inputCount, crafts * resultCount);
	}

	/**
	 * 将待写入的聚合物品映射原地转换，供自定义离心机 AE/本地输出前使用。
	 * <p>调用方负责在转换后重新计算自己的数量统计。</p>
	 */
	public static boolean convertPendingOutputs(Level level, Map<ItemStack, Integer> outputs) {
		if (level == null || outputs == null || outputs.isEmpty()) return false;
		ConversionSnapshot conversionSnapshot = EssenceConversionRecipeIndex.snapshotFor(level);
		if (!containsConversionCandidate(outputs, conversionSnapshot)) return false;
		List<ItemStack> source = new ArrayList<>(outputs.size());
		for (Map.Entry<ItemStack, Integer> entry : outputs.entrySet()) {
			int count = Math.max(0, entry.getValue());
			if (count > 0) source.add(entry.getKey().copyWithCount(count));
		}
		List<ItemStack> converted = convert(conversionSnapshot, source);
		if (sameStacks(source, converted)) return false;
		outputs.clear();
		for (ItemStack stack : converted) {
			if (!stack.isEmpty()) outputs.put(stack.copyWithCount(stack.getCount()), stack.getCount());
		}
		return true;
	}

	/**
	 * 转换 PB 原版输出处理器中的已聚合物品。
	 * <p>只有容量模拟成功才会修改库存；异常时恢复快照并保留原输出。</p>
	 */
	public static boolean convertStored(Level level, IItemHandler handler) {
		if (level == null || level.isClientSide() || handler == null
				|| !(handler instanceof IItemHandlerModifiable modifiable)) {
			return false;
		}
		int[] outputSlots = resolveOutputSlots(handler);
		if (outputSlots.length == 0) return false;
		ConversionSnapshot conversionSnapshot = EssenceConversionRecipeIndex.snapshotFor(level);
		if (!containsConversionCandidate(handler, outputSlots, conversionSnapshot)) return false;

		// 先建立整个输出库存的快照，再做一次转换。不能逐候选修改库存，
		// 否则 A -> B 与 B -> C 同时存在时，刚生成的 B 可能被再次转换。
		List<ItemStack> source = new ArrayList<>(outputSlots.length);
		for (int slot : outputSlots) {
			ItemStack stack = handler.getStackInSlot(slot);
			if (!stack.isEmpty()) addAmount(source, stack, stack.getCount());
		}
		if (source.isEmpty()) return false;

		List<ItemStack> replacement = convert(conversionSnapshot, source);
		if (sameStacks(source, replacement) || !canFitReplacement(handler, outputSlots, replacement)) {
			return false;
		}

		ItemStack[] snapshot = snapshot(handler, outputSlots);
		extractAll(handler, outputSlots);
		if (insertAll(handler, outputSlots, replacement)) return true;

		for (int i = 0; i < outputSlots.length; i++) {
			modifiable.setStackInSlot(outputSlots[i], snapshot[i]);
		}
		LogThrottle.error("essence_conversion_restore", "精华转化写入失败，已恢复原输出库存");
		return false;
	}

	/** 清空配方转换缓存，服务器停止或 /reload 时调用。 */
	public static void invalidateCache() {
		EssenceConversionRecipeIndex.invalidate();
	}

	private static int[] resolveOutputSlots(IItemHandler handler) {
		if (handler instanceof BlockEntityItemStackHandler blockHandler) {
			int[] outputSlots = blockHandler.getOutputSlots();
			return outputSlots == null ? new int[0] : outputSlots;
		}
		int[] slots = new int[handler.getSlots()];
		for (int i = 0; i < slots.length; i++) slots[i] = i;
		return slots;
	}

	private static boolean containsConversionCandidate(IItemHandler handler, int[] slots,
			ConversionSnapshot snapshot) {
		for (int slot : slots) {
			ItemStack stack = handler.getStackInSlot(slot);
			if (!stack.isEmpty() && snapshot.find(stack) != null) return true;
		}
		return false;
	}

	private static boolean containsConversionCandidate(Map<ItemStack, Integer> outputs,
			ConversionSnapshot snapshot) {
		for (Map.Entry<ItemStack, Integer> entry : outputs.entrySet()) {
			if (entry.getValue() > 0 && !entry.getKey().isEmpty() && snapshot.find(entry.getKey()) != null) {
				return true;
			}
		}
		return false;
	}

	private static boolean canFitReplacement(IItemHandler handler, int[] outputSlots,
			List<ItemStack> replacement) {
		ItemStack[] simulated = new ItemStack[outputSlots.length];
		int[] limits = new int[outputSlots.length];
		for (int i = 0; i < outputSlots.length; i++) {
			simulated[i] = ItemStack.EMPTY;
			limits[i] = Math.max(1, handler.getSlotLimit(outputSlots[i]));
		}
		for (ItemStack stack : replacement) {
			long remaining = stack.getCount();
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				ItemStack current = simulated[i];
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int capacity = Math.min(limits[i], current.getMaxStackSize());
				remaining -= Math.max(0, capacity - current.getCount());
				if (current.getCount() < capacity) current.setCount(capacity);
			}
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				if (!simulated[i].isEmpty()) continue;
				int capacity = Math.min(limits[i], stack.getMaxStackSize());
				int inserted = (int) Math.min(remaining, capacity);
				simulated[i] = stack.copyWithCount(inserted);
				remaining -= inserted;
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static void extractAll(IItemHandler handler, int[] slots) {
		for (int slot : slots) {
			ItemStack current = handler.getStackInSlot(slot);
			if (current.isEmpty()) continue;
			int count = current.getCount();
			handler.extractItem(slot, count, false);
		}
	}

	private static boolean insertAll(IItemHandler handler, int[] slots, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			int remaining = stack.getCount();
			for (int slot : slots) {
				if (remaining <= 0) break;
				ItemStack current = handler.getStackInSlot(slot);
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int offered = Math.min(remaining, Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			for (int slot : slots) {
				if (remaining <= 0 || !handler.getStackInSlot(slot).isEmpty()) continue;
				int offered = Math.min(remaining, Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static ItemStack[] snapshot(IItemHandler handler, int[] slots) {
		ItemStack[] snapshot = new ItemStack[slots.length];
		for (int i = 0; i < slots.length; i++) snapshot[i] = handler.getStackInSlot(slots[i]).copy();
		return snapshot;
	}

	private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) return false;
		for (int i = 0; i < left.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(left.get(i), right.get(i))
					|| left.get(i).getCount() != right.get(i).getCount()) return false;
		}
		return true;
	}

	private static void addAmount(List<ItemStack> stacks, ItemStack template, long amount) {
		if (template == null || template.isEmpty() || amount <= 0) return;
		for (ItemStack existing : stacks) {
			if (!ItemStack.isSameItemSameComponents(existing, template)) continue;
			int accepted = (int) Math.min(amount, Integer.MAX_VALUE - (long) existing.getCount());
			existing.grow(accepted);
			amount -= accepted;
			if (amount <= 0) return;
		}
		while (amount > 0) {
			int count = (int) Math.min(amount, Integer.MAX_VALUE);
			stacks.add(template.copyWithCount(count));
			amount -= count;
		}
	}

	record AmountConversion(long remainder, long resultAmount) {
		private static final AmountConversion EMPTY = new AmountConversion(0, 0);
	}
}
