package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetStore;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ApiaryOwnershipCompatibilityMinecraftTest {
    @BeforeAll
    static void configure() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
    }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static TileEntityMekApiary hive() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
        return new TileEntityMekApiary(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState());
    }

    private static TileEntityMekCentrifuge centrifuge() {
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_centrifuge"));
        return new TileEntityMekCentrifuge(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState());
    }

    private static void paidBee(TileEntityMekApiary hive, int count) {
        var data = new CompoundTag();
        data.putString("id", "productivebees:configurable_bee");
        data.putString("type", "productivebees:iron");
        var bee = hive.getBeeSlots()[0];
        bee.setBeeData(data);
        hive.productionState().counts[0] = count;
        hive.productionState().capture(0, bee);
    }

    @Test
    void maintenanceSaveKeepsPaidSnapshotWithoutLegacyCounter() {
        var source = hive();
        paidBee(source, 4);
        var saved = new CompoundTag();
        ApiaryTilePersistence.saveAdditional(source, saved, registries());
        assertFalse(saved.contains(PendingBeeCycles.KEY));
        var restored = hive();
        ApiaryTilePersistence.loadAdditional(restored, saved, registries());
        assertEquals(source.productionState().save(registries()), restored.productionState().save(registries()));
        assertEquals(4, restored.productionState().counts[0]);
        assertTrue(restored.pendingCyclesReadable());
    }

    @Test
    void oldNetworkCyclesMigrateOnceAndConflictingMirrorIsPreserved() {
        var source = hive();
        paidBee(source, 4);
        var legacy = new ApiaryOwnershipAssets(source).capture(registries());
        var restored = hive();
        ApiaryTilePersistence.loadAdditional(restored, legacy, registries());
        assertEquals(4, restored.productionState().counts[0]);
        assertEquals(source.getBeeSlots()[0].getBeeData(), restored.productionState().bees[0].getBeeData());
        var saved = new CompoundTag();
        ApiaryTilePersistence.saveAdditional(restored, saved, registries());
        assertFalse(saved.contains(PendingBeeCycles.KEY));
        int[] conflicting = new int[restored.getBeeSlotCount()];
        conflicting[0] = 9;
        PendingBeeCycles.save(saved, conflicting, 0, 0);
        var conflict = saved.get(PendingBeeCycles.KEY).copy();
        var copy = hive();
        ApiaryTilePersistence.loadAdditional(copy, saved, registries());
        assertFalse(copy.pendingCyclesReadable());
        assertEquals(4, copy.productionState().counts[0]);
        var preserved = new CompoundTag();
        ApiaryTilePersistence.saveAdditional(copy, preserved, registries());
        assertEquals(conflict, preserved.get(PendingBeeCycles.KEY));
    }

    @Test
    void sealedCyclesHaveOneOwnerAndClearRevokesIndependentWork() {
        var source = hive();
        paidBee(source, 7);
        var assets = new ApiaryOwnershipAssets(source);
        assertTrue(assets.prepared());
        var image = assets.capture(registries());
        assertFalse(image.getCompound(ApiaryOutputBuffer.nbtKey()).contains("production"));
        assertEquals(7, PendingBeeCycles.read(image, source.getBeeSlotCount()).counts()[0]);
        assets.clear(registries());
        assertTrue(assets.empty());
        assertEquals(0, source.productionState().total());
        var restored = hive();
        var target = new ApiaryOwnershipAssets(restored);
        target.restore(image, registries());
        assertEquals(image, target.capture(registries()));
        assertEquals(7, restored.productionState().total());
        var drained = image.copy();
        drained.remove(PendingBeeCycles.KEY);
        var returned = hive();
        new ApiaryOwnershipAssets(returned).restore(drained, registries());
        assertEquals(0, returned.productionState().total());
    }

    @Test
    void retainedFrozenAndUnknownProductionStayWithIndependentOwner() {
        var retained = hive();
        paidBee(retained, 3);
        retained.getBeeSlots()[0].clear();
        retained.productionState().detachReplaced(retained.getBeeSlots());
        assertEquals(3, retained.productionState().total());
        assertFalse(new ApiaryOwnershipAssets(retained).prepared());

        var unknown = hive();
        paidBee(unknown, 4);
        var group = new OrderedSlotIndex();
        group.reset(unknown.getBeeSlotCount());
        group.add(0);
        unknown.productionState().begin(group);
        var before = unknown.productionState().save(registries());
        assertFalse(new ApiaryOwnershipAssets(unknown).prepared());
        assertThrows(IllegalStateException.class, () -> new ApiaryOwnershipAssets(unknown).capture(registries()));
        assertEquals(before, unknown.productionState().save(registries()));

        var frozen = hive();
        paidBee(frozen, 5);
        frozen.productionState().begin(group);
        frozen.productionState().converted();
        frozen.productionState().freeze(new BeeProduceProcessor.Generated(
                List.of(new ItemStack(Items.IRON_INGOT, 5)), FluidStack.EMPTY, 0));
        assertFalse(new ApiaryOwnershipAssets(frozen).prepared());
        assertEquals(5, frozen.productionState().items().getFirst().getCount());
    }

    @Test
    void resourceReferencesAndUnknownOutputCannotJoinEitherHost() {
        for (TileEntityMekanism host : List.of(hive(), centrifuge())) {
            var store = new MachineAssetStore(host);
            assertTrue(store.prepared());
            var resource = host.getData(ResourceHiveHost.TYPE);
            var reference = new CompoundTag();
            reference.putInt("version", 1);
            reference.putUUID("source", new UUID(11, 12));
            reference.putLong("generation", 1);
            reference.putLong("capacity", ResourceHiveHost.DEFAULT_CAPACITY);
            resource.deserializeNBT(registries(), reference);
            var before = resource.serializeNBT(registries());
            assertFalse(store.prepared());
            assertFalse(store.empty());
            assertThrows(IllegalStateException.class, () -> store.capture(registries()));
            assertEquals(before, resource.serializeNBT(registries()));
            var empty = new CompoundTag();
            empty.putInt("version", 1);
            resource.deserializeNBT(registries(), empty);
            assertTrue(store.prepared());
            OutputCustody.of(host).handoffItem(new ItemStack(Items.DIAMOND, 3), "uncertain receiver",
                    () -> { throw new IllegalStateException("after external acceptance"); });
            assertFalse(store.prepared());
            assertEquals(3, OutputCustody.of(host).amount());
        }
    }

    @Test
    void malformedBeeFluidAndUpgradeCustodyStayWithIndependentOwner() {
        var beeData = new CompoundTag();
        beeData.put(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, StringTag.valueOf("原始坏蜂位"));
        var fluidData = new CompoundTag();
        fluidData.put("productivebeesgenesis_apiary_fluid", StringTag.valueOf("原始坏流体"));
        var upgradeHost = hive();
        var source = new CompoundTag();
        source.putString("custody", "完整升级来源");
        upgradeHost.upgradeRecovery().begin(new UUID(31, 32), source, "fluid");
        upgradeHost.upgradeRecovery().fail(new IllegalStateException("保管升级失败"));
        var upgradeData = new CompoundTag();
        upgradeHost.nbtSerializer().saveApiaryState(upgradeData, registries());

        for (var original : List.of(beeData, fluidData, upgradeData)) {
            var host = hive();
            host.nbtSerializer().loadApiaryState(original, registries());
            var before = new CompoundTag();
            host.nbtSerializer().saveApiaryState(before, registries());
            var assets = new ApiaryOwnershipAssets(host);
            assertFalse(assets.prepared());
            assertFalse(assets.empty());
            assertFalse(new MachineAssetStore(host).prepared());
            assertThrows(IllegalStateException.class, () -> assets.capture(registries()));
            var after = new CompoundTag();
            host.nbtSerializer().saveApiaryState(after, registries());
            assertEquals(before, after);
            for (var key : original.getAllKeys()) {
                assertEquals(original.get(key), after.get(key), key);
            }
        }
    }

    @Test
    void localMixedRequestCannotBeRecapturedOrReplayed() {
        var host = hive();
        paidBee(host, 2);
        var context = new CompoundTag();
        context.putString("recipe", "paid_mixed_output");
        host.productionState().beginLocal(0, context, new BeeProduceProcessor.Generated(
                List.of(new ItemStack(Items.IRON_INGOT, 3)), FluidStack.EMPTY, 0));
        var before = host.productionState().save(registries());
        var assets = new ApiaryOwnershipAssets(host);
        assertFalse(assets.prepared());
        assertFalse(assets.empty());
        assertThrows(IllegalStateException.class, () -> assets.capture(registries()));
        assertEquals(before, host.productionState().save(registries()));
    }

    @Test
    void unreadableCommittedCentrifugeOutputCannotBeClearedByTakeover() {
        var host = centrifuge();
        var assets = host.ownershipAssets();
        var saved = assets.capture(registries());
        var original = StringTag.valueOf("原始离心余量");
        saved.put("productivebeesgenesis_pb_committed_pending", original);
        assets.restore(saved, registries());
        assertFalse(assets.prepared());
        assertFalse(assets.empty());
        assertEquals(original, assets.capture(registries()).get("productivebeesgenesis_pb_committed_pending"));
        assertFalse(new MachineAssetStore(host).prepared());
    }

    @Test
    void orphanResourceEvidenceAndMalformedBindingsRemainIsolated() {
        var host = hive();
        var resource = host.getData(ResourceHiveHost.TYPE);
        var evidence = new CompoundTag();
        evidence.putUUID("source", new UUID(13, 14));
        evidence.putLong("generation", 1);
        evidence.putString("reason", "missing_account");
        var list = new ListTag();
        list.add(evidence);
        var root = new CompoundTag();
        root.putInt("version", 1);
        root.put("unresolved_references", list);
        resource.deserializeNBT(registries(), root);
        assertFalse(new MachineAssetStore(host).prepared());
        assertEquals(root, resource.serializeNBT(registries()));
        host.getPersistentData().put(MemberBinding.TAG, StringTag.valueOf("invalid network owner"));
        assertFalse(ResourceHiveHost.ready(host));
        assertNull(ResourceHiveHost.ensureMechanical(host));
        assertEquals(root, resource.serializeNBT(registries()));
    }
}
