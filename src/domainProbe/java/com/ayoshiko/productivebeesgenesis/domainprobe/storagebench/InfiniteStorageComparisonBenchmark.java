package com.ayoshiko.productivebeesgenesis.domainprobe.storagebench;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.google.gson.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.math.BigInteger;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.storagebench.StorageAmountBackends.*;

/** 四个数量后端的模型实验；正确性失败直接终止，不代表模组排名、AE2 吞吐或世界保存性能。 */
public final class InfiniteStorageComparisonBenchmark {
    private static final com.sun.management.ThreadMXBean ALLOCATIONS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final ProductAmount ONE = ProductAmount.of(1);
    private static final BigInteger WIDE = BigInteger.ONE.shiftLeft(256);
    private static final long SEED = 0x51A6EL;

    public static void main(String[] args) throws Exception {
        Path target = Path.of(args[0]);
        if (Files.exists(target)) throw new IllegalArgumentException("Evidence already exists: " + target);
        int fork = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation meter unavailable");
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        var report = new JsonObject();
        report.addProperty("schema", 2);
        report.addProperty("java", System.getProperty("java.version"));
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("fork", fork);
        report.addProperty("seed", SEED);
        report.addProperty("scope", "amount models; shared ProductKey, exact visits, normalized full NBT; no AE2 or disk durability");
        report.add("sources", sourceHashes());
        var cases = new JsonArray();
        var order = new ArrayList<>(List.of(NAMES));
        if ((fork & 1) != 0) Collections.reverse(order);
        for (String name : order) verifyContract(name);
        report.addProperty("boundaryRandomSimulationAndCodecChecks", true);
        for (int count : new int[]{10_000, 1_000_000}) {
            var keys = new ProductKey[count];
            for (int i = 0; i < count; i++) keys[i] = key(i);
            int[] access = new int[Math.min(100_000, count)];
            int[] increments = new int[count];
            var random = new Random(SEED);
            for (int i = 0; i < access.length; i++) { access[i] = random.nextInt(count); increments[access[i]]++; }
            for (String name : order) {
                cases.add(measure(name, keys, access, increments));
                System.gc();
            }
        }
        report.add("cases", cases);
        report.addProperty("passed", true);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardOpenOption.CREATE_NEW);
        System.out.println("Verified storage model benchmark: " + target);
    }

    private static JsonObject measure(String name, ProductKey[] keys, int[] access, int[] increments) throws Exception {
        Backend backend = create(name);
        var result = new JsonObject();
        result.addProperty("backend", name); result.addProperty("keys", keys.length);
        result.addProperty("componentBytes", 0);
        result.addProperty("aboveLongKeys", keys.length / 100);
        result.addProperty("above126BitsKeys", keys.length / 200);
        long heap = usedHeapAfterGc(), allocated = allocated(), start = System.nanoTime();
        for (int i = 0; i < keys.length; i++) backend.add(keys[i], initial(i));
        result.addProperty("insertNanos", System.nanoTime() - start);
        result.addProperty("insertAllocatedBytes", allocated() - allocated);
        result.addProperty("retainedHeapBytesApprox", Math.max(0, usedHeapAfterGc() - heap));
        long[] samples = new long[access.length / 100];
        allocated = allocated();
        for (int batch = 0; batch < samples.length; batch++) {
            start = System.nanoTime();
            for (int j = batch * 100; j < (batch + 1) * 100; j++) backend.add(keys[access[j]], ONE);
            samples[batch] = System.nanoTime() - start;
        }
        result.addProperty("mutateAllocatedBytes", allocated() - allocated);
        Arrays.sort(samples);
        result.addProperty("mutate100P50Nanos", samples[samples.length / 2]);
        result.addProperty("mutate100P99Nanos", samples[Math.min(samples.length - 1, samples.length * 99 / 100)]);
        result.addProperty("mutate100MaxNanos", samples[samples.length - 1]);

        // 校验在计时之外：逐键精确值，不能用非零 checksum 当作守恒证据。
        long expectedChecksum = 0;
        for (int i = 0; i < keys.length; i++) {
            var expected = initial(i).exact().add(BigInteger.valueOf(increments[i]));
            require(backend.amount(keys[i]).exact().equals(expected), name + " lost amount at " + i);
            expectedChecksum += checksum(keys[i], ProductAmount.of(expected));
        }
        long[] observed = {0, 0};
        allocated = allocated(); start = System.nanoTime();
        backend.visit((key, amount) -> { observed[0] += checksum(key, amount); observed[1]++; });
        result.addProperty("visitNanos", System.nanoTime() - start);
        result.addProperty("visitAllocatedBytes", allocated() - allocated);
        require(observed[0] == expectedChecksum && observed[1] == keys.length, name + " visit mismatch");
        result.addProperty("exactAmountsAndVisitVerified", true);

        // 构造 NBT 与写字节分别计时、分别统计分配；统一格式，不测上游增量保存布局。
        allocated = allocated(); start = System.nanoTime();
        CompoundTag tag = backend.serialize();
        result.addProperty("tagBuildNanos", System.nanoTime() - start);
        result.addProperty("tagBuildAllocatedBytes", allocated() - allocated);
        allocated = allocated(); start = System.nanoTime();
        var bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        result.addProperty("nbtEncodeNanos", System.nanoTime() - start);
        result.addProperty("nbtEncodeAllocatedBytes", allocated() - allocated);
        result.addProperty("nbtBytes", bytes.size());
        var restored = decode(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        require(restored.size() == keys.length, name + " serialization count mismatch");
        for (int i = 0; i < keys.length; i++) {
            require(initial(i).exact().add(BigInteger.valueOf(increments[i])).equals(restored.get(keys[i])),
                    name + " serialization amount mismatch at " + i);
        }
        result.addProperty("nbtRoundTripExact", true);
        return result;
    }

    private static ProductAmount initial(int i) {
        return i % 100 != 0 ? ProductAmount.of(i + 1)
                : ProductAmount.of(i % 200 == 0 ? WIDE : BigInteger.ONE.shiftLeft(70));
    }
    // 溢出在此仅作为可重复哈希，绝不是可对外上报的数量合计。
    private static long checksum(ProductKey key, ProductAmount amount) {
        return ((long) key.hashCode() * 0x9E3779B1L) ^ amount.longSaturated();
    }

    private static void verifyContract(String name) throws Exception {
        Backend backend = create(name); ProductKey first = key(0);
        for (BigInteger boundary : new BigInteger[]{MAX_LONG, MAX_LONG.add(BigInteger.ONE),
                MAX_126, MAX_126.add(BigInteger.ONE), WIDE}) {
            backend.set(first, ProductAmount.ZERO);
            backend.add(first, ProductAmount.of(boundary));
            backend.add(first, ONE);
            backend.add(first, ProductAmount.of(WIDE));
            BigInteger total = boundary.add(BigInteger.ONE).add(WIDE);
            require(backend.amount(first).exact().equals(total), name + " wide addition");
            require(backend.extract(first, ProductAmount.of(total.subtract(MAX_LONG)), true).exact()
                    .equals(total.subtract(MAX_LONG)) && backend.amount(first).exact().equals(total), name + " simulation mutated");
            backend.extract(first, ProductAmount.of(total.subtract(MAX_LONG)), false);
            require(backend.amount(first).equals(ProductAmount.of(Long.MAX_VALUE)), name + " downgrade");
            backend.extract(first, ProductAmount.of(WIDE), false);
            require(backend.amount(first).isZero() && decode(backend.serialize()).isEmpty(), name + " zero cleanup");
        }
        Map<ProductKey, BigInteger> oracle = new HashMap<>();
        var random = new Random(SEED);
        for (int step = 0; step < 512; step++) {
            ProductKey key = key(random.nextInt(16));
            BigInteger n = step % 17 == 0 ? WIDE : BigInteger.valueOf(random.nextInt(100_000));
            BigInteger old = oracle.getOrDefault(key, BigInteger.ZERO);
            if (random.nextBoolean()) {
                backend.add(key, ProductAmount.of(n));
                if (old.add(n).signum() > 0) oracle.put(key, old.add(n));
            } else {
                BigInteger taken = old.min(n);
                boolean simulate = step % 3 == 0;
                require(backend.extract(key, ProductAmount.of(n), simulate).exact().equals(taken), name + " extraction amount");
                if (!simulate) {
                    if (old.equals(taken)) oracle.remove(key); else oracle.put(key, old.subtract(taken));
                }
            }
            require(decode(backend.serialize()).equals(oracle), name + " independent model mismatch at " + step);
        }
        CompoundTag saved = backend.serialize();
        var decoded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(encode(saved))));
        require(decode(decoded).equals(oracle), name + " real NBT round trip");
        // 验证 oracle 拒绝错量而非只检查序列化能运行。
        var damaged = decoded.copy();
        var firstEntry = damaged.getList("entries", Tag.TAG_COMPOUND).getCompound(0);
        firstEntry.remove("big"); firstEntry.putLong("small", 1);
        require(!decode(damaged).equals(oracle), name + " corrupted evidence was accepted");
        var duplicate = decoded.copy();
        var entries = duplicate.getList("entries", Tag.TAG_COMPOUND); entries.add(entries.get(0).copy());
        boolean rejected = false;
        try { decode(duplicate); } catch (IllegalStateException expected) { rejected = true; }
        require(rejected, name + " duplicate key accepted");
    }

    private static byte[] encode(CompoundTag tag) throws IOException {
        var bytes = new ByteArrayOutputStream(); NbtIo.write(tag, new DataOutputStream(bytes)); return bytes.toByteArray();
    }
    private static JsonObject sourceHashes() throws Exception {
        var hashes = new JsonObject();
        for (String path : List.of("gradle.properties", "gradle/network-domain-probe.gradle",
                "src/domainProbe/java/com/ayoshiko/productivebeesgenesis/domainprobe/storagebench/InfiniteStorageComparisonBenchmark.java",
                "src/domainProbe/java/com/ayoshiko/productivebeesgenesis/domainprobe/storagebench/StorageAmountBackends.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/ProductAmount.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/ProductKey.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/PagedProductAmounts.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/ProductAmountPages.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/ProductAmountTrie.java",
                "src/main/java/com/ayoshiko/productivebeesgenesis/apiculture/storage/SparseProductAmounts.java")) {
            hashes.addProperty(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(path)))));
        }
        return hashes;
    }
    private static long allocated() { return ALLOCATIONS.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long usedHeapAfterGc() {
        System.gc(); Runtime runtime = Runtime.getRuntime(); return runtime.totalMemory() - runtime.freeMemory();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private InfiniteStorageComparisonBenchmark() {}
}
