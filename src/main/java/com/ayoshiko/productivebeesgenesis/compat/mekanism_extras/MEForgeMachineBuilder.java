package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import java.util.function.Supplier;
import com.ayoshiko.productivebeesgenesis.util.ForgeMachineBuilder;
import com.jerry.mekanism_extras.common.content.blocktype.ExtraMachine;
import mekanism.api.text.ILangEntry;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;

public final class MEForgeMachineBuilder {
    private MEForgeMachineBuilder() {}
    public static <T extends TileEntityMekanism> ForgeMachineBuilder<ExtraMachine.ExtraFactoryMachine<T>, T> createExtraFactoryMachine(
            Supplier<TileEntityTypeRegistryObject<T>> tile, ILangEntry description, FactoryType factory) {
        var machine = ExtraMachine.ExtraMachineBuilder.createExtraFactoryMachine(tile, null, factory).build();
        ((com.ayoshiko.productivebeesgenesis.mixin.accessor.BlockTypeDescriptionAccessor) machine).productivebeesgenesis$setDescription(description);
        return new ForgeMachineBuilder<>(machine);
    }
}
