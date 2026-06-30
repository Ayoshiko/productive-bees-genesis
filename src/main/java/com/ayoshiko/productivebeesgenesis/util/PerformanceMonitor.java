package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.StandardMBean;

/**
 * 性能监控器
 * <br/>
 * 兼容Spark profiler的tick时间统计，通过JMX暴露MBean供外部工具查询。
 * 使用线程安全的原子类型保证并发安全。
 */
public final class PerformanceMonitor {

    private static final PerformanceMonitor INSTANCE = new PerformanceMonitor();

    // tick时间统计
    private final AtomicLong totalTickTimeNanos = new AtomicLong();
    private final AtomicInteger tickCount = new AtomicInteger();
    private final AtomicLong maxTickTimeNanos = new AtomicLong();

    // 配方查找统计
    private final AtomicLong recipeLookupTimeNanos = new AtomicLong();
    private final AtomicInteger recipeLookupCount = new AtomicInteger();
    private final AtomicInteger cacheHitCount = new AtomicInteger();

    // 能量统计
    private final AtomicLong totalEnergyConsumed = new AtomicLong();

    // 每方块实体统计（LRU淘汰避免内存泄漏，synchronized保证线程安全）
    private final LinkedHashMap<String, BlockEntityStats> blockEntityStats = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BlockEntityStats> eldest) {
            return size() > 1024;
        }
    };

    private PerformanceMonitor() {}

    public static PerformanceMonitor getInstance() { return INSTANCE; }

    /**
     * 检查性能监控是否启用
     * <br/>
     * 直接读取 {@link ModConfig#COMMON} 的配置项，默认关闭时所有 recordXxx 调用前的判断立即返回 false，
     * 不产生 System.nanoTime 等额外开销。COMMON 在配置加载前为 null，加 null 检查避免 NPE。
     */
    public static boolean isEnabled() {
        return ModConfig.COMMON != null && ModConfig.COMMON.enablePerformanceMonitor.get();
    }

    /** 记录tick耗时 */
    public void recordTickTime(long nanos) {
        totalTickTimeNanos.addAndGet(nanos);
        tickCount.incrementAndGet();
        maxTickTimeNanos.updateAndGet(current -> Math.max(current, nanos));
    }

    /** 记录配方查找耗时 */
    public void recordRecipeLookup(long nanos, boolean cacheHit) {
        recipeLookupTimeNanos.addAndGet(nanos);
        recipeLookupCount.incrementAndGet();
        if (cacheHit) cacheHitCount.incrementAndGet();
    }

    /** 记录能量消耗 */
    public void recordEnergyConsumed(long energy) {
        totalEnergyConsumed.addAndGet(energy);
    }

    /** 记录方块实体tick统计（synchronized保证LRU Map的线程安全） */
    public void recordBlockEntityTick(String blockPosKey, long nanos) {
        synchronized (blockEntityStats) {
            blockEntityStats.computeIfAbsent(blockPosKey, k -> new BlockEntityStats())
                    .recordTick(nanos);
        }
    }

    /** 平均tick时间（毫秒） */
    public double getAverageTickTimeMs() {
        int ticks = tickCount.get();
        return ticks > 0 ? totalTickTimeNanos.get() / (double) ticks / 1_000_000 : 0;
    }

    /** 最大tick时间（毫秒） */
    public double getMaxTickTimeMs() {
        return maxTickTimeNanos.get() / 1_000_000.0;
    }

    /** 缓存命中率 */
    public double getCacheHitRate() {
        int total = recipeLookupCount.get();
        return total > 0 ? cacheHitCount.get() / (double) total : 0;
    }

    /** 平均配方查找时间（微秒） */
    public double getAverageRecipeLookupUs() {
        int count = recipeLookupCount.get();
        return count > 0 ? recipeLookupTimeNanos.get() / (double) count / 1_000 : 0;
    }

    public long getTotalEnergyConsumed() { return totalEnergyConsumed.get(); }
    public int getTickCount() { return tickCount.get(); }

    /** 重置所有统计 */
    public void reset() {
        totalTickTimeNanos.set(0);
        tickCount.set(0);
        maxTickTimeNanos.set(0);
        recipeLookupTimeNanos.set(0);
        recipeLookupCount.set(0);
        cacheHitCount.set(0);
        totalEnergyConsumed.set(0);
        synchronized (blockEntityStats) {
            blockEntityStats.clear();
        }
    }

    /** 注册JMX MBean */
    public void registerJMX() {
        try {
            var mbs = ManagementFactory.getPlatformMBeanServer();
            var name = new javax.management.ObjectName("productivebeesgenesis:type=PerformanceMonitor");
            if (!mbs.isRegistered(name)) {
                // 使用 StandardMBean 显式绑定接口，避免实现类名必须遵循 "接口名去 MBean 后缀" 的命名约定
                var mbean = new StandardMBean(new PerformanceMonitorMBeanImpl(this), PerformanceMonitorMBean.class);
                mbs.registerMBean(mbean, name);
                ProductiveBeesGenesis.LOGGER.info("性能监控JMX MBean注册成功");
            }
        } catch (Exception e) {
            // JMX注册失败不影响功能，但记录警告便于排障
            ProductiveBeesGenesis.LOGGER.warn("性能监控JMX MBean注册失败", e);
        }
    }

    /** JMX MBean接口 */
    public interface PerformanceMonitorMBean {
        double getAverageTickTimeMs();
        double getMaxTickTimeMs();
        double getCacheHitRate();
        double getAverageRecipeLookupUs();
        long getTotalEnergyConsumed();
        int getTickCount();
    }

    /** JMX MBean实现 */
    private static class PerformanceMonitorMBeanImpl implements PerformanceMonitorMBean {
        private final PerformanceMonitor monitor;

        PerformanceMonitorMBeanImpl(PerformanceMonitor monitor) {
            this.monitor = monitor;
        }

        @Override public double getAverageTickTimeMs() { return monitor.getAverageTickTimeMs(); }
        @Override public double getMaxTickTimeMs() { return monitor.getMaxTickTimeMs(); }
        @Override public double getCacheHitRate() { return monitor.getCacheHitRate(); }
        @Override public double getAverageRecipeLookupUs() { return monitor.getAverageRecipeLookupUs(); }
        @Override public long getTotalEnergyConsumed() { return monitor.getTotalEnergyConsumed(); }
        @Override public int getTickCount() { return monitor.getTickCount(); }
    }

    /** 方块实体统计 */
    public static class BlockEntityStats {
        private final AtomicLong tickTime = new AtomicLong();
        private final AtomicInteger processCount = new AtomicInteger();

        public void recordTick(long nanos) {
            tickTime.addAndGet(nanos);
            processCount.incrementAndGet();
        }

        public long getTickTime() { return tickTime.get(); }
        public int getProcessCount() { return processCount.get(); }
        public double getAverageTickMs() {
            int count = processCount.get();
            return count > 0 ? tickTime.get() / (double) count / 1_000_000 : 0;
        }
    }
}
