package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 持续数据仅携带设置；配置卡与机器物品不复制账户或资源余额。 */
public final class ResourceSidePersistence {
    public static final String KEY = "pbg_native_output";
    private ResourceSidePersistence() {}
    public static boolean machine(BlockEntity host) {
        return host instanceof com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary || ResourceHiveHost.isMekCentrifuge(host);
    }
    public static void write(BlockEntity host, CompoundTag data) {
        if (!machine(host)) return;
        var state = ResourceHiveHost.existing(host);
        if (state != null) data.put(KEY, state.nativeConfigData());
    }
    public static void read(BlockEntity host, CompoundTag data) {
        if (!machine(host) || !data.contains(KEY)) return;
        com.ayoshiko.productivebeesgenesis.port.ForgeResourceHostState.get(host).readNativeConfig(host, data.get(KEY));
    }
}
