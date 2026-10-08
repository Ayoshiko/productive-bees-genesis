package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器线程共享的 AEItemKey → SNBT 缓存，避免多台机器为相同产物重复编码。
 * 键保留完整组件；注册表身份、数据重载代际变化或停服时清空。
 * 条目数及已编码字符数双重有界，不限制实际传输或持久化的物品数量。
 */
final class Ae2FingerprintCache {
	static final int MAX_ENTRIES = 4096;
	static final int MAX_CACHED_CHARACTERS = 2 * 1024 * 1024;

	private static final Map<AEItemKey, String> cache = new LinkedHashMap<>(128, 0.75f, true);
	private static WeakReference<HolderLookup.Provider> registries = new WeakReference<>(null);
	private static long recipeVersion = Long.MIN_VALUE;
	private static int cachedCharacters;

	/** 调用方在服务器线程内使用；GUI 编码继续走 Ae2ItemFingerprint 原路径。 */
	String get(AEItemKey key, HolderLookup.Provider provider) {
		if (key == null || provider == null) return "";
		long version = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (registries.get() != provider || recipeVersion != version) {
			clearShared();
			registries = new WeakReference<>(provider);
			recipeVersion = version;
		}
		String cached = cache.get(key);
		if (cached != null) return cached;
		String encoded = Ae2ItemFingerprint.encodeOrLegacy(key, provider);
		// 超大指纹仍完整交给调用方；临时编码失败的 legacy 结果不污染共享缓存。
		if (!encoded.startsWith("{") || encoded.length() > MAX_CACHED_CHARACTERS) return encoded;
		String replaced = cache.put(key, encoded);
		cachedCharacters += encoded.length() - (replaced == null ? 0 : replaced.length());
		while (cache.size() > MAX_ENTRIES || cachedCharacters > MAX_CACHED_CHARACTERS) {
			var iterator = cache.entrySet().iterator();
			var eldest = iterator.next();
			cachedCharacters -= eldest.getValue().length();
			iterator.remove();
		}
		return encoded;
	}

	static void clearShared() {
		cache.clear();
		cachedCharacters = 0;
		registries.clear();
		recipeVersion = Long.MIN_VALUE;
	}
}
