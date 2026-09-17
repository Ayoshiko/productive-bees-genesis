package com.ayoshiko.productivebeesgenesis.storageprototype.bench;

import com.ayoshiko.productivebeesgenesis.storageprototype.amount.*;
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
import java.util.Random;

/** D02 探索性微基准：三个独立 JVM，报告批次分位数，不伪称逐操作尾延迟。 */
public final class AmountBenchmark {
	private static final int OPERATIONS = 32_768;
	private static final int REPEATS = 5;
	private static final BigInteger BIG = BigInteger.ONE.shiftLeft(180);
	private static final com.sun.management.ThreadMXBean ALLOCATION =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static volatile long blackhole;

	private AmountBenchmark() { }

	public static void main(String[] args) throws Exception {
		int fork = Integer.parseInt(args[1]);
		ALLOCATION.setThreadAllocatedMemoryEnabled(true);
		JsonObject report = new JsonObject();
		report.addProperty("fork", fork);
		report.addProperty("java", System.getProperty("java.version"));
		report.addProperty("timing", "ns per probe or add/extract pair; p95 is across five measured batches");
		report.addProperty("memory", "post-GC heap delta excluding prebuilt keys; approximate, not retained-size proof");
		JsonArray rows = new JsonArray();
		report.add("rows", rows);
		for (int count : new int[]{1_000, 10_000, 100_000, 1_000_000}) {
			Key[] keys = new Key[count];
			Key[] churn = new Key[count];
			for (int i = 0; i < count; i++) {
				keys[i] = new Key(i);
				churn[i] = new Key(-i - 1);
			}
			for (String shape : List.of("long", "mixed1pct", "big180", "cross63", "cross126")) {
				for (int candidate = 0; candidate < 3; candidate++) {
					int order = (candidate + fork) % 3;
					runCandidate(rows, keys, churn, shape, order);
				}
			}
			System.out.println("D02_BENCH keys=" + count + " rows=" + rows.size());
		}
		Path output = Path.of(args[0]);
		Files.createDirectories(output.getParent());
		Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report));
	}

	private static void runCandidate(JsonArray rows, Key[] keys, Key[] churn, String shape, int candidate) throws Exception {
		long heapBefore = heapAfterGc();
		AmountStore<Key> store = switch (candidate) {
			case 0 -> new SparseAmountStore<>();
			case 1 -> new IndexedAmountStore<>();
			default -> new BigAmountStore<>();
		};
		for (int i = 0; i < keys.length; i++) store.set(keys[i], initial(shape, i));
		long retained = heapAfterGc() - heapBefore;
		for (boolean hot : new boolean[]{false, true}) {
			int[] indices = indices(keys.length, hot);
			for (String operation : List.of("probe", "pair", "churn")) {
				for (int warmup = 0; warmup < 3; warmup++) batch(store, keys, churn, indices, operation);
				double[] times = new double[REPEATS];
				long bytes = 0;
				for (int repeat = 0; repeat < REPEATS; repeat++) {
					long allocation = allocated();
					long begin = System.nanoTime();
					batch(store, keys, churn, indices, operation);
					times[repeat] = (System.nanoTime() - begin) / (double) OPERATIONS;
					bytes += allocated() - allocation;
				}
				Arrays.sort(times);
				JsonObject row = row(store, shape, keys.length, retained);
				row.addProperty("access", hot ? "90pct-in-1pct" : "uniform");
				row.addProperty("operation", operation);
				row.addProperty("medianNs", times[REPEATS / 2]);
				row.addProperty("p95BatchNs", times[REPEATS - 1]);
				row.addProperty("allocatedBytesPerOp", bytes / (double) (OPERATIONS * REPEATS));
				rows.add(row);
			}
		}
		long[] checksum = {0};
		long allocation = allocated();
		long begin = System.nanoTime();
		store.visitVisible((key, value) -> checksum[0] += value ^ key.id());
		JsonObject enumeration = row(store, shape, keys.length, retained);
		enumeration.addProperty("operation", "enumerateVisible");
		enumeration.addProperty("totalMs", (System.nanoTime() - begin) / 1_000_000D);
		enumeration.addProperty("allocatedBytes", allocated() - allocation);
		blackhole = checksum[0];
		rows.add(enumeration);
		allocation = allocated();
		begin = System.nanoTime();
		ArrayList<Entry> entries = new ArrayList<>(store.size());
		store.visitExact((key, value) -> entries.add(new Entry(key, value)));
		entries.sort((a, b) -> {
			int quantity = b.amount().compareTo(a.amount());
			return quantity != 0 ? quantity : Integer.compare(a.key().id(), b.key().id());
		});
		JsonObject sorting = row(store, shape, keys.length, retained);
		sorting.addProperty("operation", "exactSnapshotAndSort");
		sorting.addProperty("totalMs", (System.nanoTime() - begin) / 1_000_000D);
		sorting.addProperty("allocatedBytes", allocated() - allocation);
		rows.add(sorting);
		blackhole = entries.getFirst().key().id();
		if (store.size() != keys.length) throw new AssertionError("Churn leaked keys");
	}

	private static JsonObject row(AmountStore<Key> store, String shape, int count, long retained) {
		JsonObject row = new JsonObject();
		row.addProperty("candidate", store.getClass().getSimpleName());
		row.addProperty("shape", shape);
		row.addProperty("keys", count);
		row.addProperty("approximateStoreHeapBytes", retained);
		return row;
	}

	private static void batch(AmountStore<Key> store, Key[] keys, Key[] churn, int[] indices, String operation) {
		long result = 0;
		for (int index : indices) {
			Key key = operation.equals("churn") ? churn[index] : keys[index];
			switch (operation) {
				case "probe" -> result ^= store.visible(key);
				case "pair", "churn" -> {
					store.add(key, 1);
					result += store.extract(key, 1);
				}
				default -> throw new IllegalArgumentException(operation);
			}
		}
		blackhole = result;
	}

	private static int[] indices(int count, boolean hot) {
		Random random = new Random(0xD02);
		int[] indices = new int[OPERATIONS];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = random.nextInt(hot && random.nextInt(10) != 0 ? Math.max(1, count / 100) : count);
		}
		return indices;
	}

	private static BigInteger initial(String shape, int index) {
		return switch (shape) {
			case "long" -> BigInteger.valueOf(10_000L + index);
			case "mixed1pct" -> index % 100 == 0 ? BIG.add(BigInteger.valueOf(index)) : BigInteger.valueOf(10_000L + index);
			case "big180" -> BIG.add(BigInteger.valueOf(index));
			case "cross63" -> AmountStore.MAX_LONG;
			case "cross126" -> BigInteger.ONE.shiftLeft(126).subtract(BigInteger.ONE);
			default -> throw new IllegalArgumentException(shape);
		};
	}

	private static long allocated() { return ALLOCATION.getThreadAllocatedBytes(Thread.currentThread().threadId()); }

	private static long heapAfterGc() throws InterruptedException {
		System.gc();
		Thread.sleep(40);
		return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
	}

	private record Key(int id) { }
	private record Entry(Key key, BigInteger amount) { }
}
