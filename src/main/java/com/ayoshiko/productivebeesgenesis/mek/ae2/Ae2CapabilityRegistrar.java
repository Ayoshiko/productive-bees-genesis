package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.storage.MEStorage;
import appeng.capabilities.Capabilities;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.EnumMap;

/** Forge providers follow each block entity's lifecycle, including chunk unload. */
public final class Ae2CapabilityRegistrar {
    private Ae2CapabilityRegistrar() {}
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        BlockEntity tile = event.getObject();
        if (!(tile instanceof IAe2OutputHostBase host) || !(tile instanceof IInWorldGridNodeHost gridHost)) return;
        Provider provider = new Provider(tile, host, gridHost);
        event.addCapability(new ResourceLocation(ProductiveBeesGenesis.MOD_ID, "ae2"), provider);
        event.addListener(provider::invalidate);
    }
    private static final class Provider implements ICapabilityProvider {
        private final BlockEntity tile;
        private final IAe2OutputHostBase host;
        private final LazyOptional<IInWorldGridNodeHost> grid;
        private final EnumMap<Direction, LazyOptional<MEStorage>> storage = new EnumMap<>(Direction.class);
        private boolean valid = true;
        Provider(BlockEntity tile, IAe2OutputHostBase host, IInWorldGridNodeHost gridHost) {
            this.tile = tile;
            this.host = host;
            this.grid = LazyOptional.of(() -> gridHost);
        }
        @Override
        public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> capability, @Nullable Direction side) {
            if (!valid) return LazyOptional.empty();
            if (capability == Capabilities.IN_WORLD_GRID_NODE_HOST) return grid.cast();
            if (capability == Capabilities.STORAGE && side != null && tile instanceof IMekCentrifugeTile centrifuge
                    && tile instanceof ISideConfiguration configuration) {
                return storage.computeIfAbsent(side, direction -> LazyOptional.of(() ->
                    CentrifugeExternalAeStorage.getOrCreate(host, centrifuge, configuration, direction))).cast();
            }
            return LazyOptional.empty();
        }
        void invalidate() {
            valid = false;
            grid.invalidate();
            storage.values().forEach(LazyOptional::invalidate);
            storage.clear();
        }
    }
}
