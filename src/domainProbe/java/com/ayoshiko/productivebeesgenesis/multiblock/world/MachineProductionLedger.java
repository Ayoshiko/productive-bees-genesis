package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 独立观察真实 tick；不调用生产执行器，账目跨 JVM 保存。小规模夹具使用精确 long。 */
final class MachineProductionLedger {
	private final Map<ProductKey, Long> expected = new HashMap<>();
	private CombinedMachineWork previous;
	long suppliedEnergy, spentEnergy, beeCycles, jobsStarted, jobsCompleted, observations, imported, exported;

	MachineProductionLedger(CombinedMachineWork initial) { previous = initial; }
	void transfer(ProductKey key, long amount) {
		add(expected, key, amount);
		if (amount > 0) imported = Math.addExact(imported, amount); else exported = Math.subtractExact(exported, amount);
	}
	void supply(long amount) { suppliedEnergy = Math.addExact(suppliedEnergy, amount); }
	void baseline(CombinedMachineWork work) { verify(work); previous = work; }
	void observe(CombinedMachineWork work) {
		long charge = 0;
		for (var bee : work.bees()) {
			var old = previous.bee(bee.slot()); var plan = bee.plan();
			check(bee.id().equals(old.id()) && bee.random().seed() == old.random().seed(), "Bee identity/random seed changed");
			long cycles = bee.random().cursor() - old.random().cursor();
			check(cycles >= 0 && cycles <= 1 && bee.pendingCycles() == 0, "More than one bee cycle in a real tick");
			long ticks = cycles * plan.cycleTicks() + bee.progress() - old.progress();
			check(ticks >= 0 && ticks <= 1, "Bee advanced more than one tick");
			if (old.progress() > 0) check(plan.equals(old.plan()), "In-progress bee plan changed");
			charge = Math.addExact(charge, ticks * plan.energyPerTick());
			check(plan.count() == 1 && plan.productivity() == 0, "Independent bee sample assumes normal one-item recipe");
			if (cycles != 0) {
				var random = new SplittableRandom(bee.random().seed());
				for (long cursor = 0; cursor < old.random().cursor(); cursor++) random.nextDouble();
				long rolls = (long) Math.floor(plan.productionMultiplier());
				if (random.nextDouble() < plan.productionMultiplier() - rolls) rolls++;
				add(expected, plan.output(), rolls); beeCycles++;
			}
		}
		for (int lane = 0; lane < work.lanes(); lane++) {
			var before = previous.centrifuges().get(lane); var after = work.centrifuges().get(lane);
			if (before == null) {
				if (after == null) continue;
				var job = after.job();
				check(job.plan().cycleTicks() > 1 && job.progress() == 1 && !job.sampled(), "Unobservable same-tick new job");
				add(expected, job.plan().input(), -job.operations()); jobsStarted++;
				charge = Math.addExact(charge, job.plan().energyPerTick(job.operations()));
			} else {
				var job = before.job();
				if (after != null) {
					check(after.job().id().equals(job.id()) && after.job().plan().equals(job.plan())
							&& after.job().seed() == job.seed() && after.job().operations() == job.operations(), "Pinned job changed");
					if (job.sampled()) check(job.frozen().equals(after.job().frozen()), "Paid result rerolled");
				}
				int progress = after == null ? job.plan().cycleTicks() : after.job().progress();
				check(progress >= job.progress() && progress - job.progress() <= 1, "Centrifuge advanced more than one tick");
				charge = Math.addExact(charge, (progress - job.progress()) * job.plan().energyPerTick(job.operations()));
				if (!job.paid() && progress == job.plan().cycleTicks()) {
					// 概率采样复用已验收内核；投入、付款、在制与分次交付由本账目独立核算。
					var sample = job.plan().sample(job.operations(), job.seed());
					sample.forEach((key, amount) -> add(expected, key, amount.exact().longValueExact()));
					if (after != null) check(sample.equals(after.job().frozen()), "Frozen sample differs from pinned seed");
					jobsCompleted++;
				}
			}
		}
		check(previous.energy() - work.energy() == charge, "Tick FE differs from independent work charge");
		spentEnergy = Math.addExact(spentEnergy, charge); observations++; baseline(work);
	}
	private void verify(CombinedMachineWork work) {
		var actual = new HashMap<ProductKey, Long>();
		for (var cell : work.buffer().items()) if (cell.key() != null) add(actual, cell.key(), cell.count());
		for (var cell : work.buffer().fluids()) if (cell.key() != null) add(actual, cell.key(), cell.count());
		for (var bee : work.bees()) add(actual, bee.plan().output(), bee.frozen().exact().longValueExact());
		for (var delivery : work.centrifuges().values()) if (delivery.job().sampled())
			for (var key : delivery.job().frozen().keySet()) add(actual, key, delivery.remaining(key).exact().longValueExact());
		check(actual.equals(expected), "Product conservation failed: expected=" + expected + " actual=" + actual);
		check(work.energy() == suppliedEnergy - spentEnergy, "Total FE conservation failed");
	}
	private static void add(Map<ProductKey, Long> values, ProductKey key, long amount) {
		long next = Math.addExact(values.getOrDefault(key, 0L), amount);
		check(next >= 0, "Unfunded product subtraction");
		if (next == 0) values.remove(key); else values.put(key, next);
	}
	CompoundTag save() {
		var tag = new CompoundTag(); var list = new ListTag();
		expected.forEach((key, amount) -> {
			var entry = new CompoundTag(); entry.putString("kind", key.kind().name()); entry.putString("id", key.id().toString());
			entry.put("components", key.components()); entry.putLong("amount", amount); list.add(entry);
		});
		tag.put("balance", list); tag.putLong("supplied", suppliedEnergy); tag.putLong("spent", spentEnergy);
		tag.putLong("cycles", beeCycles); tag.putLong("started", jobsStarted); tag.putLong("completed", jobsCompleted);
		tag.putLong("observations", observations); tag.putLong("imported", imported); tag.putLong("exported", exported); return tag;
	}
	static MachineProductionLedger restore(CompoundTag tag, CombinedMachineWork work) {
		var result = new MachineProductionLedger(work);
		for (var value : tag.getList("balance", Tag.TAG_COMPOUND)) {
			var entry = (CompoundTag) value;
			result.expected.put(new ProductKey(ProductKey.Kind.valueOf(entry.getString("kind")), ResourceLocation.parse(entry.getString("id")),
					entry.getCompound("components")), entry.getLong("amount"));
		}
		result.suppliedEnergy = tag.getLong("supplied"); result.spentEnergy = tag.getLong("spent"); result.beeCycles = tag.getLong("cycles");
		result.jobsStarted = tag.getLong("started"); result.jobsCompleted = tag.getLong("completed"); result.observations = tag.getLong("observations");
		result.imported = tag.getLong("imported"); result.exported = tag.getLong("exported"); result.baseline(work); return result;
	}
}
