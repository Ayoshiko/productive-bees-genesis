package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompilation;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 静态产品资格按服务器和配方代际共享；动态发现仍属于各网络自己的注册表。 */
final class RuntimeProductPolicies {
	private static final class Cache {
		long epoch = -1;
		Object recipes;
		PbProductPolicyCompilation compilation;
		ProductPolicySnapshot snapshot;
	}
	private static final Map<MinecraftServer, Cache> CACHES = new ConcurrentHashMap<>();
	static ProductPolicySnapshot get(ServerLevel level, long revision) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime policy belongs to the server thread");
		if (revision < 0) throw new IllegalArgumentException("Negative policy revision");
		var cache = CACHES.computeIfAbsent(level.getServer(), ignored -> new Cache()); long epoch = ProductiveBeesGenesis.RECIPE_VERSION.get();
		var recipes = level.getRecipeManager().getRecipes();
		if (cache.epoch != epoch || cache.recipes != recipes) {
			cache.epoch = epoch; cache.recipes = recipes; cache.snapshot = null; cache.compilation = null;
		}
		if (cache.snapshot != null) return cache.snapshot.withRevision(revision);
		if (cache.compilation == null) { cache.compilation = new PbProductPolicyCompilation(level, 0); return null; }
		if (!cache.compilation.step()) return null;
		var result = cache.compilation.result(); cache.snapshot = result.snapshot(); cache.compilation = null;
		if (!result.diagnostics().isEmpty()) ProductiveBeesGenesis.LOGGER.warn("Network product policy skipped invalid recipes: {}", result.diagnostics());
		return cache.snapshot.withRevision(revision);
	}
	static void clear(MinecraftServer server) { CACHES.remove(server); }
	private RuntimeProductPolicies() { }
}
