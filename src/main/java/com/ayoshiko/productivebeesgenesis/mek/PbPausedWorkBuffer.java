package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 换料时保管明确已付的半途周期；预留在扣费前完成，领取只转移一次所有权。 */
final class PbPausedWorkBuffer {
	static final long MAX_WORK_BYTES = 2L * 1024 * 1024;

	private final Entry[] reserved;
	private final Map<Item, List<Entry>> paused = new LinkedHashMap<>();
	private long usedBytes = envelopeBytes();

	PbPausedWorkBuffer(int lanes) {
		reserved = new Entry[lanes];
	}

	boolean reserve(int lane, PbRecipeProcessorStateHelper.PaidWork work, HolderLookup.Provider provider) {
		Entry previous = reserved[lane];
		if (previous != null && previous.work == work) return true;
		Entry next = Entry.paid(work, provider);
		long replacementBytes = usedBytes - (previous == null ? 0 : previous.bytes) + next.bytes;
		if (replacementBytes > MAX_WORK_BYTES) return false;
		reserved[lane] = next;
		usedBytes = replacementBytes;
		return true;
	}

	/** 旧存档的已付内容不能因新增预算被丢弃；只暂停新增付款，原周期仍可结清。 */
	void restoreReservation(int lane, PbRecipeProcessorStateHelper.PaidWork work,
			ItemStack legacyInput, HolderLookup.Provider provider) {
		if (work == null && legacyInput.isEmpty()) return;
		Entry entry = work == null ? Entry.legacy(legacyInput, provider) : Entry.paid(work, provider);
		release(lane);
		reserved[lane] = entry;
		usedBytes += entry.bytes;
	}

	boolean park(int lane, int progress, int processingTime) {
		Entry entry = reserved[lane];
		if (entry == null || progress <= 0) return false;
		paused.computeIfAbsent(entry.input.getItem(), ignored -> new ArrayList<>())
				.add(entry.withProgress(progress, processingTime));
		reserved[lane] = null;
		return true;
	}

	Entry take(int lane, ItemStack input) {
		if (reserved[lane] != null || input.isEmpty()) return null;
		List<Entry> candidates = paused.get(input.getItem());
		if (candidates == null) return null;
		for (int index = 0; index < candidates.size(); index++) {
			Entry entry = candidates.get(index);
			if (entry.matches(input)) {
				candidates.remove(index);
				if (candidates.isEmpty()) paused.remove(input.getItem());
				reserved[lane] = entry;
				return entry;
			}
		}
		return null;
	}

	boolean hasInput(ItemStack input) {
		if (input.isEmpty()) return false;
		List<Entry> candidates = paused.get(input.getItem());
		if (candidates == null) return false;
		for (Entry entry : candidates) {
			if (entry.matches(input)) return true;
		}
		return false;
	}

	void release(int lane) {
		Entry entry = reserved[lane];
		if (entry != null) {
			usedBytes -= entry.bytes;
			reserved[lane] = null;
		}
	}

	void clear() {
		Arrays.fill(reserved, null);
		paused.clear();
		usedBytes = envelopeBytes();
	}

	ListTag save() {
		ListTag entries = new ListTag();
		for (List<Entry> values : paused.values()) {
			for (Entry entry : values) entries.add(entry.save());
		}
		return entries;
	}

	void load(Tag raw, HolderLookup.Provider provider) {
		if (raw == null) return;
		if (!(raw instanceof ListTag entries)
				|| !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND) {
			throw new IllegalArgumentException("Invalid paused centrifuge work collection");
		}
		List<Entry> restored = new ArrayList<>();
		long bytes = usedBytes;
		for (Tag value : entries) {
			Entry entry = Entry.load((CompoundTag) value, provider);
			bytes = Math.addExact(bytes, entry.bytes);
			// 旧版已付内容按原数保管；超预算时 reserve 拒绝新增付款，原周期仍可结清。
			restored.add(entry);
		}
		for (Entry entry : restored) {
			paused.computeIfAbsent(entry.input.getItem(), ignored -> new ArrayList<>()).add(entry);
		}
		usedBytes = bytes;
	}

	private static long envelopeBytes() {
		CompoundTag root = new CompoundTag();
		root.putInt("version", 2);
		root.put("entries", new ListTag());
		root.put("paused", new ListTag());
		return root.sizeInBytes();
	}

	static final class Entry {
		final PbRecipeProcessorStateHelper.PaidWork work;
		final ItemStack input;
		final int progress;
		final int processingTime;
		private final CompoundTag encoded;
		private final long bytes;

		private Entry(PbRecipeProcessorStateHelper.PaidWork work, ItemStack input, int progress,
				int processingTime, CompoundTag encoded, long bytes) {
			this.work = work;
			this.input = input;
			this.progress = progress;
			this.processingTime = processingTime;
			this.encoded = encoded;
			this.bytes = bytes;
		}

		static Entry paid(PbRecipeProcessorStateHelper.PaidWork work, HolderLookup.Provider provider) {
			CompoundTag encoded = new CompoundTag();
			encoded.put("work", work.save(provider));
			return create(work, work.input, encoded);
		}

		static Entry legacy(ItemStack input, HolderLookup.Provider provider) {
			CompoundTag encoded = new CompoundTag();
			encoded.put("legacy_input", input.copyWithCount(1).save(provider));
			return create(null, input.copyWithCount(1), encoded);
		}

		private static Entry create(PbRecipeProcessorStateHelper.PaidWork work,
				ItemStack input, CompoundTag encoded) {
			encoded.putInt("progress", 1);
			encoded.putInt("processing_time", 0);
			CompoundTag active = work == null ? new CompoundTag() : encoded.getCompound("work").copy();
			if (work == null) {
				active.putBoolean("legacy", true);
				active.put("input", encoded.get("legacy_input").copy());
			}
			active.putInt("process", 0);
			active.putBoolean("resume_cycle", true);
			ListTag sizeProbe = new ListTag();
			long emptyBytes = sizeProbe.sizeInBytes();
			sizeProbe.add(encoded);
			long pausedBytes = sizeProbe.sizeInBytes() - emptyBytes;
			sizeProbe.set(0, active);
			long activeBytes = sizeProbe.sizeInBytes() - emptyBytes;
			return new Entry(work, input, 0, 0, encoded, Math.max(pausedBytes, activeBytes));
		}

		Entry withProgress(int paid, int required) {
			return new Entry(work, input, paid, Math.max(0, required), encoded, bytes);
		}

		boolean matches(ItemStack candidate) {
			return ItemStack.isSameItemSameComponents(input, candidate);
		}

		CompoundTag save() {
			CompoundTag result = encoded.copy();
			result.putInt("progress", progress);
			result.putInt("processing_time", processingTime);
			return result;
		}

		static Entry load(CompoundTag tag, HolderLookup.Provider provider) {
			if (!Set.of("work", "legacy_input", "progress", "processing_time").containsAll(tag.getAllKeys())
					|| !tag.contains("progress", Tag.TAG_INT) || tag.getInt("progress") <= 0
					|| !tag.contains("processing_time", Tag.TAG_INT) || tag.getInt("processing_time") < 0
					|| tag.contains("work") == tag.contains("legacy_input")) {
				throw new IllegalArgumentException("Invalid paused centrifuge work");
			}
			Entry entry;
			if (tag.contains("work")) {
				if (!tag.contains("work", Tag.TAG_COMPOUND)
						|| tag.getCompound("work").contains("process")) {
					throw new IllegalArgumentException("Invalid paused recipe snapshot");
				}
				entry = paid(PbRecipeProcessorStateHelper.PaidWork.load(tag.getCompound("work"), provider), provider);
			} else {
				if (!tag.contains("legacy_input", Tag.TAG_COMPOUND)) {
					throw new IllegalArgumentException("Invalid paused legacy input");
				}
				ItemStack input = ItemStack.parse(provider, tag.getCompound("legacy_input")).orElseThrow();
				if (input.getCount() != 1) throw new IllegalArgumentException("Invalid paused input count");
				entry = legacy(input, provider);
			}
			return entry.withProgress(tag.getInt("progress"), tag.getInt("processing_time"));
		}
	}
}
