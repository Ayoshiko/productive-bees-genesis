package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

/** 只比较捕获与后续变更成本；不把此 JVM 微基准当成游戏 MSPT 或文件保存测试。 */
public final class SnapshotBenchmark {
	private static final com.sun.management.ThreadMXBean ALLOCATIONS =
			(com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final ProductAmount LARGE = ProductAmount.of(BigInteger.ONE.shiftLeft(256));
	private SnapshotBenchmark() { }
	public static void main(String[] args) throws Exception {
		Path target = Path.of(args[0]);
		if (Files.exists(target)) throw new IllegalStateException("Preserve the previous benchmark report");
		var report = new JsonObject(); var cases = new JsonArray();
		for (int count : new int[] {10_000, 1_000_000}) {
			var keys = new ProductKey[count];
			for (int i = 0; i < count; i++) {
				var component = new CompoundTag(); component.putInt("test:variant", i);
				keys[i] = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:product"), component);
			}
			// 两种次序在独立 JVM 交替执行，避免固定次序掩盖预热差异。
			boolean reverse = args.length > 1 && Integer.parseInt(args[1]) % 2 != 0;
			for (boolean paged : new boolean[] {reverse, !reverse}) {
				cases.add(measure(keys, paged)); System.gc();
			}
		}
		report.add("cases", cases); report.addProperty("passed", true);
		report.addProperty("java", System.getProperty("java.version"));
		Files.createDirectories(target.getParent());
		Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(report));
		System.out.println(report);
	}
	private static ProductAmount initial(int i) { return i % 100 == 0 ? LARGE : ProductAmount.of(i + 1); }
	private static JsonObject measure(ProductKey[] keys, boolean paged) {
		var result = new JsonObject(); result.addProperty("backend", paged ? "paged" : "sparse"); result.addProperty("keys", keys.length);
		var sparse = paged ? null : new SparseProductAmounts<ProductKey>();
		var pages = paged ? new PagedProductAmounts() : null;
		for (int i = 0; i < keys.length; i++) {
			if (paged) pages.set(keys[i], initial(i)); else sparse.set(keys[i], initial(i));
		}
		var random = new Random(0xD09B1); var access = new int[100_000];
		for (int i = 0; i < access.length; i++) access[i] = random.nextInt(keys.length);
		// 预热相同的查询／覆盖路径，再冻结一个真实存档边界。
		for (int index : access) {
			var amount = paged ? pages.amount(keys[index]) : sparse.amount(keys[index]);
			if (paged) pages.set(keys[index], amount); else sparse.set(keys[index], amount);
		}
		long allocated = allocated(), started = System.nanoTime();
		var checkpoint = new LedgerCheckpoint(1, paged ? pages.snapshot() : sparse.snapshot(), List.of());
		result.addProperty("captureNanos", System.nanoTime() - started);
		result.addProperty("captureAllocatedBytes", allocated() - allocated);
		long[] samples = new long[access.length / 100];
		allocated = allocated();
		for (int batch = 0; batch < samples.length; batch++) {
			started = System.nanoTime();
			for (int j = batch * 100; j < (batch + 1) * 100; j++) {
				var key = keys[access[j]];
				var value = (paged ? pages.amount(key) : sparse.amount(key)).add(ProductAmount.of(1));
				if (paged) pages.set(key, value); else sparse.set(key, value);
			}
			samples[batch] = System.nanoTime() - started;
		}
		result.addProperty("mutateAllocatedBytes", allocated() - allocated);
		Arrays.sort(samples);
		result.addProperty("mutate100P50Nanos", samples[samples.length / 2]);
		result.addProperty("mutate100P99Nanos", samples[samples.length * 99 / 100]);
		result.addProperty("mutate100MaxNanos", samples[samples.length - 1]);
		for (int i = 0; i < keys.length; i++) {
			if (!initial(i).equals(checkpoint.balances().get(keys[i]))) throw new IllegalStateException("Frozen checkpoint changed");
		}
		Map<ProductKey, ProductAmount> current = paged ? pages.snapshot() : sparse.snapshot();
		int[] increments = new int[keys.length]; for (int index : access) increments[index]++;
		for (int i = 0; i < keys.length; i++) {
			if (!initial(i).add(ProductAmount.of(increments[i])).equals(current.get(keys[i]))) throw new IllegalStateException("Live amount changed incorrectly");
		}
		result.addProperty("frozenAndCurrentAmountsVerified", true);
		return result;
	}
	private static long allocated() { return ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
}
