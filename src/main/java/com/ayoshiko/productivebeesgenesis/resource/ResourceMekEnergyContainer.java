package com.ayoshiko.productivebeesgenesis.resource;

import mekanism.api.Action;
import mekanism.api.math.FloatingLong;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.energy.IStrictEnergyHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** ENERGY 输出槽的账户视图；不加入机器的持久化容器列表，不拥有第二份余额。 */
final class ResourceMekEnergyContainer implements IEnergyContainer {
    private final BlockEntity host;

    ResourceMekEnergyContainer(BlockEntity host) {
        this.host = host;
    }

    private IStrictEnergyHandler output() {
        var access = ResourceOutputAccess.find(host);
        return access == null ? null : access.strictEnergy();
    }

    @Override
    public FloatingLong getEnergy() {
        var output = output();
        return output == null ? FloatingLong.ZERO : output.getEnergy(0);
    }

    @Override
    public FloatingLong getMaxEnergy() {
        var output = output();
        return output == null ? FloatingLong.ZERO : output.getMaxEnergy(0);
    }

    @Override
    public FloatingLong getNeeded() {
        return FloatingLong.ZERO;
    }

    @Override
    public FloatingLong insert(FloatingLong amount, Action action, AutomationType automationType) {
        return amount;
    }

    @Override
    public FloatingLong extract(FloatingLong amount, Action action, AutomationType automationType) {
        var output = output();
        return output == null ? FloatingLong.ZERO : output.extractEnergy(0, amount, action);
    }

    @Override
    public void setEnergy(FloatingLong amount) {
        // 任意赋值不能创建、清空或复制账户资产，提取必须走账户事务。
    }

    @Override
    public void onContentsChanged() {
        // 账户提交负责标记持久化；这个视图没有独立内容。
    }

    @Override
    public CompoundTag serializeNBT() {
        return new CompoundTag();
    }

    @Override
    public void deserializeNBT(CompoundTag tag) {
        // 容器不保存余额，不能从机器或配置卡恢复第二份资源。
    }
}
