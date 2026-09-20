package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** 真实配置／配方代际变化必须使预览失效，成品准入不能充当再次离心的资格。 */
final class CentrifugeRequestProbe {
	static void verify(ServerLevel level, NetworkSavedData data, NetworkCentrifugeService service,
			ProductPolicyRegistry policy, List<CentrifugeLaneAllocator.Candidate> offers, JsonObject report) {
		var offer = offers.stream().filter(value -> value.plan().maxParallel() > 1).findFirst().orElseThrow();
		var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(offer), 0, 1, 2).selection();
		var before = data.checkpoint();
		double old = ProductiveBeesConfig.UPGRADES.timeBonus.get();
		try {
			ProductiveBeesConfig.UPGRADES.timeBonus.set(old + 1);
			var changed = service.candidate(level, offer.member(), offer.plan().input());
			require(changed.plan().cycleTicks() != offer.plan().cycleTicks(), "Time config fixture did not change capacity");
			require(!service.assign(level, selection, 1, false) && data.checkpoint() == before, "Stale config preview was assigned");
		} finally { ProductiveBeesConfig.UPGRADES.timeBonus.set(old); }
		long epoch = ProductiveBeesGenesis.RECIPE_VERSION.getAndIncrement();
		try { require(!service.assign(level, selection, 1, false) && data.checkpoint() == before, "Stale recipe generation was assigned"); }
		finally { ProductiveBeesGenesis.RECIPE_VERSION.set(epoch); }
		var product = offer.plan().outputs().stream().map(CentrifugeRecipePlan.Output::key)
				.filter(key -> key.kind() == ProductKey.Kind.ITEM && !key.equals(offer.plan().input())).findFirst().orElseThrow();
		require(policy.evaluate(product).allowed(), "Finished product fixture is not storable");
		boolean rejected = false;
		try { service.candidate(level, offer.member(), product); } catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected && data.checkpoint() == before, "Finished product entered comb processing or changed authority");
		report.addProperty("centrifugeStaleConfigurationRecipeAndFinishedProductRejected", true);
		report.addProperty("centrifugeFinishedProductReentryFixture", product.id().toString());
	}
	private static void require(boolean condition, String reason) { if (!condition) throw new IllegalStateException(reason); }
	private CentrifugeRequestProbe() { }
}
