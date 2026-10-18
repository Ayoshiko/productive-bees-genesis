package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import mekanism.api.RelativeSide;
import mekanism.api.energy.IEnergyContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentConfig;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import mekanism.common.tile.interfaces.ISideConfiguration;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 机械宿主共用 ENERGY 配置：工作电池输入与 FE 账户输出各自拥有容器。 */
public final class ResourceMekEnergyConfig {
    private ResourceMekEnergyConfig() {}

    public static boolean manages(BlockEntity host) {
        return host instanceof TileEntityMekApiary || host instanceof IMekCentrifugeTile;
    }

    public static void setup(TileEntityMekanism host, TileComponentConfig component, IEnergyContainer battery) {
        var config = component.setupIOConfig(TransmissionType.ENERGY, battery,
                new ResourceMekEnergyContainer(host), RelativeSide.FRONT);
        config.setCanEject(true);
        component.addConfigChangeListener(TransmissionType.ENERGY, side -> {
            var state = ResourceHiveHost.existing(host);
            if (state != null && state.outputAccess != null) state.outputAccess.configurationChanged();
        });
    }

    private static ConfigInfo config(BlockEntity host) {
        return host instanceof ISideConfiguration sided ? sided.getConfig().getConfig(TransmissionType.ENERGY) : null;
    }

    public static int mask(BlockEntity host) {
        var config = config(host);
        if (config == null) return 0;
        int mask = 0;
        for (var side : RelativeSide.values()) {
            var mode = config.getDataType(side);
            if (mode == DataType.OUTPUT || mode == DataType.INPUT_OUTPUT) mask |= 1 << side.ordinal();
        }
        return mask;
    }

    public static boolean output(BlockEntity host, Direction side) {
        int mask = mask(host);
        return side == null ? mask != 0
                : (mask & (1 << RelativeSide.fromDirections(ResourceSideConfig.facing(host), side).ordinal())) != 0;
    }

    public static boolean eject(BlockEntity host) {
        var config = config(host);
        return config != null && config.isEjecting();
    }

    static void set(BlockEntity host, int mask, boolean eject) {
        if (mask < 0 || mask > ResourceSideConfig.ALL_SIDES) throw new IllegalArgumentException("Invalid energy faces");
        var config = config(host);
        if (config == null) return;
        var component = ((ISideConfiguration) host).getConfig();
        for (var side : RelativeSide.values()) {
            var old = config.getDataType(side);
            var mode = old;
            if ((mask & (1 << side.ordinal())) != 0) mode = DataType.OUTPUT;
            else if (old == DataType.OUTPUT || old == DataType.INPUT_OUTPUT) mode = DataType.INPUT;
            if (config.setDataType(mode, side) && host.getLevel() != null) {
                component.sideChanged(TransmissionType.ENERGY, side);
            }
        }
        config.setEjecting(eject);
        host.setChanged();
        host.invalidateCapabilities();
    }
}
