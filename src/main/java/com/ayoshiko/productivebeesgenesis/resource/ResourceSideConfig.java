package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.RelativeSide;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.attribute.AttributeStateFacing;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Arrays;

/** 宿主所有的原生输出设置；只保存配置，不拥有产物，坏数据由宿主保留原文。 */
public final class ResourceSideConfig {
    public static final int ALL_SIDES = 63;
    private final int[] outputs = new int[ResourceNativeType.values().length];
    private int eject;
    private long revision = 1;
    private boolean energyMigrated = true;
    public ResourceSideConfig() {
        Arrays.fill(outputs, ALL_SIDES);
        outputs[ResourceNativeType.ENERGY.ordinal()] = 0;
        eject = 1 << ResourceNativeType.AIR.ordinal();
    }
    public long revision() { return revision; }
    boolean energyMigrationPending() { return !energyMigrated; }
    void energyMigrationComplete() { energyMigrated = true; }
    public int mask(ResourceNativeType type) { return outputs[type.ordinal()]; }
    public boolean eject(ResourceNativeType type) { return (eject & (1 << type.ordinal())) != 0; }
    public boolean isDefault() {
        return eject == (1 << ResourceNativeType.AIR.ordinal())
                && Arrays.equals(outputs, new ResourceSideConfig().outputs);
    }
    public static Direction facing(BlockEntity host) {
        var attribute = Attribute.get(host.getBlockState(), AttributeStateFacing.class);
        return attribute == null ? Direction.NORTH : attribute.getDirection(host.getBlockState());
    }
    public boolean output(BlockEntity host, ResourceNativeType type, Direction side) {
        return side == null ? mask(type) != 0
                : (mask(type) & (1 << RelativeSide.fromDirections(facing(host), side).ordinal())) != 0;
    }
    public boolean set(ResourceNativeType type, int mask, boolean auto) {
        if (mask < 0 || mask > ALL_SIDES) throw new IllegalArgumentException("Invalid native output faces");
        int nextEject = auto ? eject | (1 << type.ordinal()) : eject & ~(1 << type.ordinal());
        if (outputs[type.ordinal()] == mask && eject == nextEject) return false;
        long nextRevision = Math.incrementExact(revision);
        outputs[type.ordinal()] = mask;
        eject = nextEject;
        revision = nextRevision;
        return true;
    }
    public CompoundTag save() {
        var tag = new CompoundTag();
        tag.putInt("version", energyMigrated ? 2 : 1);
        tag.putLong("revision", revision);
        tag.putIntArray("outputs", outputs);
        tag.putInt("eject", eject);
        return tag;
    }
    public static ResourceSideConfig load(CompoundTag tag) {
        ResourceAccountData.keys(tag, java.util.Set.of("version", "revision", "outputs", "eject"));
        if (!tag.contains("version", Tag.TAG_INT) || (tag.getInt("version") != 1 && tag.getInt("version") != 2)
                || !tag.contains("revision", Tag.TAG_LONG) || tag.getLong("revision") < 1
                || !tag.contains("outputs", Tag.TAG_INT_ARRAY) || !tag.contains("eject", Tag.TAG_INT)) {
            throw new IllegalArgumentException("Invalid native resource side configuration");
        }
        var values = tag.getIntArray("outputs");
        int flags = tag.getInt("eject");
        if (values.length != ResourceNativeType.values().length || flags < 0 || flags >= (1 << values.length)) {
            throw new IllegalArgumentException("Native resource configuration exceeds budget");
        }
        for (int value : values) if (value < 0 || value > ALL_SIDES) throw new IllegalArgumentException("Invalid native resource mask");
        var result = new ResourceSideConfig();
        System.arraycopy(values, 0, result.outputs, 0, values.length);
        result.eject = flags;
        result.revision = tag.getLong("revision");
        result.energyMigrated = tag.getInt("version") == 2;
        return result;
    }
}
