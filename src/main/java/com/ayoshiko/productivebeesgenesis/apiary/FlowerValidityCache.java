package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 花朵有效性缓存（按蜜蜂类型键）
 * <br/>
 * 从 {@link FeederSlotManager} 拆分而来，职责（SRP）：缓存蜜蜂类型→花朵匹配结果
 * 并维护失效版本号；喂食槽内容变化频率远低于 tick 频率，缓存可显著降低
 * 256× 加速场景下每 tick 每只蜜蜂遍历喂食槽的开销。
 * <p>
 * 使用普通 HashMap（非 LinkedHashMap access-order）以避免每次 get 触发
 * afterNodeAccess 重新链接节点的开销（Spark 显示此处为 HashMap.get 热点）。
 * 容量增长时由调用方在喂食槽变化时主动 {@link #invalidate()}。
 * <p>
 * 线程安全：服务端单线程访问，无需同步。
 */
final class FlowerValidityCache {

	/** 初始容量 — 与蜜蜂类型缓存规模匹配 */
	private static final int INITIAL_CAPACITY = 64;

	private final Map<ResourceLocation, Boolean> cache = new HashMap<>(INITIAL_CAPACITY);

	/** 上次失效缓存的版本号（监听器递增触发失效） */
	private int version = 0;

	/** 查询缓存值；未命中返回 null（调用方需重新计算） */
	Boolean get(ResourceLocation beeTypeKey) {
		return cache.get(beeTypeKey);
	}

	/** 写入缓存值 */
	void put(ResourceLocation beeTypeKey, boolean valid) {
		cache.put(beeTypeKey, valid);
	}

	/** 失效全部缓存并递增版本号 */
	void invalidate() {
		cache.clear();
		version++;
	}

	/** 当前缓存版本号（供外部缓存层判断失效） */
	int version() {
		return version;
	}
}
