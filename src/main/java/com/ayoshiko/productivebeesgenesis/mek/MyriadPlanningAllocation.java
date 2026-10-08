package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.RandomHoneycombSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Arrays;

/** 一次容量事务内的分配顺序、真实模板与匹配结果；二分仅更新原始数量数组。 */
final class MyriadPlanningAllocation {
	private final MyriadBatchPlanner.SlotCapacitySnapshot snapshot;
	private final ResourceLocation[] types;
	private final ItemStack[] templates;
	private final int[] amounts;
	private final int[][] limits;
	private final int[][] eligibleSlots;
	private final byte[][] matches;
	private int[] sourceIndices;
	private int buckets;

	MyriadPlanningAllocation(MyriadBatchPlanner.SlotCapacitySnapshot snapshot, Item baseItem,
			Map<ResourceLocation, Integer> allocation, Map<ResourceLocation, ItemStack> templateByType) {
		this.snapshot = snapshot;
		types = new ResourceLocation[allocation.size()];
		templates = new ItemStack[allocation.size()];
		amounts = new int[allocation.size()];
		limits = new int[allocation.size()][];
		eligibleSlots = new int[allocation.size()][];
		matches = new byte[allocation.size()][];
		int index = 0;
		for (var entry : allocation.entrySet()) {
			types[index] = entry.getKey();
			templates[index] = MyriadBatchPlanner.resolveTemplate(baseItem, entry.getKey(), templateByType);
			amounts[index++] = entry.getValue();
		}
	}

	static MyriadPlanningAllocation evenly(MyriadBatchPlanner.SlotCapacitySnapshot snapshot,
			Item baseItem, List<ResourceLocation> types, Map<ResourceLocation, ItemStack> templateByType) {
		// 保留 allocateEvenly 的 HashMap 遍历顺序；有槽位过滤时不能擅自改成输入顺序。
		var result = new MyriadPlanningAllocation(snapshot, baseItem,
				RandomHoneycombSelector.allocateEvenly(types.size(), types), templateByType);
		result.buckets = types.size();
		result.sourceIndices = new int[result.size()];
		for (int i = 0; i < result.size(); i++) {
			result.sourceIndices[i] = types.lastIndexOf(result.types[i]);
		}
		return result;
	}

	void setTotal(int total) {
		int base = total / buckets;
		int remainder = total % buckets;
		for (int i = 0; i < amounts.length; i++) {
			amounts[i] = base + (sourceIndices[i] < remainder ? 1 : 0);
		}
	}

	int size() { return templates.length; }
	int amount(int index) { return amounts[index]; }
	ItemStack template(int index) { return templates[index]; }

	int[] limits(int index) {
		if (limits[index] == null) limits[index] = snapshot.limitsFor(templates[index]);
		return limits[index];
	}

	/** 二分各轮复用稀疏槽位索引，不重复扫描无关蜂种、满槽和拒收槽。 */
	int[] eligibleSlots(int index) {
		if (eligibleSlots[index] == null) {
			int[] capacities = limits(index);
			int[] slots = new int[snapshot.slotCount];
			int count = 0;
			for (int i = 0; i < capacities.length; i++) {
				if (capacities[i] > snapshot.slotCounts[i]) slots[count++] = i;
			}
			eligibleSlots[index] = Arrays.copyOf(slots, count);
		}
		return eligibleSlots[index];
	}

	boolean matches(int left, int right) {
		if (right < 0) return false;
		if (left == right || templates[left] == templates[right]) return true;
		if (matches[left] == null) matches[left] = new byte[size()];
		byte match = matches[left][right];
		if (match == 0) {
			match = (byte) (ItemStack.isSameItemSameComponents(templates[right], templates[left]) ? 1 : -1);
			matches[left][right] = match;
		}
		return match == 1;
	}
}
