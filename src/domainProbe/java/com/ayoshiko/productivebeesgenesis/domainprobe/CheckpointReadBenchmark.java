package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 复用已验收保存夹具，隔离读取测量；不代表注册表验证、索引恢复或磁盘冷缓存。 */
public final class CheckpointReadBenchmark {
	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private CheckpointReadBenchmark() { }
	public static void main(String[] args) throws Exception {
		Path reportFile = Path.of(args[0]), sourceReport = Path.of(args[1]);
		if (Files.exists(reportFile)) throw new IllegalStateException("Preserve previous read evidence");
		var source = JsonParser.parseString(Files.readString(sourceReport)).getAsJsonObject();
		require(source.get("passed").getAsBoolean(), "Read fixture must have passed stream-write validation");
		var report = new JsonObject(); var cases = new JsonArray();
		for (var raw : source.getAsJsonArray("cases")) {
			var sample = raw.getAsJsonObject(); int keys = sample.get("balanceKeys").getAsInt();
			Path directory = sourceReport.resolveSibling(sourceReport.getFileName() + "-" + keys);
			var result = measure(domainFile(directory), sample.get("uncompressedSha256").getAsString());
			result.addProperty("balanceKeys", keys); result.addProperty("metadataPerDomain", sample.get("recordsPerMetadataDomain").getAsInt()); cases.add(result);
			System.out.println("READ_BENCHMARK_CASE_COMPLETE keys=" + keys);
		}
		report.add("largeRecord", measure(domainFile(sourceReport.resolveSibling(sourceReport.getFileName() + "-large-record")), null));
		report.add("cases", cases); report.addProperty("passed", true); report.addProperty("sourceWriteReport", sourceReport.toAbsolutePath().toString());
		report.addProperty("scope", "Fresh reader over existing synthetic full-domain files; bounded transport and exact event digests, no registry/index recovery, no cold-cache or MSPT claim");
		Files.createDirectories(reportFile.getParent()); Files.writeString(reportFile, new GsonBuilder().setPrettyPrinting().create().toJson(report)); System.out.println(report);
	}
	private static Path domainFile(Path folder) throws Exception {
		try (var files = Files.list(folder)) {
			var domains = files.filter(path -> path.getFileName().toString().matches("productivebeesgenesis_network_[0-9a-f-]{36}\\.dat")).toList();
			require(domains.size() == 1, "Expected exactly one fixture domain"); return domains.getFirst();
		}
	}
	private static JsonObject measure(Path file, String expected) throws Exception {
		var result = new JsonObject(); var consumed = new NbtEventDigest(); long[] polls = new long[2_000_000]; int pollCount = 0;
		try (var reader = new CheckpointReadService()) {
			long[] existingThreads = THREADS.getAllThreadIds();
			long allocation = allocated(); long started = System.nanoTime(); var session = reader.tryOpen(file).orElseThrow();
			result.addProperty("requestNanos", System.nanoTime() - started); result.addProperty("requestAllocatedBytes", allocated() - allocation);
			long worker = workerId(existingThreads); long began = System.nanoTime(), consumerNanos = 0, consumerBytes = 0;
			long deadline = began + TimeUnit.MINUTES.toNanos(3);
			while (true) {
				require(System.nanoTime() < deadline && pollCount < polls.length, "Read did not finish within budget");
				started = System.nanoTime(); var batch = session.poll(); polls[pollCount++] = System.nanoTime() - started;
				if (batch != null) {
					allocation = allocated(); started = System.nanoTime(); consumed.accept(batch);
					consumerNanos += System.nanoTime() - started; consumerBytes += allocated() - allocation;
				}
				var status = session.status(); require(status.failure().isEmpty(), "Read failed: " + status.failure());
				if (status.drained()) break;
				if (batch == null) LockSupport.parkNanos(100_000);
			}
			long elapsed = System.nanoTime() - began; var status = session.status(); String eventDigest = consumed.finish();
			require(status.sha256().equals(eventDigest) && (expected == null || expected.equals(eventDigest)), "Reader or event consumer lost bytes");
			require(status.peakBatches() <= CheckpointReadService.QUEUED_BATCH_LIMIT && status.peakBytes() <= CheckpointReadService.QUEUED_BYTE_LIMIT, "Unbounded queue");
			result.addProperty("file", file.toAbsolutePath().toString()); result.addProperty("compressedBytes", Files.size(file));
			result.addProperty("decompressedBytes", status.decompressedBytes()); result.addProperty("readAndVerifyNanos", elapsed);
			result.addProperty("workerAllocatedBytes", THREADS.getThreadAllocatedBytes(worker));
			result.addProperty("consumerDigestNanos", consumerNanos); result.addProperty("consumerAllocatedBytes", consumerBytes);
			result.addProperty("peakQueuedBytes", status.peakBytes()); result.addProperty("peakQueuedBatches", status.peakBatches());
			result.addProperty("events", status.deliveredEvents()); result.addProperty("backpressureWaits", status.backpressureWaits());
			result.addProperty("uncompressedSha256", eventDigest); result.add("poll", timings(Arrays.copyOf(polls, pollCount)));
		}
		return result;
	}
	private static long workerId(long[] existing) {
		for (var info : THREADS.getThreadInfo(THREADS.getAllThreadIds())) {
			if (info != null && info.getThreadName().equals("pbg-network-reader") && Arrays.stream(existing).noneMatch(id -> id == info.getThreadId())) return info.getThreadId();
		}
		throw new IllegalStateException("Reader worker was not started");
	}
	private static long allocated() { return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
	private static JsonObject timings(long[] samples) {
		Arrays.sort(samples); var result = new JsonObject(); result.addProperty("samples", samples.length);
		result.addProperty("p50Nanos", samples[samples.length / 2]); result.addProperty("p99Nanos", samples[(samples.length - 1) * 99 / 100]);
		result.addProperty("maxNanos", samples[samples.length - 1]); return result;
	}
}
