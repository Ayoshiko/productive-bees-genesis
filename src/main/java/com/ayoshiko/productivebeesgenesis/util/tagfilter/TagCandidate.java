package com.ayoshiko.productivebeesgenesis.util.tagfilter;

import java.util.Collection;
import java.util.Locale;

/**
 * 表达式求值所需的候选物品视图（ISP：只暴露匹配一个模式所需的最小能力）。
 * <p>
 * 之所以抽象成接口而不是直接传 {@code ItemStack}/{@code AEItemKey}：
 * 求值逻辑因此不依赖 Minecraft 与 AE2，可纯 JVM 单测；
 * 真实运行时由 AE2 侧适配器提供物品 id 与标签 id 集合。
 */
public interface TagCandidate {

	/** 候选是否匹配给定模式（标签 id 或物品 id 任一命中即算命中）。 */
	boolean matches(TagPattern pattern);

	/**
	 * 基于「物品 id + 标签 id 集合」的默认实现，供测试与简单场景使用。
	 *
	 * @param itemId 物品注册名，如 {@code minecraft:honeycomb_block}
	 * @param tagIds 该物品持有的全部标签 id，如 {@code c:storage_blocks/honeycombs}
	 */
	static TagCandidate of(String itemId, Collection<String> tagIds) {
		String normalizedItem = itemId == null ? null : itemId.toLowerCase(Locale.ROOT);
		return pattern -> {
			if (pattern == null) return false;
			if (normalizedItem != null && pattern.matches(normalizedItem)) return true;
			if (tagIds == null) return false;
			for (String tagId : tagIds) {
				if (tagId != null && pattern.matches(tagId)) return true;
			}
			return false;
		};
	}
}
