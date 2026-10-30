package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.world.item.Item;
import java.util.function.Supplier;

/** Maps PB 13's upgrade vocabulary to PB 12 items and explicit Genesis backports. */
public final class PbUpgradeItems {
    private PbUpgradeItems() {}
    public static final Supplier<Item> UPGRADE_PRODUCTIVITY = ModItems.UPGRADE_PRODUCTIVITY;
    public static final Supplier<Item> UPGRADE_PRODUCTIVITY_2 = ModItems.UPGRADE_PRODUCTIVITY_2;
    public static final Supplier<Item> UPGRADE_PRODUCTIVITY_3 = ModItems.UPGRADE_PRODUCTIVITY_3;
    public static final Supplier<Item> UPGRADE_PRODUCTIVITY_4 = ModItems.UPGRADE_PRODUCTIVITY_4;
    public static final Supplier<Item> UPGRADE_TIME = ModItems.UPGRADE_TIME;
    public static final Supplier<Item> UPGRADE_BLOCK = ModItems.UPGRADE_COMB_BLOCK;
    public static final Supplier<Item> UPGRADE_GENE_SAMPLER = ModItems.UPGRADE_BEE_SAMPLER;
    public static final Supplier<Item> UPGRADE_SIMULATOR = ModItems.UPGRADE_SIMULATOR;
    public static final Supplier<? extends Item> UPGRADE_TIME_2 = com.ayoshiko.productivebeesgenesis.init.ModItems.TIME_2_UPGRADE;
    public static final Supplier<? extends Item> UPGRADE_STABILITY = com.ayoshiko.productivebeesgenesis.init.ModItems.STABILITY_UPGRADE;
}
