package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class CentrifugeFluidPersistenceMinecraftTest {
    private static final RegistryAccess REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @BeforeAll
    static void config() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @Test
    void normalSaveAndLegacyDuplicateRestoreExactlyOneCompleteFluidStack() {
        var source = machine();
        int amount = source.getFluidOutputTank().getCapacity() / 3;
        assertTrue(amount > 0);
        var fluid = new FluidStack(Fluids.WATER, amount);
        fluid.set(DataComponents.CUSTOM_NAME, Component.literal("preserved fluid component"));
        source.getFluidOutputTank().setStack(fluid.copy());
        assertEquals(amount, source.getFluidOutputTank().getFluidAmount());

        var saved = new CompoundTag();
        source.saveAdditional(saved, REGISTRIES);
        assertTrue(saved.contains("fluid_tanks"));
        assertFalse(saved.contains(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID));
        var restored = machine();
        restored.loadAdditional(saved, REGISTRIES);
        assertFluid(fluid, restored);
        restored.loadAdditional(saved, REGISTRIES);
        assertFluid(fluid, restored);

        var legacyDuplicate = saved.copy();
        var legacy = new CompoundTag();
        legacy.put("Fluid", fluid.save(REGISTRIES));
        legacyDuplicate.put(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID, legacy);
        var fromOldVersion = machine();
        fromOldVersion.loadAdditional(legacyDuplicate, REGISTRIES);
        assertFluid(fluid, fromOldVersion);
        var migrated = new CompoundTag();
        fromOldVersion.saveAdditional(migrated, REGISTRIES);
        assertFalse(migrated.contains(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID));
    }

    @Test
    void legacySingleTankUpgradeRestoresCompleteFluidButMalformedCountsStayHeld() {
        var expected = new FluidStack(Fluids.WATER, 73);
        expected.set(DataComponents.CUSTOM_NAME, Component.literal("legacy upgrade fluid"));
        var entry = new CompoundTag();
        entry.put("fluidStack", expected.save(REGISTRIES));
        var entries = new net.minecraft.nbt.ListTag();
        entries.add(entry);
        var legacy = new CompoundTag();
        legacy.put("tanks", entries);
        var root = new CompoundTag();
        root.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, legacy);
        var holder = new com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder(
                3, 1000, () -> {}, 0, () -> 0L);
        holder.readFromNBT(root, REGISTRIES);
        assertFalse(holder.hasUnresolvedSavedData());
        assertEquals(73, holder.getPrimaryTank().getFluidAmount());
        assertTrue(FluidStack.isSameFluidSameComponents(expected, holder.getPrimaryTank().getFluid()));
        holder.readFromNBT(root, REGISTRIES);
        assertEquals(73, holder.getPrimaryTank().getFluidAmount());

        var wrongCount = legacy.copy();
        wrongCount.putString("count", "1");
        assertThrows(IllegalArgumentException.class,
                () -> com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankNbtCodec.decode(wrongCount, REGISTRIES));
        var multiple = legacy.copy();
        multiple.getList("tanks", net.minecraft.nbt.Tag.TAG_COMPOUND).add(entry.copy());
        assertThrows(IllegalArgumentException.class,
                () -> com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankNbtCodec.decode(multiple, REGISTRIES));
    }

    private static TileEntityMekCentrifuge machine() {
        var block = ModBlocks.MEK_CENTRIFUGE.get();
        return (TileEntityMekCentrifuge) block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
    }

    private static void assertFluid(FluidStack expected, TileEntityMekCentrifuge machine) {
        var actual = machine.getFluidOutputTank().getFluid();
        assertEquals(expected.getAmount(), actual.getAmount());
        assertTrue(FluidStack.isSameFluidSameComponents(expected, actual));
    }
}
