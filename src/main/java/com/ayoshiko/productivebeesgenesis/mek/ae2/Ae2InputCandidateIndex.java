package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 无空标准车道时按原目录顺序投影补料候选；只索引完整键位置，不保留准入或库存结果。 */
final class Ae2InputCandidateIndex {

	private final Object2IntOpenHashMap<AEItemKey> positions = new Object2IntOpenHashMap<>();
	private final int[] refillPositions = new int[Long.SIZE];
	private final List<AEItemKey> projectedKeys = new ArrayList<>(Long.SIZE);
	private boolean indexed;

	Ae2InputCandidateIndex() {
		positions.defaultReturnValue(-1);
	}

	/** 调用方在服务端重建本机候选列表时清理；普通全目录扫描不建立这份索引。 */
	void clear() {
		positions.clear();
		projectedKeys.clear();
		indexed = false;
	}

	List<AEItemKey> project(List<AEItemKey> candidates, Ae2InputLaneSnapshot lanes, AEItemKey cursor) {
		projectedKeys.clear();
		if (!indexed) {
			for (int index = 0; index < candidates.size(); index++) {
				positions.put(candidates.get(index), index);
			}
			indexed = true;
		}
		int cursorPosition = cursor == null ? -1 : positions.getInt(cursor);
		int start = cursorPosition + 1;
		if (start == candidates.size()) start = 0;
		int count = 0;
		for (long remaining = lanes.refillableLanes(); remaining != 0L; remaining &= remaining - 1) {
			int lane = Long.numberOfTrailingZeros(remaining);
			int position = positions.getInt(lanes.currentKey(lane));
			if (position >= 0) refillPositions[count++] = position;
		}
		Arrays.sort(refillPositions, 0, count);
		int pivot = 0;
		while (pivot < count && refillPositions[pivot] < start) pivot++;
		int previous = -1;
		for (int visited = 0; visited < count; visited++) {
			int position = refillPositions[(pivot + visited) % count];
			if (position != previous) projectedKeys.add(candidates.get(position));
			previous = position;
		}
		return projectedKeys;
	}
}
