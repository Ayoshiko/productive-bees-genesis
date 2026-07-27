package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.common.block.attribute.Attribute;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

/**
 * Mekanism 反射类/方法/Field 缓存工具类
 * <br/>
 * 缓存反射获取的 Class、Method、Field 对象，避免重复 Class.forName() 和 getField() 调用。
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

	/** EME 的 EMExtraAttributeFactoryType 全限定类名（可选依赖，反射访问，避免编译期硬依赖） */
	private static final String EME_ATTRIBUTE_FACTORY_TYPE_CLASS =
			"io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeFactoryType";

	// ===== 反射缓存 — volatile + 双重检查，避免重复 Class.forName() =====
	/** EM FactoryTier 类缓存 */
	private static volatile Class<?> cachedEMFactoryTierClass;
	/** ME ExtraFactoryTier 类缓存 */
	private static volatile Class<?> cachedMEFactoryTierClass;
	/** EME EMExtraFactoryTier 类缓存 */
	private static volatile Class<?> cachedEMEFactoryTierClass;
	/** EME EMExtraAttributeFactoryType 类缓存（配置卡兼容性检查用） */
	private static volatile Class<?> cachedEMEAttributeFactoryTypeClass;
	/** Attribute.get(Object, Class) 静态方法缓存（反射调用 EME Attribute 检查） */
	private static volatile Method cachedAttributeGetMethod;
	/** EMExtraAttributeFactoryType.getFactoryType() 方法缓存 */
	private static volatile Method cachedEMEGetFactoryTypeMethod;

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

	/** 获取缓存的 EME EMExtraAttributeFactoryType 类（双重检查 + volatile，ClassNotFoundException 时返回 null） */
	@Nullable
	static Class<?> getCachedEMEAttributeFactoryTypeClass() {
		Class<?> clazz = cachedEMEAttributeFactoryTypeClass;
		if (clazz == null) {
			synchronized (MekReflectionCache.class) {
				clazz = cachedEMEAttributeFactoryTypeClass;
				if (clazz == null) {
					try {
						clazz = Class.forName(EME_ATTRIBUTE_FACTORY_TYPE_CLASS);
						cachedEMEAttributeFactoryTypeClass = clazz;
					} catch (ClassNotFoundException e) {
						// EME 类未加载，返回 null，调用方安全降级
						return null;
					}
				}
			}
		}
		return clazz;
	}

	/** 获取缓存的 Attribute.get(Object, Class) 静态方法（双重检查 + volatile） */
	@Nullable
	static Method getCachedAttributeGetMethod() {
		Method method = cachedAttributeGetMethod;
		if (method == null) {
			synchronized (MekReflectionCache.class) {
				method = cachedAttributeGetMethod;
				if (method == null) {
					try {
						method = Attribute.class.getMethod("get", Object.class, Class.class);
						cachedAttributeGetMethod = method;
					} catch (NoSuchMethodException e) {
						// Mekanism API 变更导致方法签名变化，返回 null
						return null;
					}
				}
			}
		}
		return method;
	}

	/** 获取缓存的 EMExtraAttributeFactoryType.getFactoryType() 方法（双重检查 + volatile） */
	@Nullable
	static Method getCachedEMEGetFactoryTypeMethod(Class<?> emeAttrClass) {
		Method method = cachedEMEGetFactoryTypeMethod;
		if (method != null && method.getDeclaringClass() == emeAttrClass) {
			return method;
		}
		synchronized (MekReflectionCache.class) {
			method = cachedEMEGetFactoryTypeMethod;
			if (method != null && method.getDeclaringClass() == emeAttrClass) {
				return method;
			}
			try {
				method = emeAttrClass.getMethod("getFactoryType");
				cachedEMEGetFactoryTypeMethod = method;
			} catch (NoSuchMethodException e) {
				// EME API 变更导致方法缺失，返回 null
				return null;
			}
		}
		return method;
	}
}
