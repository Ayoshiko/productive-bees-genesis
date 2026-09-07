package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.List;

/**
 * 喂食槽逐格禁用状态
 * <br/>
 * 职责（SRP）：禁用标志的读写、位掩码编解码（容器同步）与 NBT 持久化。
 * {@link FeederSlotManager} 只保留槽位容器与花朵判定编排，禁用这条正交关注点收拢到本类。
 * <p>
 * 真值来源仍在 {@link FeederInventorySlot#isDisabled()} 上——花朵匹配是 tick 热路径，
 * 遍历时已持有槽位引用，就地读标志零间接层；本类持有的是同一个 List 的引用而非副本。
 * <p>
 * 位掩码而非"每格一个布尔同步器"：60 槽只需 1 个 {@code long}，
 * 比 60 个 {@code SyncableBoolean} 少两个数量级的 tracker。
 * 打包结果按外部传入的状态版本号缓存，因为 MEK 每 tick 都会轮询 getter。
 */
final class FeederSlotDisableState {

	/** NBT key — 逐格禁用位掩码（LongArray；旧存档无此键时全部启用） */
	private static final String NBT_KEY = "productivebeesgenesis_feeder_disabled";

	/** 单个同步字承载的槽位数 */
	private static final int BITS_PER_WORD = 64;

	/** 槽位列表（与 {@link FeederSlotManager} 共享同一实例，非副本） */
	private final List<FeederInventorySlot> slots;

	/** 声明槽位数（用于同步字数量；构建槽位前 slots 可能为空，不能用 size()） */
	private final int slotCount;

	/** 位掩码缓存与其对应的状态版本号 */
	private long[] cachedWords;
	private int cachedWordsVersion = -1;

	FeederSlotDisableState(List<FeederInventorySlot> slots, int slotCount) {
		this.slots = slots;
		this.slotCount = slotCount;
	}

	/**
	 * 同步/持久化所需的字数量
	 * <br/>
	 * 至少返回 1：容器 tracker 数量必须在客户端与服务端严格一致，
	 * 恒定注册一个字可避免异常槽位数（0）时两端 tracker 数量漂移。
	 *
	 * @param slotCount 喂食槽总数
	 * @return 位掩码字数量（≥1）
	 */
	static int wordCount(int slotCount) {
		if (slotCount <= 0) return 1;
		return Math.max(1, (slotCount + BITS_PER_WORD - 1) / BITS_PER_WORD);
	}

	/** 本实例的同步字数量 */
	int wordCount() {
		return wordCount(slotCount);
	}

	/** 指定格是否被玩家禁用（索引越界返回 false） */
	boolean isDisabled(int index) {
		return index >= 0 && index < slots.size() && slots.get(index).isDisabled();
	}

	/**
	 * 指定格是否处于"有物品但被禁用"状态 — 供 GUI 渲染灰色遮罩使用
	 * <br/>
	 * 与 {@link #isDisabled} 的区别：空格子的禁用标志无意义（放入物品前不该显示为灰），
	 * 客户端同步的位掩码与虚拟槽位内容到达顺序不定，故渲染判据必须同时校验非空。
	 */
	boolean isBlocked(int index) {
		if (index < 0 || index >= slots.size()) return false;
		FeederInventorySlot slot = slots.get(index);
		return slot.isDisabled() && !slot.isEmpty();
	}

	/**
	 * 切换指定格的禁用状态
	 * <br/>
	 * 空格子直接拒绝（"格子没有物品的时候无效"）。
	 *
	 * @return true 表示状态已改变（调用方据此推进状态版本号并标记存档脏）
	 */
	boolean toggle(int index) {
		if (index < 0 || index >= slots.size()) return false;
		FeederInventorySlot slot = slots.get(index);
		if (slot.isEmpty()) return false;
		return slot.setDisabled(!slot.isDisabled());
	}

	/**
	 * 批量设置全部格子的禁用状态
	 * <br/>
	 * 传绝对目标状态而非逐格翻转：多人同时操作时翻转会互相打架，绝对值天然幂等。
	 * 空格子跳过（与单格语义一致）。
	 *
	 * @param disabled true = 全部停用，false = 全部恢复
	 * @return true 表示至少有一格状态改变
	 */
	boolean setAll(boolean disabled) {
		boolean changed = false;
		for (int i = 0; i < slots.size(); i++) {
			FeederInventorySlot slot = slots.get(i);
			if (slot.isEmpty()) continue;
			if (slot.setDisabled(disabled)) changed = true;
		}
		return changed;
	}

	/**
	 * 读取指定同步字的位掩码（服务端 → 客户端）
	 * <br/>
	 * MEK 每 tick 通过 {@code SyncableLong.isDirty()} 调用本方法，故按状态版本号缓存打包结果，
	 * 稳态退化为一次数组读。
	 *
	 * @param wordIndex 字下标
	 * @param version   当前喂食槽状态版本号（内容或禁用标志变化即递增）
	 */
	long word(int wordIndex, int version) {
		long[] words = cachedWords;
		int expectedLength = wordCount();
		if (words == null || words.length != expectedLength) {
			words = new long[expectedLength];
			cachedWords = words;
			cachedWordsVersion = -1;
		}
		if (cachedWordsVersion != version) {
			for (int i = 0; i < words.length; i++) {
				words[i] = packWord(i);
			}
			cachedWordsVersion = version;
		}
		return wordIndex >= 0 && wordIndex < words.length ? words[wordIndex] : 0L;
	}

	/**
	 * 按位掩码写回槽位禁用标志（客户端同步回调路径）
	 * <br/>
	 * 客户端不做空槽过滤：位掩码与虚拟槽位内容属于两条独立同步通道，
	 * 到达顺序不定，过滤会让先到的掩码被后到的空内容永久丢弃。
	 * 渲染侧用 {@link #isBlocked} 兼顾「非空 + 已禁用」判定。
	 */
	void applyWord(int wordIndex, long word) {
		int base = wordIndex * BITS_PER_WORD;
		int end = Math.min(slots.size(), base + BITS_PER_WORD);
		for (int i = base; i < end; i++) {
			slots.get(i).setDisabled((word & (1L << (i - base))) != 0L);
		}
	}

	/** 保存禁用位掩码；全部启用时不写键，避免旧存档体积无谓增长 */
	void save(CompoundTag nbt) {
		if (slots.isEmpty()) return;
		long[] words = new long[wordCount()];
		boolean any = false;
		for (int i = 0; i < words.length; i++) {
			words[i] = packWord(i);
			if (words[i] != 0L) any = true;
		}
		if (any) nbt.putLongArray(NBT_KEY, words);
	}

	/**
	 * 加载禁用位掩码
	 * <br/>
	 * 必须在槽位内容反序列化之后调用：{@link FeederInventorySlot#onContentsChanged()}
	 * 会在槽位变空时清除禁用标志，顺序颠倒会把刚读出的标志清掉。
	 * 空槽位的脏数据位被忽略（禁用对空格子无意义）。
	 */
	void load(CompoundTag nbt) {
		if (slots.isEmpty()) return;
		if (!nbt.contains(NBT_KEY, Tag.TAG_LONG_ARRAY)) return;
		long[] words = nbt.getLongArray(NBT_KEY);
		for (int i = 0; i < slots.size(); i++) {
			int wordIndex = i / BITS_PER_WORD;
			boolean disabled = wordIndex < words.length
					&& (words[wordIndex] & (1L << (i % BITS_PER_WORD))) != 0L;
			FeederInventorySlot slot = slots.get(i);
			slot.setDisabled(disabled && !slot.isEmpty());
		}
	}

	/** 打包指定字内的禁用位 */
	private long packWord(int wordIndex) {
		long word = 0L;
		int base = wordIndex * BITS_PER_WORD;
		int end = Math.min(slots.size(), base + BITS_PER_WORD);
		for (int i = base; i < end; i++) {
			if (slots.get(i).isDisabled()) {
				word |= 1L << (i - base);
			}
		}
		return word;
	}
}
