package com.ayoshiko.productivebeesgenesis.mek.ae2;

/** 同单位 J 容量和批次缺口，避免 long 加法溢出。 */
final class Ae2EnergyMath {
    private Ae2EnergyMath() {
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
