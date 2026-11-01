package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;

/**
 * 客户端预览实体的有界缓存。单个正式界面最多显示 60 只蜂，
 * 256 项容量用于跨界面复用；逐项淘汰最久未访问项，不在边界清掉当前可见蜂种。
 * <p>实体只由渲染线程创建；集成服务器的数据重载也会清理缓存，
 * 因此查找、创建、换 Level 与清理使用同一个锁，避免清理后重新发布旧实体。
 * 本类不引用客户端专用类型，保留服务端失效入口的类加载安全。
 */
public final class BeeEntityCache {
	private static final BeeEntityCache INSTANCE = new BeeEntityCache();
	private static final int MAX_CACHE_SIZE = 256;
	private final LinkedHashMap<RenderKey, Entity> cache = new LinkedHashMap<>(64, 0.75F, true);
	private Level currentLevel;
	private volatile long generation;

	public static BeeEntityCache getInstance() {
		return INSTANCE;
	}

	public static void clearCache() {
		synchronized (INSTANCE) {
			INSTANCE.cache.clear();
			INSTANCE.currentLevel = null;
			INSTANCE.generation++;
		}
	}

	/** 仅用于客户端渲染资源失效；读取不持有 Level 或实体。 */
	static long generation() {
		return INSTANCE.generation;
	}

	private BeeEntityCache() {}

	public synchronized Entity getOrCreate(CompoundTag beeData, Level level) {
		if (beeData == null || level == null) return null;
		if (currentLevel != level) {
			cache.clear();
			currentLevel = level;
			generation++;
		}
		EntityType<?> entityType = BeeNbtHelper.resolveEntityType(beeData);
		if (entityType == null) entityType = EntityType.BEE;
		// 工厂按实际 EntityType 创建实体，再应用 type；两者都属于渲染身份。
		RenderKey key = new RenderKey(entityType, beeData.getString("type"));
		Entity cached = cache.get(key);
		if (cached != null) return cached;
		Entity created = BeeEntityFactory.createBeeEntity(beeData, level);
		if (created != null) {
			if (cache.size() >= MAX_CACHE_SIZE) com.ayoshiko.productivebeesgenesis.port.ForgeCollections.pollFirst(cache);
			cache.put(key, created);
		}
		return created;
	}

	private record RenderKey(EntityType<?> entityType, String beeType) {}
}
