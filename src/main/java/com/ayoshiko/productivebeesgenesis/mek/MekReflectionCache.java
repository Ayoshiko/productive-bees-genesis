package com.ayoshiko.productivebeesgenesis.mek;

import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
	 * Mekanism 反射类与 Field 缓存工具类
	 * <br/>
	 * 缓存反射获取的 Class、Field 对象，避免重复 Class.forName() 和 getField() 调用。
	 * 使用 volatile + 双重检查锁保证线程安全，Field 缓存使用 ConcurrentHashMap。
	 * 仅供 {@link MekCompatHooks} 包内使用。
	 */
final class MekReflectionCache {

	/** EM的EMFactoryTier全限定类名（可选依赖，反射访问） */
	private static final String EM_FACTORY_TIER_CLASS = "fr.iglee42.evolvedmekanism.tiers.EMFactoryTier";

	/** ME的ExtraFactoryTier全限定类名（可选依赖，反射访问） */
	private static final String ME_FACTORY_TIER_CLASS = "com.jerry.mekextras.common.tier.ExtraFactoryTier";

	/** EME的EMExtraFactoryTier全限定类名（可选依赖，反射访问） */
	private static final String EME_FACTORY_TIER_CLASS = "io.github.masyumero.emextras.common.tier.EMExtraFactoryTier";

	// ===== 反射缓存 — volatile + 双重检查，避免重复 Class.forName() =====
	/** EM FactoryTier 类缓存 */
	private static volatile Class<?> cachedEMFactoryTierClass;
	/** ME ExtraFactoryTier 类缓存 */
	private static volatile Class<?> cachedMEFactoryTierClass;
	/** EME EMExtraFactoryTier 类缓存 */
	private static volatile Class<?> cachedEMEFactoryTierClass;

	/** 反射 Field 缓存 — 避免重复 getField 调用（key = className#fieldName） */
	private static final Map<String, Field> FIELD_CACHE = new ConcurrentHashMap<>();

	private MekReflectionCache() {
	}

	/**
	 * 获取反射 Field（带缓存，避免重复 getField 调用）
	 * <br/>
	 * Task 23.6：Field 对象缓存到 ConcurrentHashMap，key = className#fieldName。
	 * 首次调用执行 clazz.getField(fieldName)，后续直接从缓存返回。
	 *
	 * @param clazz     目标类
	 * @param fieldName 字段名
	 * @return Field 对象
	 * @throws NoSuchFieldException 字段不存在时抛出
	 */
	static Field getCachedField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
		String key = clazz.getName() + "#" + fieldName;
		Field field = FIELD_CACHE.get(key);
		if (field == null) {
			field = clazz.getField(fieldName);
			FIELD_CACHE.put(key, field);
		}
		return field;
	}

	/** 获取缓存的 EM FactoryTier 类（双重检查 + volatile） */
	@Nullable
	static Class<?> getCachedEMFactoryTierClass() throws ClassNotFoundException {
		Class<?> clazz = cachedEMFactoryTierClass;
		if (clazz == null) {
			synchronized (MekReflectionCache.class) {
				clazz = cachedEMFactoryTierClass;
				if (clazz == null) {
					clazz = Class.forName(EM_FACTORY_TIER_CLASS);
					cachedEMFactoryTierClass = clazz;
				}
			}
		}
		return clazz;
	}

	/** 获取缓存的 ME ExtraFactoryTier 类（双重检查 + volatile） */
	@Nullable
	static Class<?> getCachedMEFactoryTierClass() throws ClassNotFoundException {
		Class<?> clazz = cachedMEFactoryTierClass;
		if (clazz == null) {
			synchronized (MekReflectionCache.class) {
				clazz = cachedMEFactoryTierClass;
				if (clazz == null) {
					clazz = Class.forName(ME_FACTORY_TIER_CLASS);
					cachedMEFactoryTierClass = clazz;
				}
			}
		}
		return clazz;
	}

	/** 获取缓存的 EME EMExtraFactoryTier 类（双重检查 + volatile） */
	@Nullable
	static Class<?> getCachedEMEFactoryTierClass() throws ClassNotFoundException {
		Class<?> clazz = cachedEMEFactoryTierClass;
		if (clazz == null) {
			synchronized (MekReflectionCache.class) {
				clazz = cachedEMEFactoryTierClass;
				if (clazz == null) {
					clazz = Class.forName(EME_FACTORY_TIER_CLASS);
					cachedEMEFactoryTierClass = clazz;
				}
			}
		}
		return clazz;
	}

}
