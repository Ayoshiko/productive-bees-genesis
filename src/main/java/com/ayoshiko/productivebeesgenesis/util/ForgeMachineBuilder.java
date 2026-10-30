package com.ayoshiko.productivebeesgenesis.util;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import mekanism.api.text.ILangEntry;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;

/** Keeps custom descriptions and FE configuration on Mekanism's 10.4 builders. */
public class ForgeMachineBuilder<M extends BlockTypeTile<T>, T extends TileEntityMekanism>
        extends BlockTypeTile.BlockTileBuilder<M, T, ForgeMachineBuilder<M, T>> {
    public ForgeMachineBuilder(M machine) { super(machine); }
    /** In 10.4 the tile's TileComponentConfig supplies side capabilities and persistence. */
    public ForgeMachineBuilder<M, T> withSideConfig(mekanism.common.lib.transmitter.TransmissionType... types) { return this; }
    public ForgeMachineBuilder<M, T> withEnergyConfig(LongSupplier usage, LongSupplier storage) {
        super.withEnergyConfig(() -> ForgeEnergyUnits.fromFe(usage.getAsLong()),
                () -> ForgeEnergyUnits.fromFe(storage.getAsLong()));
        return this;
    }
    public static <T extends TileEntityMekanism> ForgeMachineBuilder<Machine<T>, T> createMachine(
            Supplier<TileEntityTypeRegistryObject<T>> tile, ILangEntry description) {
        return new ForgeMachineBuilder<>(new Machine<T>(tile, null) {
            @Override public ILangEntry getDescription() { return description; }
        });
    }
    public static <T extends TileEntityMekanism> ForgeMachineBuilder<Machine.FactoryMachine<T>, T> createFactoryMachine(
            Supplier<TileEntityTypeRegistryObject<T>> tile, ILangEntry description, FactoryType factory) {
        return new ForgeMachineBuilder<>(new Machine.FactoryMachine<T>(tile, null, factory) {
            @Override public ILangEntry getDescription() { return description; }
        });
    }
}
