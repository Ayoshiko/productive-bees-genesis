package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.util.EssenceConversionUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.RawOreSmeltingUpgradeHelper;
import com.ayoshiko.productivebeesgenesis.util.UselessByproductUpgradeHelper;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/** PB 12 has no CollectValidUpgradesEvent; its handler mixin calls this predicate. */
public final class CollectValidUpgradesEventHandler {
    private CollectValidUpgradesEventHandler() {}

    /** null leaves PB's own upgrade whitelist intact. */
    @Nullable public static Boolean nativeUpgradeValidity(BlockEntity machine, ItemStack stack) {
        boolean hive = machine instanceof AdvancedBeehiveBlockEntity;
        if (!hive && !(machine instanceof CentrifugeBlockEntity)) return null;
        if (stack.is(ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get()))
            return !UselessByproductUpgradeHelper.hasUpgrade(machine);
        if (stack.is(ModItems.ESSENCE_CONVERSION_UPGRADE.get()))
            return !EssenceConversionUpgradeHelper.hasUpgrade(machine);
        if (stack.is(ModItems.RAW_ORE_SMELTING_UPGRADE.get()))
            return !hive && !RawOreSmeltingUpgradeHelper.hasUpgrade(machine);
        return null;
    }
}
