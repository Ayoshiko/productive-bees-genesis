package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 静态产品资格按服务器和配方代际共享；动态发现仍属于各网络自己的注册表。 */
final class RuntimeProductPolicies {
	private static final class Cache {
		long epoch = -1;
		final Map<Long, ProductPolicySnapshot> revisions = new ConcurrentHashMap<>();
	}
	private static final Map<MinecraftServer, Cache> CACHES = new ConcurrentHashMap<>();
	static ProductPolicySnapshot get(ServerLevel level, long revision) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime policy belongs to the server thread");
		var cache = CACHES.computeIfAbsent(level.getServer(), ignored -> new Cache()); long epoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (cache.epoch != epoch) { cache.epoch = epoch; cache.revisions.clear(); }
		var result = cache.revisions.get(revision); if (result != null) return result;
		if (cache.revisions.size() >= 8) cache.revisions.clear();
		result = PbProductPolicyCompiler.compile(level, revision).snapshot(); cache.revisions.put(revision, result); return result;
	}
	static void clear(MinecraftServer server) { CACHES.remove(server); }
	private RuntimeProductPolicies() { }
}
