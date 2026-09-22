package com.ayoshiko.productivebeesgenesis.apiculture.runtime;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.server.level.ServerLevel;

/** 复用正式缓存，核对一请求一步、跨网络版本共享、重载中断及停服清理。 */
public final class RuntimePolicyProbe {
	public static void verify(ServerLevel level, int steps) {
		RuntimeProductPolicies.clear(level.getServer());
		for (int i = 0; i < steps; i++) require(RuntimeProductPolicies.get(level, i % 17) == null, "Runtime bypassed compilation budget");
		var result = RuntimeProductPolicies.get(level, 17); require(result != null && result.revision() == 17, "Revisions did not share compilation");
		for (int i = 0; i < 32; i++) require(RuntimeProductPolicies.get(level, i).descriptorCount() == result.descriptorCount(), "Policy revision recompiled catalog");
		ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
		for (int i = 0; i < 37; i++) require(RuntimeProductPolicies.get(level, 1) == null, "Old catalog survived reload");
		ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
		for (int i = 0; i < steps; i++) require(RuntimeProductPolicies.get(level, 1) == null, "Partial old generation survived reload");
		require(RuntimeProductPolicies.get(level, 1) != null, "Reloaded compilation did not finish");
		RuntimeProductPolicies.clear(level.getServer());
		require(RuntimeProductPolicies.get(level, 1) == null, "Cleared session retained policy");
		RuntimeProductPolicies.clear(level.getServer());
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private RuntimePolicyProbe() { }
}
