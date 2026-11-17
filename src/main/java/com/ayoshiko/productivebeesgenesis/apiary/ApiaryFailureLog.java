package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.core.BlockPos;

/** 各实际失败范围保留首因；日志期限只限制汇总，不改变生产与恢复尝试。 */
record ApiaryFailureLog(Object source, String phase, int index, Throwable cause, long nextSummaryNanos) {
    private static final long SUMMARY_INTERVAL_NANOS = 60_000_000_000L;

    static ApiaryFailureLog record(BlockPos position, ApiaryFailureLog previous, Object source,
            String phase, int index, Throwable error) {
        long now = System.nanoTime();
        if (previous != null && previous.source() == source) {
            if (now - previous.nextSummaryNanos() < 0) return previous;
            LogThrottle.warnWithCooldown("apiary_failure_summary", 60_000L,
                    "蜂箱故障持续，位置 {}，阶段 {}，槽／组 {}，首根因 {}；同类持续故障合并报告",
                    position, previous.phase(), previous.index(), previous.cause().toString());
            return new ApiaryFailureLog(previous.source(), previous.phase(), previous.index(), previous.cause(),
                    now + SUMMARY_INTERVAL_NANOS);
        }
        ApiaryFailureLog episode = new ApiaryFailureLog(source, phase, index, error, now + SUMMARY_INTERVAL_NANOS);
        LogThrottle.errorWithCooldown("apiary_failure_first", 60_000L,
                "蜂箱首次故障，位置 {}，阶段 {}，槽／组 {}，原身份 {}；各范围保留首因，同类故障合并报告",
                position, phase, index, source, error);
        return episode;
    }

    static void recovered(BlockPos position, ApiaryFailureLog episode) {
        if (episode == null) return;
        LogThrottle.infoWithCooldown("apiary_failure_recovered", 60_000L,
                "蜂箱故障已恢复，位置 {}，阶段 {}，槽／组 {}，原根因 {}；同类恢复合并报告",
                position, episode.phase(), episode.index(), episode.cause().toString());
    }
}
