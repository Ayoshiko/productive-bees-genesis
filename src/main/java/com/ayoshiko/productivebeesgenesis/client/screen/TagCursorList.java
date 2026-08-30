package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.List;

/**
 * 带游标的字符串候选列表 —— 滚轮选择类交互的最小状态载体。
 * <p>
 * 职责（SRP）：只维护「候选序列 + 当前下标」，不知道候选是标签还是别的东西，
 * 因此「添加候选」与「移除候选」两条列表可以复用同一实现（OCP）。
 * <p>
 * <b>刷新时保留选中项</b>：列表每帧可能重算（表达式文本一变，可加/可删集合就变），
 * 若直接把 cursor 归零，玩家滚到第 5 项后一改文本就被弹回第 1 项。
 * 故 {@link #setEntries} 先记住当前项，重建后按值找回下标，找不到才归零。
 */
final class TagCursorList {

	/** 候选数上限，防止个别整合包物品挂上百标签时 tooltip 与取模被无意义拉长。 */
	static final int MAX_ENTRIES = 64;

	private List<String> entries = List.of();
	private int cursor;

	List<String> getEntries() {
		return entries;
	}

	int getCursor() {
		return cursor;
	}

	boolean isEmpty() {
		return entries.isEmpty();
	}

	/** 当前选中项；空列表返回 null。 */
	String current() {
		if (entries.isEmpty()) return null;
		return entries.get(Math.min(cursor, entries.size() - 1));
	}

	/** 重设候选（内容相同则完全不动，避免每帧无谓写入）。 */
	void setEntries(List<String> next) {
		List<String> bounded = next == null ? List.of()
				: next.size() <= MAX_ENTRIES ? List.copyOf(next) : List.copyOf(next.subList(0, MAX_ENTRIES));
		if (bounded.equals(entries)) return;
		String previous = current();
		entries = bounded;
		int index = previous == null ? -1 : entries.indexOf(previous);
		cursor = Math.max(index, 0);
	}

	void clear() {
		entries = List.of();
		cursor = 0;
	}

	/**
	 * 滚轮环形切换。
	 *
	 * @param delta 正数下一项，负数上一项
	 * @return true 表示选中项确实变化（供调用方决定是否吞掉滚轮事件）
	 */
	boolean cycle(double delta) {
		int size = entries.size();
		if (delta == 0 || size <= 1) return false;
		int step = delta > 0 ? 1 : -1;
		cursor = ((cursor + step) % size + size) % size;
		return true;
	}
}
