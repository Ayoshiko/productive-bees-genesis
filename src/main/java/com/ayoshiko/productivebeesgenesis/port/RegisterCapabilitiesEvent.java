package com.ayoshiko.productivebeesgenesis.port;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.*;
import net.minecraftforge.common.capabilities.Capability;
import java.util.function.BiFunction;

public final class RegisterCapabilitiesEvent {
    @SuppressWarnings("unchecked")
    public <T, B extends BlockEntity> void registerBlockEntity(Capability<T> cap, BlockEntityType<B> type, BiFunction<B,Direction,T> provider) {
        ForgeBlockCapabilities.register(cap, (tile, side) -> tile.getType() == type ? provider.apply((B)tile, side) : null);
    }
}
