package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * AE2 输入过滤器 — 蜜脾种类的白名单/黑名单数据模型（位置固定 + 精确模式）
 * <br/>
 * 提供 per-tile 的蜜蜂类型过滤能力，独立于全局配置，由 {@link Ae2OutputStateHolder} 持有引用。
 * <p>
 * <b>V15 变更</b>：
 * <ul>
 *   <li>filterEntries 从 {@code List<String>} 改为固定大小 {@code String[]} 数组，
 *       支持"空槽位"表达，实现真正的"位置固定"语义（参考 AE2 GenericStackInv）</li>
 *   <li>removeEntryAt 仅清空目标位，不再移位，保证其他条目位置不变</li>
 *   <li>新增 ensureCapacity / setEntryAtIndex / getCapacity / getNonEmptyEntries / IndexedEntry</li>
 *   <li>序列化改为带 index 的 CompoundTag 列表，向后兼容旧 StringTag 紧凑格式</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：filterMode/preciseMode/slots 均使用 volatile 发布，
 * 每次修改都 clone→set→publish（CopyOnWrite 语义），遍历弱一致。
 * 写方法使用 synchronized 互斥，防止并发 clone→modify→publish 丢失修改。
 * 设计上保证 AE2 拉取 tick 线程与 GUI 配置线程并发访问安全。
 *
 * @since 1.0.0
 */
public final class Ae2InputFilter {

	/** 过滤模式枚举 */
	public enum FilterMode {
		/** 禁用过滤 */
		DISABLED,
		/** 白名单：仅拉取列表中的种类 */
		WHITELIST,
		/** 黑名单：拉取列表外的种类 */
		BLACKLIST
	}

	/** 默认容量：4 页 × 24 槽位 */
	private static final int DEFAULT_CAPACITY = 96;

	/** 过滤槽位最大容量上界（防止恶意 index 导致 OOM） */
	private static final int MAX_FILTER_SLOTS = 1024;

	/** 过滤模式（默认 DISABLED） */
	private volatile FilterMode filterMode = FilterMode.DISABLED;

	/**
	 * 精确模式 — true 时区分蜜脾和蜜脾块
	 * <br/>
	 * false（默认）：同种 beeType 的蜜脾和蜜脾块一起拉取（向后兼容）
	 * true：仅拉取精确匹配的物品类型（蜜脾标记不会拉取蜜脾块，反之亦然）
	 */
	private volatile boolean preciseMode = false;

	/**
	 * 过滤条目固定大小数组（位置固定模式）
	 * <br/>
	 * 使用 volatile 发布，每次修改都 clone→set→publish（CopyOnWrite 语义）。
	 * null 表示空槽位，非 null 字符串为条目。
	 * <p>
	 * 精确模式下，条目格式为 {@code beeType} 或 {@code beeType#block}（后缀 #block 表示蜜脾块）。
	 * 非精确模式下，条目仅为 {@code beeType}，蜜脾和蜜脾块共享同一过滤条目。
	 */
	private volatile String[] slots = new String[DEFAULT_CAPACITY];

	public FilterMode getFilterMode() {
		return filterMode;
	}

	public void setFilterMode(FilterMode mode) {
		this.filterMode = mode;
	}

	/** 循环切换过滤模式：DISABLED → WHITELIST → BLACKLIST → DISABLED */
	public synchronized void cycleFilterMode() {
		FilterMode[] modes = FilterMode.values();
		filterMode = modes[(filterMode.ordinal() + 1) % modes.length];
	}

	public boolean isPreciseMode() {
		return preciseMode;
	}

	public void setPreciseMode(boolean precise) {
		this.preciseMode = precise;
	}

	/** 切换精确模式 */
	public synchronized void togglePreciseMode() {
		this.preciseMode = !this.preciseMode;
	}

	/**
	 * 在指定位置设置过滤条目（位置固定模式的核心操作）
	 * <br/>
	 * V15：直接覆盖目标位置，不做交换或去重（位置固定语义）。
	 * 精确模式下根据 isBlock 添加 #block 后缀；非精确模式仅存储 beeType。
	 *
	 * @param index   目标位置（0-based），自动扩容
	 * @param beeType 蜜蜂类型 ID
	 * @param isBlock 是否为蜜脾块（仅 preciseMode 时区分）
	 */
	public synchronized void setEntryAt(int index, ResourceLocation beeType, boolean isBlock) {
		if (beeType == null || index < 0 || index > MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String entry = preciseMode ? formatEntry(beeType, isBlock) : beeType.toString();
		String[] arr = slots.clone(); // CopyOnWrite
		arr[index] = entry;
		slots = arr; // volatile 发布
	}

	/**
	 * 移除指定位置的过滤条目
	 * <br/>
	 * V15：仅清空该位，不移位！保证其他条目位置不变。
	 *
	 * @param index 目标位置
	 */
	public synchronized void removeEntryAt(int index) {
		if (index < 0 || index >= slots.length) return;
		if (slots[index] == null) return;
		String[] arr = slots.clone();
		arr[index] = null; // 仅清该位，不移位
		slots = arr;
	}

	/**
	 * 获取指定位置的条目信息
	 *
	 * @param index 位置
	 * @return 条目信息，或 null（位置越界或空槽位）
	 */
	public EntryInfo getEntryAt(int index) {
		if (index < 0 || index >= slots.length || slots[index] == null) return null;
		return parseEntry(slots[index]);
	}

	/** 清空所有过滤条目（保留容量，全部置 null） */
	public synchronized void clearEntries() {
		String[] arr = slots.clone();
		Arrays.fill(arr, null);
		slots = arr;
	}

	/**
	 * 判断指定蜜蜂类型是否允许拉取
	 * <br/>
	 * 根据 filterMode 和 preciseMode 判断：
	 * <ul>
	 *   <li>DISABLED：所有蜜脾都允许（返回 true）</li>
	 *   <li>WHITELIST：仅在过滤列表中的蜜蜂类型允许</li>
	 *   <li>BLACKLIST：仅在过滤列表中的蜜蜂类型禁止</li>
	 * </ul>
	 * preciseMode=true 时，蜜脾和蜜脾块分别匹配；false 时共享同一过滤条目。
	 *
	 * @param beeType 蜜蜂类型 ID
	 * @param isBlock 是否为蜜脾块
	 * @return true 表示允许拉取
	 */
	public boolean isAllowed(ResourceLocation beeType, boolean isBlock) {
		if (beeType == null) return true;
		String entry = preciseMode ? formatEntry(beeType, isBlock) : beeType.toString();
		String[] arr = slots; // volatile 读
		switch (filterMode) {
			case WHITELIST:
				for (String s : arr) {
					if (entry.equals(s)) return true;
				}
				return false;
			case BLACKLIST:
				for (String s : arr) {
					if (entry.equals(s)) return false;
				}
				return true;
			case DISABLED:
			default:
				return true;
		}
	}

	/**
	 * 扩容到至少 minCapacity — 新位置自动为 null
	 *
	 * @param minCapacity 最小需要的容量
	 */
	public synchronized void ensureCapacity(int minCapacity) {
		if (minCapacity > MAX_FILTER_SLOTS + 1) return;
		if (slots.length >= minCapacity) return;
		String[] newArr = new String[minCapacity];
		System.arraycopy(slots, 0, newArr, 0, slots.length);
		slots = newArr;
	}

	/**
	 * 直接按 index 设置原始字符串条目（用于网络同步和升级数据恢复）
	 * <br/>
	 * 保留 #block 后缀，不做 preciseMode 转换。
	 *
	 * @param index    目标位置
	 * @param rawEntry 原始条目字符串（null/空白视为清空该位）
	 */
	public synchronized void setEntryAtIndex(int index, String rawEntry) {
		if (index < 0 || index > MAX_FILTER_SLOTS) return;
		ensureCapacity(index + 1);
		String[] arr = slots.clone();
		arr[index] = (rawEntry == null || rawEntry.isBlank()) ? null : rawEntry;
		slots = arr;
	}

	/** 返回当前数组容量 */
	public int getCapacity() {
		return slots.length;
	}

	/**
	 * 返回所有非空条目（index + entry 字符串），用于网络同步
	 *
	 * @return 非空条目列表
	 */
	public List<IndexedEntry> getNonEmptyEntries() {
		List<IndexedEntry> result = new ArrayList<>();
		String[] arr = slots; // volatile 读
		for (int i = 0; i < arr.length; i++) {
			if (arr[i] != null) {
				result.add(new IndexedEntry(i, arr[i]));
			}
		}
		return result;
	}

	/** 格式化条目字符串（精确模式下蜜脾块添加 #block 后缀） */
	private static String formatEntry(ResourceLocation beeType, boolean isBlock) {
		return isBlock ? beeType.toString() + "#block" : beeType.toString();
	}

	/** 解析条目字符串为 EntryInfo */
	private static EntryInfo parseEntry(String entry) {
		if (entry.endsWith("#block")) {
			String beeTypeStr = entry.substring(0, entry.length() - 6);
			ResourceLocation beeType = ResourceLocation.tryParse(beeTypeStr);
			return new EntryInfo(beeType, true);
		} else {
			ResourceLocation beeType = ResourceLocation.tryParse(entry);
			return new EntryInfo(beeType, false);
		}
	}

	/**
	 * 序列化到 NBT
	 * <br/>
	 * NBT 结构（V15 新格式）：
	 * <ul>
	 *   <li>mode (Byte): 0=DISABLED, 1=WHITELIST, 2=BLACKLIST</li>
	 *   <li>precise (Byte): 0=非精确模式, 1=精确模式</li>
	 *   <li>entries (ListTag of CompoundTag): 含 index(i) 和 entry(v)，
	 *       写入 0..lastNonNull（含中间 null，用空字符串表示）</li>
	 * </ul>
	 *
	 * @param tag 目标 NBT 标签
	 */
	public void save(CompoundTag tag) {
		tag.putByte("mode", (byte) filterMode.ordinal());
		tag.putByte("precise", (byte) (preciseMode ? 1 : 0));
		String[] arr = slots; // volatile 读
		ListTag entriesTag = new ListTag();
		// 找到最后一个非 null 槽位
		int lastNonNull = -1;
		for (int i = arr.length - 1; i >= 0; i--) {
			if (arr[i] != null) {
				lastNonNull = i;
				break;
			}
		}
		// 写入 0..lastNonNull（含中间 null，用空字符串表示）
		for (int i = 0; i <= lastNonNull; i++) {
			CompoundTag entryTag = new CompoundTag();
			entryTag.putInt("i", i);
			entryTag.putString("v", arr[i] == null ? "" : arr[i]);
			entriesTag.add(entryTag);
		}
		tag.put("entries", entriesTag);
	}

	/**
	 * 从 NBT 加载
	 * <br/>
	 * 向后兼容策略：
	 * <ul>
	 *   <li>V15 新格式：entries 为 CompoundTag 列表（含 i + v），按 index 写入数组</li>
	 *   <li>V12/V13 旧格式：entries 为 StringTag 列表，按顺序填入 0,1,2...</li>
	 * </ul>
	 *
	 * @param tag 源 NBT 标签
	 */
	public synchronized void load(CompoundTag tag) {
		if (tag.contains("mode")) {
			int ordinal = tag.getByte("mode");
			FilterMode[] modes = FilterMode.values();
			if (ordinal >= 0 && ordinal < modes.length) {
				filterMode = modes[ordinal];
			}
		}
		preciseMode = tag.contains("precise") && tag.getByte("precise") == 1;
		if (tag.contains("entries", Tag.TAG_LIST)) {
			// 局部构建新数组，最后一次性 volatile 发布，避免 load 期间并发读到部分加载的快照
			String[] newSlots = new String[DEFAULT_CAPACITY];
			ListTag entriesTag = tag.getList("entries", Tag.TAG_COMPOUND);
			if (!entriesTag.isEmpty()) {
				// V15 新格式：CompoundTag 含 index
				for (int i = 0; i < entriesTag.size(); i++) {
					CompoundTag entryTag = entriesTag.getCompound(i);
					int idx = entryTag.getInt("i");
					String val = entryTag.getString("v");
					// 防御恶意 index 导致 OOM
					if (idx < 0 || idx > MAX_FILTER_SLOTS) continue;
					// 局部扩容
					if (idx >= newSlots.length) {
						String[] grown = new String[idx + 1];
						System.arraycopy(newSlots, 0, grown, 0, newSlots.length);
						newSlots = grown;
					}
					newSlots[idx] = val.isEmpty() ? null : val;
				}
			} else {
				// 向后兼容：旧紧凑 StringTag 格式，按顺序填入 0,1,2...
				ListTag oldEntries = tag.getList("entries", Tag.TAG_STRING);
				if (!oldEntries.isEmpty()) {
					if (oldEntries.size() > newSlots.length) {
						newSlots = new String[oldEntries.size()];
					}
					for (int i = 0; i < oldEntries.size(); i++) {
						String val = oldEntries.getString(i);
						newSlots[i] = val.isEmpty() ? null : val;
					}
				}
			}
			// 一次性发布完整数组
			slots = newSlots;
		}
	}

	/** 条目信息 — 解析后的过滤条目 */
	public static final class EntryInfo {
		public final ResourceLocation beeType;
		public final boolean isBlock;

		EntryInfo(ResourceLocation beeType, boolean isBlock) {
			this.beeType = beeType;
			this.isBlock = isBlock;
		}
	}

	/** 带位置索引的条目记录 — 用于网络同步 */
	public record IndexedEntry(int index, String entry) {}
}
