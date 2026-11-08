package com.ayoshiko.productivebeesgenesis.resource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** 服务器线程拥有的瞬态统计；固定十个时间桶，不保存世界引用或参与资产事务。 */
public final class ResourceProductionMonitor {
    private static final long BUCKET_MILLIS = 500;
    private static final int BUCKETS = 10;
    private static final long ACTIVE_TICKS = 40;

    private static final class Sample {
        final long[] buckets = new long[BUCKETS];
        final BigInteger[] amounts = new BigInteger[BUCKETS];
        long lastObservedBucket;
        boolean waitingOutput;

        Sample() {
            Arrays.fill(buckets, Long.MIN_VALUE);
        }
    }

    private static final class Lane {
        List<ResourceKey> keys;
        ResourceProductionSnapshot.State state;
        long tick;
        long bucket;
    }

    private final LongSupplier clock;
    private final Map<ResourceKey, Sample> samples = new LinkedHashMap<>();
    private final Map<Integer, Lane> lanes = new HashMap<>();
    private final Map<List<ResourceProductionRecipe.Output>, List<ResourceKey>> outputKeys = new IdentityHashMap<>();
    private long latestTick;

    public ResourceProductionMonitor() {
        this(() -> System.nanoTime() / 1_000_000);
    }

    ResourceProductionMonitor(LongSupplier clock) {
        this.clock = clock;
    }

    private Sample sample(ResourceKey key, long bucket) {
        var value = samples.get(key);
        if (value == null && samples.size() < ResourceAmounts.MAX_KEYS) {
            value = new Sample();
            samples.put(key, value);
        }
        if (value != null) {
            value.lastObservedBucket = bucket;
        }
        return value;
    }

    private static boolean withinWindow(long observed, long bucket) {
        return observed <= bucket && bucket - observed < BUCKETS;
    }

    private Map<ResourceKey, ResourceProductionSnapshot.State> activeStates(long tick, long bucket) {
        var states = new HashMap<ResourceKey, ResourceProductionSnapshot.State>();
        var iterator = lanes.values().iterator();
        while (iterator.hasNext()) {
            var lane = iterator.next();
            if (tick < lane.tick || tick - lane.tick > ACTIVE_TICKS || !withinWindow(lane.bucket, bucket)) {
                iterator.remove();
                continue;
            }
            for (var key : lane.keys) {
                states.merge(key, lane.state, (first, second) -> first.ordinal() <= second.ordinal() ? first : second);
            }
        }
        return states;
    }

    private void retireExpired(long bucket, Set<ResourceKey> active) {
        samples.entrySet().removeIf(entry -> !active.contains(entry.getKey())
                && !withinWindow(entry.getValue().lastObservedBucket, bucket));
    }

    private void makeRoom(Collection<ResourceKey> keys, long bucket) {
        if (samples.size() + keys.size() <= ResourceAmounts.MAX_KEYS || samples.keySet().containsAll(keys)) {
            return;
        }
        var active = activeStates(latestTick, bucket).keySet();
        retireExpired(bucket, active);
        int missing = 0;
        for (var key : keys) {
            if (!samples.containsKey(key)) {
                missing++;
            }
        }
        // 目录满时优先展示当前车道；只让出历史身份，不重置仍活跃的增量槽号。
        var iterator = samples.keySet().iterator();
        while (samples.size() + missing > ResourceAmounts.MAX_KEYS && iterator.hasNext()) {
            var key = iterator.next();
            if (!active.contains(key) && !keys.contains(key)) {
                iterator.remove();
            }
        }
    }

    public void observe(int lane, Collection<ResourceKey> keys, ResourceProductionSnapshot.State state, long tick) {
        if (lane < 0 || lane >= 256 || keys.isEmpty()) {
            return;
        }
        long bucket = Math.floorDiv(clock.getAsLong(), BUCKET_MILLIS);
        latestTick = tick;
        var previous = lanes.get(lane);
        if (previous == null) {
            previous = new Lane();
            lanes.put(lane, previous);
        }
        if (previous.keys == null || previous.keys.size() != keys.size() || !previous.keys.containsAll(keys)) {
            previous.keys = List.copyOf(keys);
        }
        previous.state = state;
        previous.tick = tick;
        previous.bucket = bucket;
        makeRoom(keys, bucket);
        for (var key : keys) {
            var value = sample(key, bucket);
            if (value != null) {
                value.waitingOutput = state == ResourceProductionSnapshot.State.WAITING_OUTPUT;
            }
        }
    }

    private List<ResourceKey> keys(List<ResourceProductionRecipe.Output> outputs) {
        var keys = outputKeys.get(outputs);
        if (keys == null) {
            if (outputKeys.size() >= 256) {
                outputKeys.clear();
            }
            keys = outputs.stream().map(ResourceProductionRecipe.Output::key).distinct().toList();
            outputKeys.put(outputs, keys);
        }
        return keys;
    }

    public void observe(int lane, List<ResourceProductionRecipe.Output> outputs, ResourceProductionSnapshot.State state, long tick) {
        observe(lane, keys(outputs), state, tick);
    }

    public void route(List<ResourceProductionRecipe.Output> outputs, boolean blocked) {
        route(keys(outputs), blocked);
    }

    public void route(Collection<ResourceKey> keys, boolean blocked) {
        long bucket = Math.floorDiv(clock.getAsLong(), BUCKET_MILLIS);
        makeRoom(keys, bucket);
        for (var key : keys) {
            var value = sample(key, bucket);
            if (value != null) {
                value.waitingOutput = blocked;
            }
        }
    }

    /** 调用方只在正式提交成功之后传入同一份冻结结果；重复输出和库存搬运不调用此入口。 */
    public void record(Map<ResourceKey, BigInteger> produced) {
        long bucket = Math.floorDiv(clock.getAsLong(), BUCKET_MILLIS);
        int index = (int) Math.floorMod(bucket, BUCKETS);
        makeRoom(produced.keySet(), bucket);
        for (var entry : produced.entrySet()) {
            if (entry.getValue().signum() <= 0) {
                continue;
            }
            var value = sample(entry.getKey(), bucket);
            if (value == null) {
                continue;
            }
            if (value.buckets[index] != bucket) {
                value.buckets[index] = bucket;
                value.amounts[index] = BigInteger.ZERO;
            }
            // 显示预算饱和不会影响已经提交的权威资源数量。
            value.amounts[index] = value.amounts[index].add(entry.getValue()).min(ResourceExactAmounts.MAX_VALUE);
        }
    }

    public ResourceProductionSnapshot snapshot(long tick) {
        long bucket = Math.floorDiv(clock.getAsLong(), BUCKET_MILLIS);
        latestTick = tick;
        var states = activeStates(tick, bucket);
        retireExpired(bucket, states.keySet());
        var rows = new ArrayList<ResourceProductionSnapshot.Entry>(samples.size());
        for (var entry : samples.entrySet()) {
            var value = entry.getValue();
            BigInteger produced = BigInteger.ZERO;
            for (int i = 0; i < BUCKETS; i++) {
                if (value.buckets[i] != Long.MIN_VALUE && withinWindow(value.buckets[i], bucket)) {
                    produced = produced.add(value.amounts[i]).min(ResourceExactAmounts.MAX_VALUE);
                }
            }
            var state = states.getOrDefault(entry.getKey(), value.waitingOutput
                    ? ResourceProductionSnapshot.State.WAITING_OUTPUT : ResourceProductionSnapshot.State.RECENT);
            if (state != ResourceProductionSnapshot.State.PRODUCING && value.waitingOutput) {
                state = ResourceProductionSnapshot.State.WAITING_OUTPUT;
            }
            if (state == ResourceProductionSnapshot.State.WAITING_WORK && produced.signum() > 0) {
                state = ResourceProductionSnapshot.State.PRODUCING;
            }
            rows.add(new ResourceProductionSnapshot.Entry(entry.getKey(), produced, state));
        }
        return new ResourceProductionSnapshot(BUCKET_MILLIS * BUCKETS, rows);
    }
}
