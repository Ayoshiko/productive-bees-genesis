package com.ayoshiko.productivebeesgenesis.resource;

import net.neoforged.fml.ModList;

/** 原生接口配置分组；两种闪电仍以各自 ResourceKey 交接。 */
public enum ResourceNativeType {
    CHEMICAL("chemical", "mekanism", null),
    ENERGY("energy", "mekanism", ResourceTypes.FE),
    SOURCE("source", "ars_nouveau", ResourceTypes.SOURCE),
    SOUL("soul", "industrialforegoingsouls", ResourceTypes.SOUL),
    AIR("air", "pneumaticcraft", ResourceTypes.AIR),
    EXPERIENCE("experience", "experiencelib", ResourceTypes.EXPERIENCE),
    LIGHTNING("lightning", "ae2lt", ResourceTypes.HIGH_VOLTAGE);

    private final String id;
    private final String mod;
    private final ResourceKey key;
    ResourceNativeType(String id, String mod, ResourceKey key) {
        this.id = id;
        this.mod = mod;
        this.key = key;
    }
    public String id() { return id; }
    public boolean available() { return ModList.get().isLoaded(mod); }
    public ResourceKey displayKey() { return key; }
    public static ResourceNativeType of(ResourceKey key) {
        if (ResourceTypes.chemicalId(key) != null) return CHEMICAL;
        if (ResourceTypes.FE.equals(key)) return ENERGY;
        if (ResourceTypes.SOURCE.equals(key)) return SOURCE;
        if (ResourceTypes.SOUL.equals(key)) return SOUL;
        if (ResourceTypes.AIR.equals(key)) return AIR;
        if (ResourceTypes.EXPERIENCE.equals(key)) return EXPERIENCE;
        if (ResourceTypes.lightningTier(key) != null) return LIGHTNING;
        return null;
    }
}
