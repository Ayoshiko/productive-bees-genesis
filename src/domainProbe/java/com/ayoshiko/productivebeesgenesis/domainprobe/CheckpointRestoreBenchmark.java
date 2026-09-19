package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 独立 JVM 的真实原版注册表／组件冷对象恢复；不声称清空 OS 文件缓存或测量 MSPT。 */
public final class CheckpointRestoreBenchmark {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private CheckpointRestoreBenchmark() { }
	public static void run(net.minecraft.server.MinecraftServer server) throws Exception {
		Path folder = Path.of(System.getProperty("pbg.restore.folder")); String mode = System.getProperty("pbg.restore.mode");
		int count = Integer.getInteger("pbg.restore.keys", 1_000_000);
		Files.createDirectories(folder); Path file = folder.resolve("domain-" + count + ".dat");
		if (mode.equals("write")) { generate(file, count); return; }
		Path report = folder.resolve("restore-" + count + "-" + System.getProperty("pbg.restore.report", "default") + ".json"); require(!Files.exists(report), "Preserve previous report");
		var registries = server.registryAccess();
		var result = new JsonObject(); var times = new ArrayList<Long>(); long allocated = allocated();
		long began = System.nanoTime();
		try (var reader = new CheckpointReadService(); var input = reader.tryOpen(file).orElseThrow();
				var decoder = new CheckpointDecoder(input, key -> ProductKeyCodec.validatePersisted(key, registries))) {
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				require(System.nanoTime() - began < 600_000_000_000L, "Restore timed out");
				long start = System.nanoTime(); decoder.step(2048, 2_000_000); times.add(System.nanoTime() - start);
				if (times.size() % 1000 == 0) System.out.println("RESTORE_PROGRESS steps=" + decoder.progress().steps());
				Thread.yield();
			}
			var progress = decoder.progress(); require(progress.state() == CheckpointDecoder.State.COMPLETE, progress.failure());
			var checkpoint = decoder.checkpoint(); require(checkpoint.ledger().balances().size() == count, "Key count mismatch");
			long restoreNanos = System.nanoTime() - began, restoreBytes = allocated() - allocated;
			for (int i = 0; i < count; i++) require(amount(i).equals(checkpoint.ledger().balances().get(key(i))), "Component or precise balance changed at " + i);
			var expected = Files.readString(folder.resolve("digest-" + count + ".txt"));
			require(expected.equals(input.status().sha256()), "Input file digest differs");
			require(checkpoint.revision() == 1 && checkpoint.policyRevision() == 1 && checkpoint.ledger().revision() == 1
					&& checkpoint.members().isEmpty() && checkpoint.transfers().isEmpty() && checkpoint.lanes().isEmpty()
					&& checkpoint.discoveries().isEmpty() && checkpoint.ledger().transactions().isEmpty()
					&& checkpoint.scheduler().equals(SchedulerCheckpoint.EMPTY), "Fixture metadata changed");
			long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
			result.addProperty("balanceKeys", count); result.addProperty("restoreNanos", restoreNanos); result.addProperty("ownerAllocatedBytes", restoreBytes);
			result.addProperty("steps", progress.steps()); result.addProperty("maxStepNanos", progress.maxStepNanos());
			result.addProperty("slices", sorted.length); result.addProperty("sliceP95Nanos", sorted[(sorted.length - 1) * 95 / 100]);
			result.addProperty("sliceP99Nanos", sorted[(sorted.length - 1) * 99 / 100]); result.addProperty("sliceMaxNanos", sorted[sorted.length - 1]);
			result.addProperty("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
			result.addProperty("sha256", expected); result.addProperty("passed", true);
			result.addProperty("everyComponentAndExactBalanceVerified", true);
			result.addProperty("scope", "Fresh NeoForge server JVM, real registry and custom_data validation, full indexed restore and every exact balance; OS cache uncontrolled; tightly driven soft 2 ms slices, not server tick latency; indivisible codec costs included; no MSPT claim");
		}
		Files.writeString(report, new GsonBuilder().setPrettyPrinting().create().toJson(result)); System.out.println(result);
	}
	private static ProductKey key(int i) {
		var data = new CompoundTag(); data.putInt("entry", i); data.putString("label", "component-" + i);
		var components = new CompoundTag(); components.put("minecraft:custom_data", data);
		return new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("minecraft:gold_ingot"), components);
	}
	private static ProductAmount amount(int i) { return i % 64 == 0 ? ProductAmount.of(java.math.BigInteger.ONE.shiftLeft(128).add(java.math.BigInteger.valueOf(i))) : ProductAmount.of(i + 1L); }
	private static void generate(Path file, int count) throws Exception {
		require(!Files.exists(file), "Preserve existing fixture"); var balances = new PagedProductAmounts();
		for (int i = 0; i < count; i++) balances.set(key(i), amount(i));
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, new Origin("minecraft:overworld", 0, 64, 0));
		var checkpoint = new NetworkCheckpoint(identity, 1, 1, new LedgerCheckpoint(1, balances.snapshot(), List.of()), List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		try (var out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))))) { NetworkCheckpointStream.write(checkpoint, out); }
		Files.writeString(file.resolveSibling("digest-" + count + ".txt"), digest(checkpoint)); System.out.println("RESTORE_FIXTURE_CREATED keys=" + count);
	}
	private static String digest(NetworkCheckpoint checkpoint) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		NetworkCheckpointStream.write(checkpoint, new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
		return HexFormat.of().formatHex(digest.digest());
	}
	private static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
}
