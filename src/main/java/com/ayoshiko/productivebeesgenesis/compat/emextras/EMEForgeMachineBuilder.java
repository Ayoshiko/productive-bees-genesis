package com.ayoshiko.productivebeesgenesis.compat.emextras;

import java.util.function.Supplier;
import com.ayoshiko.productivebeesgenesis.util.ForgeMachineBuilder;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraFactoryType;
import mekanism.api.text.ILangEntry;
import mekanism.common.registration.impl.TileEntityTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;

public final class EMEForgeMachineBuilder {
    private EMEForgeMachineBuilder() {}
    public static <T extends TileEntityMekanism> ForgeMachineBuilder<EMExtraMachine.EMExtraFactoryMachine<T>, T> createEMExtraFactoryMachine(
            Supplier<TileEntityTypeRegistryObject<T>> tile, ILangEntry description, EMExtraFactoryType factory) {
        var machine = EMExtraMachine.EMExtraMachineBuilder.createEMExtraFactoryMachine(tile, null, factory).build();
        ((com.ayoshiko.productivebeesgenesis.mixin.accessor.BlockTypeDescriptionAccessor) machine).productivebeesgenesis$setDescription(description);
        return new ForgeMachineBuilder<>(machine);
    }
}
