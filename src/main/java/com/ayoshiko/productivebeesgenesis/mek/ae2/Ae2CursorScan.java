package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.util.List;
import java.util.function.Predicate;

/**
	 * AE2 输入拉取游标回绕扫描纯逻辑（无 AE2 / Minecraft 依赖，可直接单元测试）
	 * <br/>
	 * 主扫描跳过游标之前的键；命中游标键时将其自身也加入 —— 修复"只拉取一次"：
	 * 旧实现命中游标键后 continue 跳过它，回绕扫描又在游标键处 break，
	 * 单蜜脾类型网络中 pullList 恒空、游标永不更新，导致后续不再拉取。
	 * 收集不足 maxTypes 时从头回绕补充游标之前的键；游标键已在列表中或不可拉取时
	 * 回绕继续向后扫描（重复键由 {@code out.contains} 去重）。
	 * <p>
	 * 调用方保证 keys 序列在同一快照内稳定。游标键不在 keys 中时回绕收集全部可拉取键。
	 *
	 * @since 2.0.9
	 */
final class Ae2CursorScan {

	private Ae2CursorScan() {
		// 纯静态工具类禁止实例化
	}

	/**
	 * 按游标回绕规则收集扫描候选
	 *
	 * @param out        收集结果容器（可为预置条目的列表，自动去重）
	 * @param keys       候选键序列
	 * @param cursor     上一轮最后拉取的键（null 表示无游标，全量收集）
	 * @param maxTypes   收集上限（不含 out 中已预置的条目）
	 * @param acceptable 键是否可拉取（false 的键跳过，不影响游标定位）
	 */
	static <T> void collect(List<T> out, List<T> keys, T cursor,
			int maxTypes, Predicate<T> acceptable) {
		boolean afterCursor = cursor == null;
		for (T key : keys) {
			if (out.size() >= maxTypes) break;
			if (!acceptable.test(key) || out.contains(key)) continue;
			if (!afterCursor) {
				if (key.equals(cursor)) {
					// 游标键本身也是可拉取类型：命中后置位并放行加入
					afterCursor = true;
				} else {
					continue;
				}
			}
			out.add(key);
		}

		// 游标不存在或已到列表尾部时从开头回绕。只在回绕边界多扫一次。
		if (cursor != null && out.size() < maxTypes) {
			for (T key : keys) {
				if (out.size() >= maxTypes) break;
				if (!acceptable.test(key)) continue;
				if (key.equals(cursor)) break;
				if (!out.contains(key)) out.add(key);
			}
		}
	}
}