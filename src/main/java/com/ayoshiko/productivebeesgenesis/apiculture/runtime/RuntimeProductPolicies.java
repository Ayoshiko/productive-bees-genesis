package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompilation;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.production.BeeGeneration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** 静态目录和实际生成的完整键按服务器及配方代际共享；各网络独立保管库存。 */
public final class RuntimeProductPolicies {
	private static final class Cache {
		long epoch = -1;
		Object recipes;
		PbProductPolicyCompilation compilation;
		ProductPolicySnapshot snapshot;
	}
	private static final Map<MinecraftServer, Cache> CACHES = new ConcurrentHashMap<>();
	public static ProductPolicySnapshot get(ServerLevel level, long revision) {
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
	/** 端口查询只读已发布索引；模拟与频繁能力查询不能推进编译或绕过共享调度预算。 */
	public static ProductPolicySnapshot peek(ServerLevel level) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Runtime policy belongs to the server thread");
		var cache = CACHES.get(level.getServer());
		return cache != null && cache.epoch == ProductiveBeesGenesis.RECIPE_VERSION.get()
				&& cache.recipes == level.getRecipeManager().getRecipes() ? cache.snapshot : null;
	}
	public static void clear(MinecraftServer server) { CACHES.remove(server); }
	public static void recordGenerated(ServerLevel level, BeeGeneration generation) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Record generated products on the server thread");
		if (generation.phase() != BeeGeneration.Phase.READY) return;
		long expectedEpoch = generation.source().copy().getLong("epoch");
		var cache = CACHES.get(level.getServer());
		if (cache == null || cache.snapshot == null || cache.epoch != expectedEpoch
				|| ProductiveBeesGenesis.RECIPE_VERSION.get() != expectedEpoch || cache.recipes != level.getRecipeManager().getRecipes()) return;
		cache.snapshot = cache.snapshot.withVerifiedGenerated(generation.pool().stream().map(BeeGeneration.Drop::key).toList());
	}
	private RuntimeProductPolicies() { }
}
