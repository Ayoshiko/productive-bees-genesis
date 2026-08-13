package com.ayoshiko.productivebeesgenesis.apiary.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

import java.util.concurrent.ConcurrentHashMap;

/**
	 * 蜜蜂实体缓存层（单例）
	 * <br/>
	 * 缓存蜜蜂类型到渲染实体的映射，避免每帧为每个蜜蜂槽重复创建实体。
	 * 相同蜜蜂类型（相同的 EntityType + ConfigurableBee type 字段）共享同一实体实例。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅管理实体缓存的生命周期，不涉及实体创建（委托 {@link BeeEntityFactory}）</li>
	 *   <li>线程安全：使用 {@link ConcurrentHashMap}，适配客户端渲染线程与可能的异步加载场景</li>
	 * </ul>
	 * <p>
	 * 线程安全契约：{@link #getOrCreate} 中的世界切换检测与容量检测为 check-then-clear-then-set
	 * 非原子序列，理论上在并发访问下存在竞态。但本类仅由客户端渲染线程调用（单线程），
	 * 实际无并发风险，故未加锁。若未来改为多线程访问，需引入同步保护。
	 * <p>
	 * 缓存策略：
	 * <ul>
	 *   <li>容量上限 {@link #MAX_CACHE_SIZE}，超过时清空全部缓存（防止内存泄漏）</li>
	 *   <li>世界切换时自动清空（实体绑定 Level，跨世界复用会导致渲染异常）</li>
	 *   <li>数据重载时由 {@link com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper#invalidateCache()} 调用
	 * {@link #clearCache()} 清空</li>
	 * </ul>
	 */
public final class BeeEntityCache {

	/** 单例实例 — 全局共享，便于外部清理 */
	private static final BeeEntityCache INSTANCE = new BeeEntityCache();

	/** 缓存容量上限，超过时清空全部缓存 */
	private static final int MAX_CACHE_SIZE = 256;

	/** 蜜蜂类型 → 渲染实体 的并发缓存映射 */
	private final ConcurrentHashMap<String, Entity> cache = new ConcurrentHashMap<>();

	/** 当前缓存对应的 Level，世界切换时清空缓存 */
	private volatile Level currentLevel;

	/** 获取单例实例 */
	public static BeeEntityCache getInstance() {
		return INSTANCE;
	}

	/** 静态清理方法 — 供 {@link com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper#invalidateCache()} 调用 */
	public static void clearCache() {
		INSTANCE.cache.clear();
		INSTANCE.currentLevel = null;
	}

	private BeeEntityCache() {
		// 单例模式，禁止外部实例化
	}

	/**
	 * 获取或创建渲染用蜜蜂实体
	 * <br/>
	 * 优先从缓存命中，未命中时委托 {@link BeeEntityFactory} 创建并写入缓存。
	 * 世界切换时自动清空缓存（实体绑定 Level，跨世界复用会导致渲染异常）。
	 * 缓存容量超过上限时清空全部，防止内存泄漏。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @param level   当前世界实例
	 * @return 渲染用实体实例，beeData 为空或创建失败时返回 null
	 */
	public Entity getOrCreate(CompoundTag beeData, Level level) {
		if (beeData == null || level == null) return null;

		// 世界切换时清空缓存（实体绑定 Level，跨世界复用会导致渲染异常）
		if (currentLevel != level) {
			cache.clear();
			currentLevel = level;
		}

		String key = buildCacheKey(beeData);
		if (key.isEmpty()) return null;

		// 容量超限时清空全部缓存，防止内存泄漏
		if (cache.size() >= MAX_CACHE_SIZE) {
			cache.clear();
		}

		// computeIfAbsent 保证同 key 仅创建一次实体
		return cache.computeIfAbsent(key, k -> BeeEntityFactory.createBeeEntity(beeData, level));
	}

	/**
	 * 构建缓存键
	 * <br/>
	 * 字段优先级与 {@link com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper#resolveBeeTypeKey} 保持一致：
	 * <ol>
	 *   <li>"type" — ConfigurableBee 的具体类型（如 productivebees:iron_bee）</li>
	 *   <li>"entity" — PB 蜂笼格式的实体类型注册名（如 minecraft:bee）</li>
	 *   <li>"id" — Occupant 格式的实体类型注册名</li>
	 * </ol>
	 * 覆盖 PB 蜂笼装入的原版蜜蜂（仅有 "entity" 字段，无 "type"/"id"）场景。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return 缓存键字符串（非空），所有字段缺失时返回空串
	 */
	private String buildCacheKey(CompoundTag beeData) {
		if (beeData.contains("type")) {
			String type = beeData.getString("type");
			if (!type.isEmpty()) return type;
		}
		if (beeData.contains("entity")) {
			String entity = beeData.getString("entity");
			if (!entity.isEmpty()) return entity;
		}
		return beeData.getString("id");
	}
}
