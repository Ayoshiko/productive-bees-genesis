package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * AE2 输入过滤器 NBT 编解码（纯静态，无状态）
 * <br/>
 * 从 {@link Ae2InputFilter} 拆分而来，职责（SRP）：仅负责过滤器的持久化格式
 * 编解码与向后兼容迁移，不参与过滤匹配等运行时逻辑。
 * <p>
 * NBT 结构（V15 新格式）：
 * <ul>
 *   <li>mode (Byte): 0=DISABLED, 1=WHITELIST, 2=BLACKLIST</li>
 *   <li>precise (Byte): 0=非精确模式, 1=精确模式</li>
 *   <li>entries (ListTag of CompoundTag): 含 index(i) 和 entry(v)，
 *       写入 0..lastNonNull（含中间 null，用空字符串表示）</li>
 * </ul>
 * 旧格式（V12/V13）entries 为 StringTag 列表，按顺序填入 0,1,2...
 * <p>
 * 线程安全：本类无状态；调用方负责 volatile 发布加载结果。
 */
final class Ae2InputFilterNbtCodec {
	static final int CURRENT_FORMAT_VERSION = 1;
	private static final String KEY_VERSION = "version";
	private static final String KEY_CAPACITY = "capacity";

	private Ae2InputFilterNbtCodec() {
	}

	/**
	 * 序列化到 NBT
	 *
	 * @param tag              目标 NBT 标签
	 * @param filterMode       过滤模式
	 * @param preciseMode      精确模式
	 * @param slots            条目数组（volatile 读快照）
	 * @param directAmounts    直连条目数量数组
	 * @param directReserveAmounts 直连条目网络库存保留量数组
	 * @param directUnlimited  直连条目无限提供标记数组
	 */
	static void save(CompoundTag tag, Ae2InputFilter.FilterMode filterMode, boolean preciseMode,
			String[] slots, long[] directAmounts, long[] directReserveAmounts, boolean[] directUnlimited,
			boolean[] directNetworkStock, boolean unlimitedAllFallback) {
		tag.putInt(KEY_VERSION, CURRENT_FORMAT_VERSION);
		tag.putInt(KEY_CAPACITY, slots.length);
		tag.putByte("mode", (byte) filterMode.ordinal());
		tag.putByte("precise", (byte) (preciseMode ? 1 : 0));
		tag.putBoolean("unlimitedAllFallback", unlimitedAllFallback);
		ListTag entriesTag = new ListTag();
		// 找到最后一个非 null 槽位
		int lastNonNull = -1;
		for (int i = slots.length - 1; i >= 0; i--) {
			if (slots[i] != null) {
				lastNonNull = i;
				break;
			}
		}
		// 写入 0..lastNonNull（含中间 null，用空字符串表示）
		for (int i = 0; i <= lastNonNull; i++) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putInt("i", i);
			entryTag.putString("v", slots[i] == null ? "" : slots[i]);
			if (Ae2InputFilter.isDirectFingerprint(slots[i])) {
				entryTag.putLong("a", Math.max(0L, directAmounts[i]));
				entryTag.putLong("r", Math.max(0L, directReserveAmounts[i]));
				entryTag.putBoolean("u", directUnlimited[i]);
				entryTag.putBoolean("n", directNetworkStock[i]);
			}
			entriesTag.add(entryTag);
		}
		tag.put("entries", entriesTag);
	}

	/**
	 * 从 NBT 加载（向后兼容新旧格式）
	 * <br/>
	 * 模式与精确标记始终解析；entries 缺失时返回空数组并标记 entriesPresent=false，
	 * 由调用方决定是否替换现有槽位数组。调用方负责一次性 volatile 发布，
	 * 避免加载期间并发读到部分快照。
	 *
	 * @param tag 源 NBT 标签
	 * @return 加载结果（永不为 null）
	 */
	static LoadResult load(CompoundTag tag) {
		Ae2InputFilter.FilterMode filterMode = Ae2InputFilter.FilterMode.DISABLED;
		if (tag.contains("mode")) {
			int ordinal = tag.getByte("mode");
			Ae2InputFilter.FilterMode[] modes = Ae2InputFilter.FilterMode.values();
			if (ordinal >= 0 && ordinal < modes.length) {
				filterMode = modes[ordinal];
			}
		}
		boolean preciseMode = tag.contains("precise") && tag.getByte("precise") == 1;
		boolean unlimitedAllFallback = tag.contains("unlimitedAllFallback") && tag.getBoolean("unlimitedAllFallback");
		if (!tag.contains("entries", Tag.TAG_LIST)) {
			return new LoadResult(filterMode, preciseMode, unlimitedAllFallback, false,
					new String[0], new long[0], new long[0], new boolean[0], new boolean[0]);
		}
		// 局部构建新数组，最后一次性返回，由调用方 volatile 发布
		int persistedCapacity = tag.contains(KEY_CAPACITY, Tag.TAG_ANY_NUMERIC)
				? tag.getInt(KEY_CAPACITY) : Ae2InputFilter.getDefaultCapacity();
		int initialCapacity = Math.max(Ae2InputFilter.getDefaultCapacity(),
				Math.min(Ae2InputFilter.getMaxFilterSlots(), persistedCapacity));
		String[] newSlots = new String[initialCapacity];
		long[] newAmounts = new long[initialCapacity];
		long[] newReserveAmounts = new long[initialCapacity];
		boolean[] newUnlimited = new boolean[initialCapacity];
		boolean[] newNetworkStock = new boolean[initialCapacity];
		ListTag entriesTag = tag.getList("entries", Tag.TAG_COMPOUND);
		if (!entriesTag.isEmpty()) {
			// V15 新格式：CompoundTag 含 index
			for (int i = 0; i < entriesTag.size(); i++) {
				CompoundTag entryTag = entriesTag.getCompound(i);
				// 损坏 NBT 容错：缺失/类型不符的 i 键直接跳过该条目，
				// 避免 getInt 默认返回 0 导致条目错误覆盖索引 0 的既有条目
				if (!entryTag.contains("i", Tag.TAG_ANY_NUMERIC)) {
					continue;
				}
				int idx = entryTag.getInt("i");
				String val = entryTag.getString("v");
				// 防御恶意 index 导致 OOM
				if (idx < 0 || idx >= Ae2InputFilter.getMaxFilterSlots()) {
					continue;
				}
				// 局部扩容
				if (idx >= newSlots.length) {
					newSlots = grow(newSlots, idx + 1);
					newAmounts = grow(newAmounts, idx + 1);
					newReserveAmounts = grow(newReserveAmounts, idx + 1);
					newUnlimited = grow(newUnlimited, idx + 1);
					newNetworkStock = grow(newNetworkStock, idx + 1);
				}
				newSlots[idx] = val.isEmpty() ? null : val;
				newAmounts[idx] = Ae2InputFilter.isDirectFingerprint(newSlots[idx])
						? Math.min(Ae2InputFilter.getMaxDirectAmount(), Math.max(0L, entryTag.contains("a", Tag.TAG_LONG)
							? entryTag.getLong("a") : Ae2InputFilter.DEFAULT_DIRECT_AMOUNT)) : 0L;
				newReserveAmounts[idx] = Ae2InputFilter.isDirectFingerprint(newSlots[idx])
						? Ae2InputFilter.clampDirectReserveAmount(Math.max(0L,
							entryTag.contains("r", Tag.TAG_LONG) ? entryTag.getLong("r") : 0L)) : 0L;
				boolean legacyStock = entryTag.getBoolean("u");
				newUnlimited[idx] = Ae2InputFilter.isDirectFingerprint(newSlots[idx]) && legacyStock;
				newNetworkStock[idx] = Ae2InputFilter.isDirectFingerprint(newSlots[idx])
						&& (entryTag.contains("n", Tag.TAG_BYTE) ? entryTag.getBoolean("n") : legacyStock);
			}
		} else {
			// 向后兼容：旧紧凑 StringTag 格式，按顺序填入 0,1,2...
			// 注意：条目数受 MAX_FILTER_SLOTS 上限约束（与新格式 index 守卫一致），
			// 防止损坏/恶意 NBT 触发远超预期的数组分配
			ListTag oldEntries = tag.getList("entries", Tag.TAG_STRING);
			int oldCount = Math.min(oldEntries.size(), Ae2InputFilter.getMaxFilterSlots());
			if (oldCount > newSlots.length) {
				newSlots = new String[oldCount];
				newAmounts = new long[oldCount];
				newReserveAmounts = new long[oldCount];
				newUnlimited = new boolean[oldCount];
				newNetworkStock = new boolean[oldCount];
			}
			for (int i = 0; i < oldCount; i++) {
				String val = oldEntries.getString(i);
				newSlots[i] = val.isEmpty() ? null : val;
				newAmounts[i] = Ae2InputFilter.isDirectFingerprint(newSlots[i])
						? Ae2InputFilter.DEFAULT_DIRECT_AMOUNT : 0L;
			}
		}
		return new LoadResult(filterMode, preciseMode, unlimitedAllFallback, true, newSlots, newAmounts,
				newReserveAmounts, newUnlimited, newNetworkStock);
	}

	private static String[] grow(String[] src, int minCapacity) {
		String[] grown = new String[minCapacity];
		System.arraycopy(src, 0, grown, 0, src.length);
		return grown;
	}

	private static long[] grow(long[] src, int minCapacity) {
		long[] grown = new long[minCapacity];
		System.arraycopy(src, 0, grown, 0, src.length);
		return grown;
	}

	private static boolean[] grow(boolean[] src, int minCapacity) {
		boolean[] grown = new boolean[minCapacity];
		System.arraycopy(src, 0, grown, 0, src.length);
		return grown;
	}

	/** 加载结果快照（数组均为新分配，可直接发布） */
	record LoadResult(Ae2InputFilter.FilterMode filterMode, boolean preciseMode, boolean unlimitedAllFallback,
		boolean entriesPresent, String[] slots, long[] directAmounts, long[] directReserveAmounts,
			boolean[] directUnlimited, boolean[] directNetworkStock) {
	}
}
