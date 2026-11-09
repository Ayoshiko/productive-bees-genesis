package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.util.RandomSource;

import java.util.LinkedHashMap;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** 单周期资源份数计划；预约上界不抽样，冻结后重试不得重新开奖。 */
public final class ResourceRollPlan {
	/** 混合物品与资源使用同一批升级轮数；份数仅用于物品采样，不重复应用基因。 */
	public record Sample(Map<ResourceKey, BigInteger> exactAmounts, long rolls) {
        public Map<ResourceKey, Long> amounts() { return ResourceExactAmounts.longExact(exactAmounts); }
    }
	private final List<ResourceProductionRecipe.Output> outputs;
	private final double rolls;
	private final long geneMultiplier;
	private final long seed;
	private final Map<ResourceKey, BigInteger> maximum;
	private final boolean deterministic;

	/** 捕获实际周期的升级、基因和独立随机种子。 */
	public ResourceRollPlan(List<ResourceProductionRecipe.Output> outputs, double rolls, int gene, long seed) {
		this.outputs = List.copyOf(outputs);
		if (!Double.isFinite(rolls) || rolls < 1 || rolls > 1_000_000 || gene < 0 || gene > 3) {
			throw new IllegalArgumentException("Unsupported resource production multiplier");
		}
		this.rolls = rolls;
		this.geneMultiplier = gene + 1L;
		this.seed = seed;
		boolean exact = rolls == Math.floor(rolls);
		var upper = new LinkedHashMap<ResourceKey, BigInteger>();
		for (var output : outputs) {
			exact &= output.chance() == 0 || output.chance() == 1;
			if (output.chance() <= 0) continue;
			BigInteger amount = output.exactAmount().multiply(BigInteger.valueOf((long) Math.ceil(rolls))).multiply(BigInteger.valueOf(geneMultiplier));
			upper.merge(output.key(), amount, BigInteger::add);
		}
		this.maximum = ResourceExactAmounts.copy(upper);
		this.deterministic = exact;
	}

	/** 整份最大占用，用于原子预约和只读容量检查。 */
	public Map<ResourceKey, Long> maximum() { return ResourceExactAmounts.longExact(maximum); }
    public Map<ResourceKey, BigInteger> maximumExact() { return maximum; }

	/** 多个独立周期的整份上界；STACK 计入周期数，不再另乘产物。 */
	public Map<ResourceKey, Long> maximum(long cycles) { return ResourceExactAmounts.longExact(maximumExact(cycles)); }
    public Map<ResourceKey, BigInteger> maximumExact(long cycles) {
		if (cycles < 0) throw new IllegalArgumentException("Negative resource cycle count");
		if (cycles == 0) return Map.of();
		if (cycles == 1) return maximum;
		var result = new LinkedHashMap<ResourceKey, BigInteger>();
		maximum.forEach((key, amount) -> result.put(key, amount.multiply(BigInteger.valueOf(cycles))));
		return ResourceExactAmounts.copy(result);
	}

	/** 对固定种子采样；所有输出共享轮数，每轮概率独立，不把原始单位作为物品栈计算。 */
	public Map<ResourceKey, Long> sample() { return sample(1); }

	/** 批量仍按独立周期抽取随机尾数；确定性输出可以精确聚合，不逐单位循环。 */
	public Map<ResourceKey, Long> sample(long cycles) {
		return sampleWithRolls(cycles).amounts();
	}

	public Sample sampleWithRolls(long cycles) { return sampleWithRolls(cycles, seed); }

	public Sample sampleWithRolls(long cycles, long seed) {
		if (cycles < 0) throw new IllegalArgumentException("Negative resource cycle count");
		if (cycles == 0) return new Sample(Map.of(), 0);
		long whole = (long) Math.floor(rolls);
		if (deterministic) return new Sample(maximumExact(cycles), Math.multiplyExact(whole, cycles));
		// 抽样前仍验证整份上界溢出，但不创建随后丢弃的余额表。
		for (var entry : maximum.entrySet()) ResourceExactAmounts.validate(entry.getKey(), entry.getValue().multiply(BigInteger.valueOf(cycles)));
		long totalRolls = 0;
		var successes = new long[outputs.size()];
		if (cycles > 32 || whole > 1024) {
            var random = RandomSource.create(seed);
            totalRolls = Math.addExact(Math.multiplyExact(whole, cycles),
                    com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler.sample(random, cycles, rolls - whole));
            for (int i = 0; i < outputs.size(); i++)
                successes[i] = com.ayoshiko.productivebeesgenesis.util.ExactBinomialSampler.sample(random, totalRolls, outputs.get(i).chance());
		} else {
			var random = RandomSource.create(seed);
			for (long cycle = 0; cycle < cycles; cycle++) {
				long count = whole;
				if (random.nextDouble() < rolls - whole) count++;
				totalRolls = Math.addExact(totalRolls, count);
				for (int i = 0; i < outputs.size(); i++) {
					var output = outputs.get(i);
					long hits = output.chance() >= 1 ? count : 0;
					if (output.chance() > 0 && output.chance() < 1) {
						for (long roll = 0; roll < count; roll++) if (random.nextDouble() < output.chance()) hits++;
					}
					successes[i] = Math.addExact(successes[i], hits);
				}
			}
		}
		var result = new LinkedHashMap<ResourceKey, BigInteger>();
		for (int i = 0; i < outputs.size(); i++) {
			if (successes[i] == 0) continue;
			var output = outputs.get(i);
			BigInteger amount = output.exactAmount().multiply(BigInteger.valueOf(successes[i])).multiply(BigInteger.valueOf(geneMultiplier));
			result.merge(output.key(), amount, BigInteger::add);
		}
		return new Sample(ResourceExactAmounts.copy(result), totalRolls);
	}
}
