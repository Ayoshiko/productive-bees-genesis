package com.ayoshiko.productivebeesgenesis.mek.ae2;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** 同单位 J 容量和批次缺口，避免 long 加法溢出。 */
final class Ae2EnergyMath {
    private static final BigDecimal MAX_LONG = BigDecimal.valueOf(Long.MAX_VALUE);

    private Ae2EnergyMath() {
    }

    static long requestedFe(long shortfallJoules, BigDecimal retainedJoules, double conversion) {
        if (!Double.isFinite(conversion) || conversion <= 0) {
            throw new IllegalStateException("Invalid Mekanism FE conversion: " + conversion);
        }
        if (shortfallJoules <= 0) return 0;
        double doubled = conversion * 2.0;
        if (retainedJoules.signum() == 0 && conversion >= 1.0
                && doubled <= Integer.MAX_VALUE && doubled == Math.rint(doubled)) {
            // 整数或半整数比率可精确拆分商余数，避免先乘二溢出或转 double 丢低位。
            long divisor = (long) doubled;
            long quotient = shortfallJoules / divisor;
            long remainder = shortfallJoules % divisor;
            long remainderRequest = (remainder * 2L + divisor - 1L) / divisor;
            return quotient * 2L + remainderRequest;
        }
        BigDecimal available = BigDecimal.valueOf(shortfallJoules).subtract(retainedJoules);
        if (available.signum() <= 0) return 0;
        // FE 以整数提取；向下取整会使不足一个 FE 的 J 缺口永远无法补齐。
        return available.divide(BigDecimal.valueOf(conversion), 0, RoundingMode.UP)
                .min(MAX_LONG).longValueExact();
    }

    static long remainingCapacity(long currentEnergy, long maxEnergy) {
        if (maxEnergy <= 0L) return 0L;
        long current = Math.max(0L, currentEnergy);
        return current >= maxEnergy ? 0L : maxEnergy - current;
    }

    static long requiredShortfall(long currentEnergy, long requiredEnergy, long remainingCapacity) {
        long current = Math.max(0L, currentEnergy);
        long required = Math.max(0L, requiredEnergy);
        long remaining = Math.max(0L, remainingCapacity);
        if (current >= required || remaining == 0L) return 0L;
        return Math.min(remaining, required - current);
    }
}
