package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/** 已解析的普通 PB 配方与单机能力；作业开始后不再读取可变配方或升级。 */
public record CentrifugeRecipePlan(String recipe, long recipeRevision, long capabilityRevision,
		ProductKey input, int cycleTicks, int maxParallel, long unitEnergyPerTick,
		int productivity, float stability, List<Output> outputs) {
	public record Output(ProductKey key, int minimum, int maximum, float chance) {
		public Output {
			Objects.requireNonNull(key);
			if (minimum < 0 || maximum < minimum || !Float.isFinite(chance) || chance < 0 || chance > 1)
				throw new IllegalArgumentException("Invalid centrifuge output");
			if (key.kind() == ProductKey.Kind.FLUID && (minimum != maximum || chance != 1))
				throw new IllegalArgumentException("PB fluid output must have a fixed amount");
		}
	}
	public CentrifugeRecipePlan {
		Objects.requireNonNull(input); outputs = List.copyOf(outputs);
		if (recipe == null || recipe.isBlank() || recipeRevision < 0 || capabilityRevision < 0
				|| input.kind() != ProductKey.Kind.ITEM || cycleTicks < 1 || maxParallel < 1 || unitEnergyPerTick < 0
				|| productivity < 1 || !Float.isFinite(stability) || stability < 0 || stability > 1 || outputs.isEmpty())
			throw new IllegalArgumentException("Invalid centrifuge recipe plan");
	}
	public long energyPerTick(int operations) {
		if (operations < 1 || operations > maxParallel) throw new IllegalArgumentException("Invalid pinned parallelism");
		long billable = CentrifugeEnergyPricing.billableOperations(operations);
		// 能量账户有限；饱和报价不能把实际超 long 的工作当作恰好可支付。
		return Math.multiplyExact(unitEnergyPerTick, billable);
	}
	public Map<ProductKey, ProductAmount> sample(int operations, long seed) {
		energyPerTick(operations);
		var random = new Random(seed);
		Map<ProductKey, ProductAmount> amounts = new ConcurrentHashMap<>();
		for (var output : outputs) {
			long base = output.key().kind() == ProductKey.Kind.FLUID ? (long) output.maximum() * operations
					: CentrifugeProductionSampling.sampleOutput(random, operations,
							output.minimum(), output.maximum(), output.chance(), stability);
			var amount = ProductAmount.of(base).multiply(productivity);
			if (!amount.isZero()) amounts.merge(output.key(), amount, ProductAmount::add);
		}
		return Map.copyOf(amounts);
	}
	void validateFrozen(int operations, Map<ProductKey, ProductAmount> amounts) {
		Map<ProductKey, ProductAmount> upper = new ConcurrentHashMap<>(), lower = new ConcurrentHashMap<>();
		for (var output : outputs) {
			upper.merge(output.key(), ProductAmount.of(output.maximum()).multiply(operations).multiply(productivity), ProductAmount::add);
			if (output.key().kind() == ProductKey.Kind.FLUID || output.chance() + (double) stability >= 1)
				lower.merge(output.key(), ProductAmount.of(output.minimum()).multiply(operations).multiply(productivity), ProductAmount::add);
		}
		for (var entry : amounts.entrySet()) {
			if (entry.getValue().isZero() || !upper.containsKey(entry.getKey()) || entry.getValue().compareTo(upper.get(entry.getKey())) > 0)
				throw new IllegalArgumentException("Unexpected or excessive frozen centrifuge output");
		}
		for (var entry : lower.entrySet()) if (amounts.getOrDefault(entry.getKey(), ProductAmount.ZERO).compareTo(entry.getValue()) < 0)
			throw new IllegalArgumentException("Missing guaranteed centrifuge output");
	}
}
