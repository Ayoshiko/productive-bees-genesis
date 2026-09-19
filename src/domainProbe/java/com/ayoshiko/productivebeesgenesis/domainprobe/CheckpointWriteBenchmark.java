package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实保存入口＋冻结期间变更；加载仍归 D09b3，本夹具只校验文件字节与冻结源一致。 */
public final class CheckpointWriteBenchmark {
	private static final com.sun.management.ThreadMXBean ALLOCATIONS =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private record WriteMeasurement(long nanos, long allocatedBytes) { }
	private static final class Writer implements Executor, AutoCloseable {
		private final java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
		private final CopyOnWriteArrayList<WriteMeasurement> measurements = new CopyOnWriteArrayList<>();
		private volatile CountDownLatch gate = new CountDownLatch(0);
		@Override public void execute(Runnable action) {
			executor.execute(() -> {
				try { gate.await(); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
				long start = System.nanoTime(); long allocated = allocated(); action.run();
				measurements.add(new WriteMeasurement(System.nanoTime() - start, allocated() - allocated));
			});
		}
		void hold() { gate = new CountDownLatch(1); }
		void release() { gate.countDown(); }
		void awaitIdle() throws Exception { executor.submit(() -> { }).get(); }
		@Override public void close() { release(); executor.close(); }
	}
	private CheckpointWriteBenchmark() { }
	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion(); Path reportFile = Path.of(args[0]);
		if (Files.exists(reportFile)) throw new IllegalStateException("Preserve existing write benchmark evidence");
		var report = new JsonObject(); var cases = new JsonArray();
		for (int count : new int[] {10_000, 1_000_000}) {
			cases.add(measure(reportFile.resolveSibling(reportFile.getFileName() + "-" + count), count)); System.gc();
		}
		report.add("oversizedRecord", oversizedRecord(reportFile.resolveSibling(reportFile.getFileName() + "-large-record")));
		report.add("cases", cases); report.addProperty("passed", true); report.addProperty("java", System.getProperty("java.version"));
		report.addProperty("scope", "Synthetic full-domain stream save, main-thread requests/ticks, concurrent mutations and exact file digests; no registry load or MSPT claim");
		Files.createDirectories(reportFile.getParent()); Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report));
		System.out.println(report);
	}
	private static JsonObject measure(Path folder, int keyCount) throws Exception {
		if (Files.exists(folder)) throw new IllegalStateException("Preserve previous benchmark files");
		int metadata = keyCount / 10;
		var state = CheckpointCaptureBenchmark.fixture(keyCount, metadata); var frozen = state.source().capture(1);
		var result = new JsonObject(); result.addProperty("balanceKeys", keyCount); result.addProperty("recordsPerMetadataDomain", metadata);
		try (var writer = new Writer()) {
			var directory = new NetworkDirectory(folder, null, writer, null); var data = directory.create(frozen.identity());
			Path file = folder.resolve("productivebeesgenesis_network_" + frozen.identity().networkId() + ".dat");
			var target = file.toFile(); writer.awaitIdle(); writer.measurements.clear(); writer.hold(); data.publish(frozen);
			long allocated = allocated(); long started = System.nanoTime(); data.save(target, null);
			result.addProperty("initialRequestNanos", System.nanoTime() - started); result.addProperty("initialRequestAllocatedBytes", allocated() - allocated);
			long[] requests = new long[512]; allocated = allocated();
			for (int i = 0; i < requests.length; i++) {
				started = System.nanoTime(); data.save(target, null); requests[i] = System.nanoTime() - started;
			}
			result.addProperty("coalescedRequestAllocatedBytes", allocated() - allocated); result.add("coalescedRequests", timings(requests));
			int changes = Math.min(metadata, 1000);
			for (int i = 0; i < changes; i++) CheckpointCaptureBenchmark.mutate(state, metadata, i);
			var latest = state.source().capture(2); data.publish(latest); data.save(target, null);
			var status = directory.saveStatus(); require(status.activeSnapshots() == 1 && status.waitingDomains() == 0, "Queued another frozen snapshot");
			result.addProperty("reservedStreamBufferBytes", status.reservedBufferBytes()); result.addProperty("liveMutationsBeforeWrite", changes);
			writer.release(); waitReceipt(data, 1);
			require(data.isDirty() && data.persistedRevision() == 1, "Old receipt cleared a newer revision");
			var oldDigest = digest(file); require(oldDigest.equals(digest(frozen)), "Written snapshot mixed in later mutations");
			long[] ticks = new long[20_000]; int tickCount = 0; int peakSnapshots = 0; int peakBuffers = 0;
			long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3); long tickAllocation = 0;
			while (data.isDirty()) {
				require(System.nanoTime() < deadline && tickCount < ticks.length, "Save did not make progress");
				allocated = allocated(); started = System.nanoTime(); directory.tick(); ticks[tickCount++] = System.nanoTime() - started;
				tickAllocation += allocated() - allocated;
				status = directory.saveStatus(); peakSnapshots = Math.max(peakSnapshots, status.activeSnapshots()); peakBuffers = Math.max(peakBuffers, status.reservedBufferBytes());
				Thread.sleep(1);
			}
			var finalDigest = digest(file); require(finalDigest.equals(digest(latest)), "Final save differs from latest frozen domain");
			require(!oldDigest.equals(finalDigest) && data.persistedRevision() == 2, "Latest checkpoint did not advance");
			result.add("mainThreadTicks", timings(Arrays.copyOf(ticks, tickCount))); result.addProperty("tickAllocatedBytes", tickAllocation);
			result.addProperty("peakActiveSnapshots", peakSnapshots); result.addProperty("peakReservedStreamBufferBytes", peakBuffers);
			result.addProperty("compressedFileBytes", Files.size(file)); result.addProperty("uncompressedSha256", finalDigest);
			var writes = new JsonArray();
			for (var measurement : writer.measurements) {
				var value = new JsonObject(); value.addProperty("encodeCompressWriteNanos", measurement.nanos());
				value.addProperty("workerAllocatedBytes", measurement.allocatedBytes()); writes.add(value);
			}
			result.add("backgroundWrites", writes); result.addProperty("frozenAndLatestFileDigestsVerified", true);
			System.out.println("WRITE_BENCHMARK_CASE_COMPLETE keys=" + keyCount);
		}
		return result;
	}
	private static JsonObject oversizedRecord(Path folder) throws Exception {
		if (Files.exists(folder)) throw new IllegalStateException("Preserve previous single-record evidence");
		var bytes = new byte[1024 * 1024]; new java.util.Random(71).nextBytes(bytes);
		var components = new CompoundTag(); components.putByteArray("test:large", bytes);
		var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:large"), components);
		var amount = ProductAmount.of(java.math.BigInteger.ONE.shiftLeft(1_000_000));
		var ledger = new LedgerCheckpoint(1, Map.of(key, amount), List.of());
		var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
				new MemberCapabilitySnapshot.Origin("minecraft:overworld", 0, 64, 0));
		var result = new JsonObject();
		try (var writer = new Writer()) {
			var directory = new NetworkDirectory(folder, null, writer, null); var data = directory.create(identity);
			var file = folder.resolve("productivebeesgenesis_network_" + identity.networkId() + ".dat");
			writer.awaitIdle(); writer.measurements.clear();
			for (int i = 1; i <= 5; i++) {
				data.publish(new NetworkCheckpoint(identity, i, 0, ledger, List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY));
				data.save(file.toFile(), null); waitReceipt(data, i);
			}
			writer.awaitIdle(); require(digest(file).equals(digest(data.checkpoint())), "Oversized record was truncated");
			result.add("encodeCompressWrite", timings(writer.measurements.stream().mapToLong(WriteMeasurement::nanos).toArray()));
			result.addProperty("maxWorkerAllocatedBytes", writer.measurements.stream().mapToLong(WriteMeasurement::allocatedBytes).max().orElseThrow());
			result.addProperty("componentBytes", bytes.length); result.addProperty("amountBits", amount.exact().bitLength());
			result.addProperty("exactFileDigestVerified", true);
		}
		return result;
	}
	private static void waitReceipt(NetworkSavedData data, long revision) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
		while (data.persistedRevision() < revision) {
			require(System.nanoTime() < deadline, "Background writer timed out"); data.poll();
			require(data.lastFailure().isEmpty(), "Background writer failed: " + data.lastFailure()); Thread.sleep(1);
		}
	}
	private static String digest(NetworkCheckpoint checkpoint) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		try (var output = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) { NetworkCheckpointStream.write(checkpoint, output); }
		return HexFormat.of().formatHex(digest.digest());
	}
	private static String digest(Path file) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		try (var input = new GZIPInputStream(Files.newInputStream(file))) {
			var bytes = new byte[16 * 1024]; int read;
			while ((read = input.read(bytes)) >= 0) digest.update(bytes, 0, read);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
	private static JsonObject timings(long[] samples) {
		Arrays.sort(samples); var result = new JsonObject(); result.addProperty("samples", samples.length);
		result.addProperty("p50Nanos", samples[samples.length / 2]); result.addProperty("p95Nanos", samples[(samples.length - 1) * 95 / 100]);
		result.addProperty("p99Nanos", samples[(samples.length - 1) * 99 / 100]); result.addProperty("maxNanos", samples[samples.length - 1]); return result;
	}
	private static long allocated() { return ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
}
