package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 捕获全域而非仅余额；防御构造是对照路径，不冒充旧版完整网络或游戏性能。 */
public final class CheckpointCaptureBenchmark {
	private static final com.sun.management.ThreadMXBean ALLOCATIONS =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final ProductAmount LARGE = ProductAmount.of(BigInteger.ONE.shiftLeft(256));
	static record Fixture(ProductKey[] keys, ProductPolicyRegistry policy, ProductLedger ledger, TransferStaging staging,
			ProcessingRuleScheduler scheduler, LedgerTransaction[] transactions, UUID[] transfers,
			MemberCapabilitySnapshot[] members, WorkCapacity work, NetworkCheckpointSource source) { }
	private record Measurement(NetworkCheckpoint snapshot, JsonObject metrics) { }
	private CheckpointCaptureBenchmark() { }
	public static void main(String[] args) throws Exception {
		Path target = Path.of(args[0]);
		if (Files.exists(target)) throw new IllegalStateException("Preserve the previous benchmark report");
		boolean reverse = args.length > 1 && Integer.parseInt(args[1]) % 2 != 0;
		var report = new JsonObject(); var cases = new JsonArray();
		for (int count : new int[] {10_000, 1_000_000}) {
			cases.add(measure(count, count / 10, reverse)); System.gc();
		}
		report.add("cases", cases); report.addProperty("passed", true); report.addProperty("java", System.getProperty("java.version"));
		report.addProperty("scope", "In-memory full-domain capture and mutations; no encoding, compression, disk, loading or MSPT measurement");
		Files.createDirectories(target.getParent()); Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(report));
		System.out.println(report);
	}
	static Fixture fixture(int keyCount, int count) {
		var keys = new ProductKey[keyCount];
		for (int i = 0; i < keyCount; i++) {
			var components = new CompoundTag(); components.putInt("test:variant", i);
			keys[i] = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), components);
		}
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(3, List.of(), List.of(
				new DynamicProductRule("test:allow", keys[0].kind(), keys[0].id(), false, key -> true),
				new DynamicProductRule("test:discover", keys[0].kind(), keys[0].id(), true, key -> true))));
		var ledger = new ProductLedger(policy, count + 1);
		for (int i = 0; i < keyCount; i++) ledger.insert(keys[i], initial(i), ProductLedger.Action.EXECUTE);
		var transactions = new LedgerTransaction[count]; var transfers = new UUID[count]; var views = new ArrayList<TransferStaging.View>(count);
		var rules = new ArrayList<ProcessingRule>(count); Map<String, Boolean> watermarks = new ConcurrentHashMap<>();
		var reserve = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, ReservePolicy.Layer.NONE, ReservePolicy.Layer.NONE);
		for (int i = 0; i < count; i++) {
			transactions[i] = ledger.prepare(Map.of(keys[i], ProductAmount.of(1)), Map.of(keys[keyCount - 1], ProductAmount.of(1)), 3);
			if (i % 2 == 0) ledger.markPaid(transactions[i]);
			ledger.extract(keys[i], ProductAmount.of(1), ProductLedger.Action.EXECUTE);
			transfers[i] = UUID.randomUUID();
			views.add(new TransferStaging.View(transfers[i], "test:endpoint", keys[i], TransferStaging.Direction.EXPORT, 1,
					ProductAmount.of(1), TransferStaging.Phase.UNKNOWN, "pending original receipt"));
			policy.recordVerifiedProduction("test:discover", keys[i], 3);
			var rule = new ProcessingRule("goal-" + i, 1, true, 0, 3, 1,
					new ProcessingRule.Goal(keys[i], ProductAmount.of(1), ProductAmount.of(2000)), reserve);
			rules.add(rule); watermarks.put(rule.id(), i % 2 == 0);
		}
		var staging = TransferStaging.restore(ledger, count + 1, 10, views);
		var savedScheduler = new SchedulerCheckpoint(rules, ProcessingRuleScheduler.Mode.FAIR, watermarks, "goal-0", 1);
		var scheduler = ProcessingRuleScheduler.restore(ledger, new ProcessingRuleIndex(rules, List.of(), Map.of()), savedScheduler);
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
				new MemberCapabilitySnapshot.Origin("minecraft:overworld", -1, 64, 0));
		var source = new NetworkCheckpointSource(identity, ledger, policy, staging, scheduler);
		var work = new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:work", 3, "default"), 1, 30, 40, 500, 1, 0, Map.of());
		var members = new MemberCapabilitySnapshot[count];
		for (int i = 0; i < count; i++) {
			members[i] = new MemberCapabilitySnapshot(UUID.randomUUID(), 1, "test:member",
					new MemberCapabilitySnapshot.Origin("minecraft:overworld", i, 64, 0), MemberCapabilitySnapshot.Availability.OFFLINE, 0, 1, List.of(work));
			source.putMember(members[i]); source.putLane(new VirtualLaneState(members[i].memberId(), 1, 0, work, 0));
		}
		return new Fixture(keys, policy, ledger, staging, scheduler, transactions, transfers, members, work, source);
	}
	private static ProductAmount initial(int index) { return index % 100 == 0 ? LARGE : ProductAmount.of(1000); }
	private static NetworkCheckpoint checkedConstruction(NetworkCheckpoint frozen) {
		var ledger = new LedgerCheckpoint(frozen.ledger().revision(), frozen.ledger().balances(), frozen.ledger().transactions());
		var state = frozen.scheduler();
		var scheduler = new SchedulerCheckpoint(state.rules(), state.mode(), state.watermarks(), state.cursorRule(), state.used());
		return new NetworkCheckpoint(frozen.identity(), frozen.revision(), frozen.policyRevision(), ledger, frozen.transfers(),
				frozen.discoveries(), frozen.members(), frozen.lanes(), scheduler);
	}
	private static JsonObject measure(int keyCount, int count, boolean reverse) {
		var state = fixture(keyCount, count); var baseline = state.source.capture(1);
		for (int i = 0; i < 256; i++) state.source.capture(1);
		for (int i = 0; i < 3; i++) checkedConstruction(baseline);
		var result = new JsonObject(); result.addProperty("balanceKeys", keyCount); result.addProperty("recordsPerMetadataDomain", count);
		result.addProperty("checkedConstructionFirst", !reverse);
		Measurement capture = null;
		for (boolean roots : new boolean[] {reverse, !reverse}) {
			var measured = sample(roots ? () -> state.source.capture(1) : () -> checkedConstruction(baseline), roots ? 512 : 5);
			result.add(roots ? "rootCapture" : "checkedConstruction", measured.metrics());
			require(baseline.equals(measured.snapshot()), "Capture paths disagree");
			if (roots) capture = measured;
		}
		var frozen = capture.snapshot();
		int changes = Math.min(count, 10_000); long[] batches = new long[(changes + 99) / 100]; long maxSingle = 0;
		long allocated = allocated();
		for (int batch = 0; batch < batches.length; batch++) {
			long started = System.nanoTime();
			for (int i = batch * 100; i < Math.min(changes, (batch + 1) * 100); i++) {
				long one = System.nanoTime(); mutate(state, count, i); maxSingle = Math.max(maxSingle, System.nanoTime() - one);
			}
			state.source.capture(batch + 2); batches[batch] = System.nanoTime() - started;
		}
		long allocatedBytes = allocated() - allocated;
		result.add("mutate100AndCapture", timings(batches)); result.addProperty("mutationAllocatedBytes", allocatedBytes);
		result.addProperty("maxSingleMutationNanos", maxSingle); result.addProperty("mutations", changes);
		state.scheduler.replace(new ProcessingRuleIndex(List.of(), List.of(), Map.of()));
		var current = state.source.capture(batches.length + 2);
		for (int i = 0; i < keyCount; i++) {
			var oldAmount = initial(i).subtract(ProductAmount.of(i < count ? 1 : 0));
			require(oldAmount.equals(frozen.ledger().balances().get(state.keys[i])), "Frozen balance changed");
			var expected = oldAmount.subtract(ProductAmount.of(i < changes ? 1 : 0)).add(ProductAmount.of(i == keyCount - 1 ? changes : 0));
			require(expected.equals(current.ledger().balances().get(state.keys[i])), "Live balance differs from settlement model");
		}
		require(frozen.ledger().transactions().size() == count && current.ledger().transactions().size() == count - changes, "Reservation snapshot changed");
		require(frozen.transfers().size() == count && current.transfers().size() == count - changes, "Staging snapshot changed");
		require(frozen.discoveries().size() == count && current.discoveries().size() == count + changes, "Discovery snapshot changed");
		require(frozen.members().stream().noneMatch(MemberCapabilitySnapshot::online)
				&& current.members().stream().filter(MemberCapabilitySnapshot::online).count() == changes, "Member snapshot changed");
		require(frozen.lanes().stream().allMatch(lane -> lane.progress() == 0)
				&& current.lanes().stream().filter(lane -> lane.progress() == 1).count() == changes, "Lane snapshot changed");
		require(frozen.scheduler().rules().size() == count && frozen.scheduler().watermarks().size() == count
				&& current.scheduler().equals(SchedulerCheckpoint.EMPTY), "Scheduler snapshot changed");
		result.addProperty("allDomainsAndExactBalancesVerified", true); return result;
	}
	static void mutate(Fixture state, int count, int index) {
		require(state.ledger.markPaid(state.transactions[index]) && state.ledger.commit(state.transactions[index]), "Work could not settle");
		state.staging.resolve(state.staging.pending(state.transfers[index]), 1);
		require(state.policy.recordVerifiedProduction("test:discover", state.keys[count + index], 3), "Discovery failed");
		var member = state.members[index];
		state.source.putMember(new MemberCapabilitySnapshot(member.memberId(), 1, member.machineId(), member.origin(),
				MemberCapabilitySnapshot.Availability.ONLINE, 0, 1, member.alternatives()));
		state.source.putLane(new VirtualLaneState(member.memberId(), 1, 0, state.work, 1));
	}
	private static Measurement sample(Supplier<NetworkCheckpoint> capture, int count) {
		long[] samples = new long[count]; NetworkCheckpoint last = null; long allocated = allocated();
		for (int i = 0; i < count; i++) {
			long start = System.nanoTime(); last = capture.get(); samples[i] = System.nanoTime() - start;
		}
		long bytes = allocated() - allocated; var metrics = timings(samples);
		metrics.addProperty("allocatedBytesPerCapture", (double) bytes / count); return new Measurement(last, metrics);
	}
	private static JsonObject timings(long[] samples) {
		Arrays.sort(samples); var result = new JsonObject(); result.addProperty("samples", samples.length);
		result.addProperty("p50Nanos", samples[samples.length / 2]); result.addProperty("p95Nanos", samples[(samples.length - 1) * 95 / 100]);
		result.addProperty("p99Nanos", samples[(samples.length - 1) * 99 / 100]); result.addProperty("maxNanos", samples[samples.length - 1]); return result;
	}
	private static long allocated() { return ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
}
