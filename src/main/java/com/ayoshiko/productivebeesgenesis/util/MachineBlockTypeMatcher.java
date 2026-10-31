package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.Predicate;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.ForgeRegistries;

/** Configuration cards in Mekanism 10.4 identify a tile type rather than a block. */
public final class MachineBlockTypeMatcher {
    private MachineBlockTypeMatcher() {}
    public static boolean matches(BlockEntityType<?> type, Predicate<Block> predicate) {
        for (Block block : ForgeRegistries.BLOCKS.getValues()) {
            if (predicate.test(block) && type.isValid(block.defaultBlockState())) return true;
        }
        return false;
    }
}
