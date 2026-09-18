package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.CapacityPoolIndex;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.MachineCapacityReader;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.util.CentrifugeRecipeIndex;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实 API 的领域契约联调；预制结果用于账本断言，不采样或执行真实机器生产。 */
final class P1FlowProbe {
	static void verify(ServerLevel level, ProductPolicySnapshot policy, JsonObject report) {
		level.setChunkForced(0, 0, true);
		var pos = new BlockPos(0, 80, 0);
		var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
		level.setBlockAndUpdate(pos, block.defaultBlockState());
		var tile = level.getBlockEntity(pos);
		require(tile instanceof PbRecipeContext, "Missing actual centrifuge");
		var recipe = CentrifugeRecipeIndex.get(ResourceLocation.parse("productivebees:iron"));
		require(recipe != null, "Missing iron recipe");
		var before = tile.saveWithFullMetadata(level.registryAccess()).copy();
		var member = MachineCapacityReader.centrifuge((PbRecipeContext) tile, UUID.randomUUID(), 1, 1, List.of(recipe));
		var capacity = new CapacityPoolIndex(List.of(member));
		var stack = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		stack.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse("productivebees:iron"));
		var input = ProductKeyCodec.item(stack, level.registryAccess());
		Map<ProductKey, ProductAmount> outputs = new ConcurrentHashMap<>();
		for (var product : recipe.value().getRecipeOutputs().keySet()) outputs.put(ProductKeyCodec.item(product, level.registryAccess()), ProductAmount.of(8));
		var fluid = recipe.value().getFluidOutputs();
		if (!fluid.isEmpty()) outputs.put(ProductKeyCodec.fluid(fluid, level.registryAccess()), ProductAmount.of(fluid.getAmount()).multiply(8));
		var ledger = new ProductLedger(new ProductPolicyRegistry(policy), 16);
		require(ledger.insert(input, ProductAmount.of(1000), ProductLedger.Action.EXECUTE).equals(ProductAmount.of(1000)), "Input admission failed");
		var reserves = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING,
				new ReservePolicy.Layer(ReserveLimit.floor(ProductAmount.of(200)), Map.of()), ReservePolicy.Layer.NONE);
		var matcher = new ProductMatcher(ProductMatcher.Mode.EXACT, input);
		var rule = new ProcessingRule("iron", 1, true, 0, 1, 8, new ProcessingRule.Match(matcher), reserves);
		var indexed = new ProcessingRecipe(member.alternatives().getFirst().work(), matcher, ProductAmount.of(1), outputs.keySet());
		var scheduler = new ProcessingRuleScheduler(ledger, new ProcessingRuleIndex(List.of(rule), List.of(indexed), Map.of()), ProcessingRuleScheduler.Mode.FAIR);
		var selected = scheduler.select(policy.revision(), capacity, Map.of());
		require(selected != null && selected.operations() == 8, "Actual work identity did not match recipe index");
		var transaction = scheduler.claim(selected, capacity, outputs);
		require(transaction != null && ledger.commit(transaction), "Domain commit failed");
		var snapshot = ledger.snapshot();
		require(ledger.commit(transaction) && snapshot.equals(ledger.snapshot()), "Duplicate commit changed balances");
		require(ledger.available(input).equals(ProductAmount.of(992)), "Input debit differs from selected work");
		outputs.forEach((key, amount) -> require(ledger.available(key).equals(amount), "Prepared output changed"));
		require(before.equals(tile.saveWithFullMetadata(level.registryAccess())), "Domain flow mutated physical machine inventory");
		report.addProperty("p1DomainFlow", true);
		report.addProperty("physicalMachineNbtUnchanged", true);
		report.addProperty("realProductionExecuted", false);
	}
}
