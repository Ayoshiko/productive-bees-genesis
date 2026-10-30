package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;

/**
	 * AE2 输出的确认式结算账本。
	 * <p>
	 * ME 已接受但源槽尚未确认扣除的数量会冻结对应槽位，并通过方块实体 NBT 持久化。
	 * 下一 tick 必须先偿还账本，才能再次扫描该槽位向 ME 提交。
	 */
public final class Ae2OutputLedger {
	public static final int MAX_ENTRIES = 256;
	private static final String KEY_ENTRIES = "entries";
	private static final String KEY_PROCESS = "process";
	private static final String KEY_SLOT = "slot";
	private static final String KEY_FINGERPRINT = "fingerprint";
	private static final String KEY_ORIGINAL_COUNT = "original_count";
	private static final String KEY_REMAINING = "remaining";

	private final List<Settlement> entries = new ArrayList<>();

	/** 预留一个源槽结算记录，防止账本容量不足时先向 ME 提交。 */
	public boolean reserve(int process, int slot, String fingerprint, int originalCount) {
		if (process < 0 || slot < 0 || fingerprint == null || fingerprint.isBlank()
				|| originalCount <= 0 || entries.size() >= MAX_ENTRIES || hasSlot(process, slot)) {
			return false;
		}
		entries.add(new Settlement(process, slot, fingerprint, originalCount, 0));
		return true;
	}

	/** 记录 ME 已接受量。零接受记录会被取消。 */
	public void commitAccepted(int process, int slot, int accepted) {
		Settlement entry = find(process, slot);
		if (entry == null) return;
		if (accepted <= 0) {
			entries.remove(entry);
		} else {
			entry.remaining = Math.min(entry.originalCount, accepted);
		}
	}

	/** 确认已经从本地槽扣除的数量。 */
	public void confirm(int process, int slot, int confirmed) {
		Settlement entry = find(process, slot);
		if (entry == null || confirmed <= 0) return;
		entry.remaining -= Math.min(entry.remaining, confirmed);
		if (entry.remaining <= 0) entries.remove(entry);
	}

	/** 取消尚未向 ME 提交的预留。 */
	public void cancel(int process, int slot) {
		Settlement entry = find(process, slot);
		if (entry != null && entry.remaining <= 0) entries.remove(entry);
	}

	public boolean hasSlot(int process, int slot) {
		return find(process, slot) != null;
	}

	public List<Settlement> snapshot() {
		List<Settlement> result = new ArrayList<>(entries.size());
		for (Settlement entry : entries) result.add(entry.copy());
		return result;
	}

	public int size() { return entries.size(); }

	public void save(CompoundTag parent) {
		if (entries.isEmpty()) {
			parent.remove(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER);
			return;
		}
		ListTag list = new ListTag();
		for (Settlement entry : entries) {
			if (entry.remaining <= 0) continue;
			CompoundTag tag = new CompoundTag();
			tag.putInt(KEY_PROCESS, entry.process);
			tag.putInt(KEY_SLOT, entry.slot);
			tag.putString(KEY_FINGERPRINT, entry.fingerprint);
			tag.putInt(KEY_ORIGINAL_COUNT, entry.originalCount);
			tag.putInt(KEY_REMAINING, entry.remaining);
			list.add(tag);
		}
		parent.put(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER, list);
	}

	public void load(CompoundTag parent) {
		clear();
		if (!parent.contains(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER, Tag.TAG_LIST)) return;
		ListTag list = parent.getList(Ae2NbtKeys.NBT_KEY_AE_OUTPUT_LEDGER, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size() && entries.size() < MAX_ENTRIES; i++) {
			CompoundTag tag = list.getCompound(i);
			int process = tag.getInt(KEY_PROCESS);
			int slot = tag.getInt(KEY_SLOT);
			String fingerprint = tag.getString(KEY_FINGERPRINT);
			int original = Math.max(0, tag.getInt(KEY_ORIGINAL_COUNT));
			int remaining = Math.min(original, Math.max(0, tag.getInt(KEY_REMAINING)));
			if (process < 0 || slot < 0 || fingerprint.isBlank() || original <= 0
					|| remaining <= 0 || hasSlot(process, slot)) continue;
			entries.add(new Settlement(process, slot, fingerprint, original, remaining));
		}
	}

	public void clear() { entries.clear(); }

	private Settlement find(int process, int slot) {
		for (Settlement entry : entries) {
			if (entry.process == process && entry.slot == slot) return entry;
		}
		return null;
	}

	/** 单个源槽的未确认结算记录。 */
	public static final class Settlement {
		private final int process;
		private final int slot;
		private final String fingerprint;
		private final int originalCount;
		private int remaining;

		private Settlement(int process, int slot, String fingerprint, int originalCount, int remaining) {
			this.process = process;
			this.slot = slot;
			this.fingerprint = fingerprint;
			this.originalCount = originalCount;
			this.remaining = remaining;
		}

		private Settlement copy() {
			return new Settlement(process, slot, fingerprint, originalCount, remaining);
		}

		public int process() { return process; }
		public int slot() { return slot; }
		public String fingerprint() { return fingerprint; }
		public int originalCount() { return originalCount; }
		public int remaining() { return remaining; }
	}
}
