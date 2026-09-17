package com.ayoshiko.productivebeesgenesis.baseline;

import appeng.api.networking.GridHelper;
import appeng.api.networking.IManagedGridNode;
import appeng.api.storage.IStorageProvider;
import appeng.blockentity.networking.CreativeEnergyCellBlockEntity;
import appeng.core.definitions.AEBlocks;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.Upgrade;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

final class D01Scene {
    final String name;
    private final ServerLevel level;
    private final boolean highIo;
    private final List<TileEntityMekApiary> apiaries = new ArrayList<>();
    private final List<PbRecipeContext> centrifuges = new ArrayList<>();
    private final List<BlockPos> positions = new ArrayList<>();
    private final List<CreativeEnergyCellBlockEntity> power = new ArrayList<>();
    private final List<D01Storage> storages = new ArrayList<>();
    private final List<IStorageProvider> providers = new ArrayList<>();
    private final List<PbRecipeContext> machines = new ArrayList<>();
    private long energyUsed;

    D01Scene(ServerLevel level, boolean highIo) {
        this.level = level;
        this.highIo = highIo;
        name = highIo ? "io-64-ultimate-pairs" : "normal-16-base-pairs";
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, level.getServer());
        level.setDayTime(6_000);
        level.setWeatherParameters(100_000, 0, false, false);
        for (int index = 0; index < (highIo ? 64 : 16); index++) {
            BlockPos base = new BlockPos((index % 8) * 8, 80, (index / 8) * 8);
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            String prefix = highIo ? "ultimate_" : "";
            String suffix = highIo ? "_factory" : "";
            TileEntityMekApiary hive = (TileEntityMekApiary) place(base, "productivebeesgenesis:" + prefix + "mek_apiary" + suffix);
            BlockEntity centrifuge = place(base.offset(highIo ? 2 : 1, 0, 0),
                    "productivebeesgenesis:" + prefix + "mek_centrifuge" + suffix);
            configure(hive);
            if (highIo) {
                ((TileEntityMekanism) centrifuge).getComponent().addUpgrades(Upgrade.SPEED, 8);
                ((TileEntityMekanism) centrifuge).getComponent().addUpgrades(Upgrade.ENERGY, 8);
                ((IAe2InputHost) centrifuge).productivebeesgenesis$setAeItemInputEnabled(true);
                level.setBlockAndUpdate(base.offset(4, 0, 0), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                positions.add(base.offset(4, 0, 0));
                power.add((CreativeEnergyCellBlockEntity) level.getBlockEntity(base.offset(4, 0, 0)));
            }
            apiaries.add(hive);
            centrifuges.add((PbRecipeContext) centrifuge);
            machines.add(hive);
            machines.add((PbRecipeContext) centrifuge);
        }
    }

    private BlockEntity place(BlockPos pos, String id) {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
        if (block == Blocks.AIR) throw new IllegalStateException("Missing baseline block " + id);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        positions.add(pos);
        BlockEntity tile = level.getBlockEntity(pos);
        if (tile == null) throw new IllegalStateException("Missing baseline block entity " + id);
        return tile;
    }

    private void configure(TileEntityMekApiary hive) {
        CompoundTag data = new CompoundTag();
        data.putString("id", "productivebees:configurable_bee");
        data.putString("type", "productivebees:iron");
        CompoundTag traits = new CompoundTag();
        traits.putString("bee_productivity", highIo ? "productivity.very_high" : "productivity.normal");
        traits.putString("bee_behavior", "behavior.metaturnal");
        traits.putString("bee_weather_tolerance", "weather_tolerance.any");
        CompoundTag attachments = new CompoundTag();
        attachments.put("productivebees:attributes_handler", traits);
        data.put("neoforge:attachments", attachments);
        for (var bee : hive.getBeeSlots()) {
            bee.setBeeData(data.copy());
            bee.setBaseMinOccupationTicks(1_200);
            bee.setHasNectar(true);
        }
        hive.getFeederSlots().getFirst().setStack(new ItemStack(Items.IRON_BLOCK));
        hive.setDirectEjectEnabled(!highIo);
        hive.setDirectContainerOutputEnabled(!highIo);
        hive.setDirectAeOutputEnabled(highIo);
        hive.setCentrifugePriorityEnabled(!highIo);
        if (highIo) {
            hive.getComponent().addUpgrades(Upgrade.SPEED, 8);
            hive.getComponent().addUpgrades(Upgrade.ENERGY, 8);
            hive.installPbUpgradeBulk(PbUpgradeType.TIME, 8);
            hive.installPbUpgradeBulk(PbUpgradeType.TIME_2, 8);
        }
        hive.setChanged();
    }

    void connect() {
        if (!highIo) return;
        for (int index = 0; index < apiaries.size(); index++) {
            var root = power.get(index).getMainNode().getNode();
            if (root == null) throw new IllegalStateException("AE power node not ready");
            for (Object machine : List.of(apiaries.get(index), centrifuges.get(index))) {
                IAe2OutputHostBase host = (IAe2OutputHostBase) machine;
                Ae2GridNodeManager.prepareNode(host);
                Ae2GridNodeManager.connectNode(host);
                var node = ((IManagedGridNode) host.productivebeesgenesis$getAe2GridNode()).getNode();
                if (node == null) throw new IllegalStateException("Machine AE node not ready");
                GridHelper.createConnection(root, node);
            }
            D01Storage storage = new D01Storage(level.registryAccess());
            IStorageProvider provider = mounts -> mounts.mount(storage);
            root.getGrid().getStorageService().addGlobalStorageProvider(provider);
            storages.add(storage);
            providers.add(provider);
        }
    }

    void refillEnergy() {
        for (PbRecipeContext machine : machines) {
            var energy = machine.energyContainer();
            energy.setEnergy(energy.getMaxEnergy());
        }
    }

    void measureEnergy() {
        for (PbRecipeContext machine : machines) {
            var energy = machine.energyContainer();
            energyUsed += Math.max(0, energy.getMaxEnergy() - energy.getEnergy());
        }
    }

    void resetCounters() {
        energyUsed = 0;
        storages.forEach(D01Storage::resetCounters);
    }

    void validateReady() {
        for (PbRecipeContext machine : machines) {
            if (!highIo) continue;
            var node = (IManagedGridNode) ((IAe2OutputHostBase) machine).productivebeesgenesis$getAe2GridNode();
            if (node == null || !node.isActive()) throw new IllegalStateException("Inactive D01 AE node");
        }
    }

    boolean hasMeasuredProduction(JsonObject before, JsonObject after) {
        if (energyUsed <= 0) return false;
        if (highIo) return storages.stream().allMatch(D01Storage::hasMeasuredProduction);
        return after.get("centrifugeOutputItems").getAsLong() > before.get("centrifugeOutputItems").getAsLong();
    }

    JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("scene", name);
        result.addProperty("pairs", apiaries.size());
        result.addProperty("bees", apiaries.stream().mapToInt(TileEntityMekApiary::getBeeSlotCount).sum());
        result.addProperty("energyUsedFE", energyUsed);
        long outputItems = 0;
        for (PbRecipeContext centrifuge : centrifuges) {
            // 普通场景只有基础离心机；高 IO 场景由 AE 元件逐键快照核实产出。
            if (highIo) break;
            for (var slot : new mekanism.api.inventory.IInventorySlot[]{centrifuge.primaryOutputSlot(0),
                    centrifuge.secondaryOutputSlot(0), centrifuge.tertiaryOutputSlot(0)}) {
                if (slot != null) outputItems += slot.getStack().getCount();
            }
        }
        result.addProperty("centrifugeOutputItems", outputItems);
        result.addProperty("virtualAcceleration", 1);
        result.addProperty("aeTopology", highIo ? "one powered two-machine grid per pair, direct logical links, real 256k item/fluid cells" : "no powered AE grid; adjacent direct output");
        JsonArray storageData = new JsonArray();
        storages.forEach(storage -> storageData.add(storage.snapshot()));
        result.add("aeStorages", storageData);
        JsonArray blocks = new JsonArray();
        for (PbRecipeContext machine : machines) {
            BlockEntity block = (BlockEntity) machine;
            blocks.add(block.saveWithFullMetadata(level.registryAccess()).toString());
        }
        result.add("machineNbt", blocks);
        return result;
    }

    void close() {
        for (int index = 0; index < providers.size(); index++) {
            var node = power.get(index).getMainNode().getNode();
            if (node != null) node.getGrid().getStorageService().removeGlobalStorageProvider(providers.get(index));
        }
        for (BlockPos pos : positions) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        // 拆除夹具可能掉出库存，不让上一场景的掉落物影响下一次采样。
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, new AABB(-2, 78, -2, 66, 84, 66))) {
            item.discard();
        }
    }
}
