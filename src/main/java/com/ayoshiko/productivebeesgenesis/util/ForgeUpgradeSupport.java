package com.ayoshiko.productivebeesgenesis.util;

import java.util.Collections;
import java.util.EnumSet;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeUpgradeSupport;

public final class ForgeUpgradeSupport {
    private ForgeUpgradeSupport() {}
    public static final AttributeUpgradeSupport DEFAULT_MACHINE_UPGRADES = create(Upgrade.SPEED, Upgrade.ENERGY, Upgrade.MUFFLING);
    public static AttributeUpgradeSupport create(Upgrade... upgrades) {
        EnumSet<Upgrade> values = EnumSet.noneOf(Upgrade.class);
        Collections.addAll(values, upgrades);
        return new AttributeUpgradeSupport(values);
    }
}
