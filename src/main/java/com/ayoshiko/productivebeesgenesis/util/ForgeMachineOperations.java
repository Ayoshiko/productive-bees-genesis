package com.ayoshiko.productivebeesgenesis.util;

import mekanism.api.Upgrade;
import mekanism.common.config.MekanismConfig;
import mekanism.common.tile.interfaces.IUpgradeTile;
import mekanism.common.util.MekanismUtils;

/** Backports Mekanism 1.21's sub-tick operation formula using the 10.4 upgrade hooks. */
public final class ForgeMachineOperations {
    private ForgeMachineOperations() {}
    public static double getTicksD(IUpgradeTile tile, int baseTicks) {
        return baseTicks * Math.pow(MekanismConfig.general.maxUpgradeMultiplier.get(),
                -MekanismUtils.fractionUpgrades(tile, Upgrade.SPEED));
    }
    public static int getOperationsPerTick(IUpgradeTile tile, int baseTicks, int defaultOperations) {
        double ticks = getTicksD(tile, baseTicks);
        if (ticks >= 1) return defaultOperations;
        return mekanism.api.math.MathUtils.clampToInt(Math.max(1, 1 / ticks) * defaultOperations);
    }
}
