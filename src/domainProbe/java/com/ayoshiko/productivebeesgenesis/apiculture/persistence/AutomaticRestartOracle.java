package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.CentrifugeJob;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Random;
import java.util.function.BiConsumer;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticRestartFixture.require;

/** 单输入夹具的独立逐次模型；不调用生产采样器、能量报价或结算服务。 */
final class AutomaticRestartOracle {
	static long energyPerTick(CentrifugeJob job) {
		require(job.operations() == 1, "Restart oracle requires one input per job");
		return job.plan().unitEnergyPerTick();
	}
	static void outputs(CentrifugeJob job, BiConsumer<ProductKey, ProductAmount> sink) {
		energyPerTick(job);
		var random = new Random(job.seed());
		for (var output : job.plan().outputs()) {
			long count;
			if (output.key().kind() == ProductKey.Kind.FLUID) count = output.maximum();
			else {
				float chance = (float) Math.min(1.0, Math.max(0.0, output.chance() + (double) job.plan().stability()));
				if (chance < 1 && random.nextFloat() >= chance) continue;
				count = output.minimum();
				if (output.minimum() != output.maximum()) count += random.nextLong((long) output.maximum() - output.minimum() + 1);
			}
			if (count > 0) sink.accept(output.key(), ProductAmount.of(Math.multiplyExact(count, job.plan().productivity())));
		}
	}
	private AutomaticRestartOracle() { }
}
