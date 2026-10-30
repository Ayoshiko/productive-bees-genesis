package com.ayoshiko.productivebeesgenesis.util;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.math.FloatingLong;
import mekanism.common.util.UnitDisplayUtils.EnergyUnit;

/** Genesis's integer FE budgets at the Mekanism 10.4 Joule boundary. */
public final class ForgeEnergyUnits {
    private ForgeEnergyUnits() {}
    public static long toFe(FloatingLong joules) { return EnergyUnit.FORGE_ENERGY.convertTo(joules).longValue(); }
    public static FloatingLong fromFe(long fe) { return EnergyUnit.FORGE_ENERGY.convertFrom(FloatingLong.create(Math.max(0L, fe))); }
    public static long extractFe(IEnergyContainer container, long amount, Action action, AutomationType automation) {
        return toFe(container.extract(fromFe(amount), action, automation));
    }
}
