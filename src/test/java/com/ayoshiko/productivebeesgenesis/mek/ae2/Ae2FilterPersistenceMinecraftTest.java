package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2FilterPersistenceMinecraftTest {
    private static final String KEY = Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER;

    @BeforeAll
    static void loadConfigs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    @Test
    void realCentrifugeUpgradeDataPreservesDeferredConfigurationAndCustody() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (boolean ae2Present : new boolean[] {false, true}) {
            try (var loader = mockStatic(Ae2IntegrationLoader.class)) {
                loader.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(ae2Present);
                for (var block : List.of(ModBlocks.MEK_CENTRIFUGE.get())) {
                    for (net.minecraft.nbt.Tag raw : List.of(configuration().get(KEY), StringTag.valueOf("damaged"))) {
                        var source = block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                        var target = block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                        var state = ((IAe2OutputHostBase) source).productivebeesgenesis$getAe2StateHolder();
                        var input = configuration();
                        input.put(KEY, raw.copy());
                        state.loadPerTileState(input);
                        state.setAeItemOutputEnabled(false);
                        state.setAeFluidOutputEnabled(false);
                        state.getPendingItemBuffer().enqueueReturnToNetwork("exact-item", 70, 0);
                        state.getPendingItemBuffer().transfer().beginKnown(() -> "exact-item", 30);
                        state.getPendingItemBuffer().transfer().offer(20, -1);
                        var before = save(state);
                        var data = ((TileEntityMekCentrifuge) source).getUpgradeData(registries);
                        assertNotNull(data);
                        ((TileEntityMekCentrifuge) target).parseUpgradeData(registries, data);
                        var after = save(((IAe2OutputHostBase) target).productivebeesgenesis$getAe2StateHolder());
                        assertEquals(before, after, block + " ae2=" + ae2Present + " raw=" + raw.getType());
                    }
                }
            }
        }
    }

    @Test
    void hiveUpgradeUsesItsActualOutputSwitchesAndPreservesCustody() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var block = ModBlocks.MEK_APIARY.get();
        for (boolean ae2Present : new boolean[] {false, true}) {
            try (var loader = mockStatic(Ae2IntegrationLoader.class)) {
                loader.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(ae2Present);
                var source = (TileEntityMekApiary) block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                var target = (TileEntityMekApiary) block.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                source.productivebeesgenesis$setAeItemOutputEnabled(false);
                source.productivebeesgenesis$setAeFluidOutputEnabled(false);
                var holder = source.productivebeesgenesis$getAe2StateHolder();
                holder.getPendingItemBuffer().transfer().beginKnown(() -> "retained", 30);
                holder.getPendingItemBuffer().transfer().offer(20, -1);
                var before = new CompoundTag();
                holder.savePendingItems(before);
                var data = source.getUpgradeData(registries);
                assertNotNull(data);
                target.parseUpgradeData(registries, data);
                assertFalse(target.productivebeesgenesis$isAeItemOutputEnabled());
                assertFalse(target.productivebeesgenesis$isAeFluidOutputEnabled());
                var after = new CompoundTag();
                target.productivebeesgenesis$getAe2StateHolder().savePendingItems(after);
                assertEquals(before, after);
            }
        }
    }

    @Test
    void missingDependencyKeepsRawConfigurationAndCustodyThroughSaveUnloadAndRestore() {
        var source = new Ae2OutputStateHolder();
        source.loadPerTileState(configuration());
        source.getPendingItemBuffer().enqueueReturnToNetwork("exact-item", 70, 0);
        source.getPendingItemBuffer().transfer().beginKnown(() -> "exact-item", 30);
        source.getPendingItemBuffer().transfer().offer(20, -1);
        var saved = save(source);
        var expected = saved.copy();
        var missing = new Ae2OutputStateHolder();
        try (var loader = mockStatic(Ae2IntegrationLoader.class)) {
            loader.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(false);
            missing.loadPerTileState(saved);
            missing.loadPendingItems(saved);
            assertTrue(missing.hasDeferredInputFilter());
            assertFalse(missing.retryInputFilterData());
            saved.getCompound(KEY).putInt("version", 99);
            missing.getOrCreateInputFilter();
            missing.clearForChunkUnload();
            assertEquals(expected.get(KEY), save(missing).get(KEY));
        }
        var restored = new Ae2OutputStateHolder();
        var roundTrip = save(missing);
        restored.loadPerTileState(roundTrip);
        restored.loadPendingItems(roundTrip);
        assertFalse(restored.hasDeferredInputFilter());
        assertEquals(expected, save(restored));
        assertTrue(restored.getPendingItemBuffer().isBlocked());
        assertEquals(100, restored.getPendingItemBuffer().getTotalAmount());
        assertEquals(10, restored.getPendingItemBuffer().transfer().knownAmount());
        assertEquals(20, restored.getPendingItemBuffer().transfer().offeredAmount());
    }

    @Test
    void corruptOrFutureConfigurationStaysRawAndCannotEnableUnrestrictedPulls() {
        var future = configuration().getCompound(KEY).copy();
        future.putInt("version", 2);
        var mode = configuration().getCompound(KEY).copy();
        mode.putInt("mode", 1);
        var entriesType = configuration().getCompound(KEY).copy();
        entriesType.putString("entries", "broken");
        var index = configuration().getCompound(KEY).copy();
        index.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putLong("i", 0);
        var duplicate = configuration().getCompound(KEY).copy();
        var entries = duplicate.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND);
        entries.add(entries.getCompound(0).copy());
        var amount = configuration().getCompound(KEY).copy();
        amount.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putInt("a", 64);
        var wrongList = configuration().getCompound(KEY).copy();
        var ints = new ListTag();
        ints.add(IntTag.valueOf(1));
        wrongList.put("entries", ints);
        try (var cache = mockConstruction(Ae2ConfigCache.class,
                (mock, context) -> when(mock.isInputPullEnabled()).thenReturn(true))) {
            for (net.minecraft.nbt.Tag raw : List.of(StringTag.valueOf("broken"), future, mode,
                    entriesType, index, duplicate, amount, wrongList)) {
                var holder = new Ae2OutputStateHolder();
                holder.setAeItemInputEnabled(true);
                assertTrue(holder.isInputPullEnabled());
                var input = configuration();
                input.put(KEY, raw);
                holder.loadPerTileState(input);
                holder.getPendingItemBuffer().enqueueReturnToNetwork("owned", 7, 0);
                assertTrue(holder.hasDeferredInputFilter());
                assertFalse(holder.isInputPullEnabled());
                assertFalse(holder.retryInputFilterData());
                assertEquals(raw, save(holder).get(KEY));
                holder.loadPerTileState(configuration());
                assertFalse(holder.hasDeferredInputFilter());
                assertTrue(holder.isInputPullEnabled());
                assertEquals(7, holder.getPendingItemBuffer().getTotalAmount());
            }
        }
    }

    @Test
    void legacyListsStillMigrateAndMissingEntriesDoNotReusePreviousSlots() {
        var holder = new Ae2OutputStateHolder();
        holder.loadPerTileState(configuration());
        var legacy = new CompoundTag();
        legacy.putByte("mode", (byte) 1);
        var entries = new ListTag();
        entries.add(StringTag.valueOf("productivebees:iron"));
        legacy.put("entries", entries);
        var input = new CompoundTag();
        input.put(KEY, legacy);
        holder.loadPerTileState(input);
        assertFalse(holder.hasDeferredInputFilter());
        var output = save(holder).getCompound(KEY);
        assertEquals(1, output.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND).size());
        assertEquals("productivebees:iron", output.getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND)
                .getCompound(0).getString("v"));
        legacy.remove("entries");
        holder.loadPerTileState(input);
        assertTrue(save(holder).getCompound(KEY).getList("entries", net.minecraft.nbt.Tag.TAG_COMPOUND).isEmpty());
    }

    @Test
    void validExplicitReplacementOrClearCannotResurrectDeferredConfiguration() {
        var holder = new Ae2OutputStateHolder();
        try (var loader = mockStatic(Ae2IntegrationLoader.class)) {
            loader.when(Ae2IntegrationLoader::isAe2Loaded).thenReturn(false);
            holder.loadPerTileState(configuration());
            holder.loadPerTileState(new CompoundTag());
            assertFalse(holder.hasDeferredInputFilter());
            holder.loadPerTileState(configuration());
            holder.clear();
            assertFalse(holder.hasDeferredInputFilter());
        }
        var reused = configuration();
        holder.savePerTileState(reused);
        assertFalse(reused.contains(KEY));
    }

    private static CompoundTag save(Ae2OutputStateHolder holder) {
        var result = new CompoundTag();
        holder.savePerTileState(result);
        holder.savePendingItems(result);
        return result;
    }

    private static CompoundTag configuration() {
        var filter = new CompoundTag();
        filter.putInt("version", 1);
        filter.putInt("capacity", 72);
        filter.putByte("mode", (byte) 1);
        filter.putBoolean("precise", true);
        filter.putBoolean("unlimitedAllFallback", true);
        filter.putBoolean("globalNetworkStock", true);
        filter.putLong("globalReserveAmount", 4096);
        var entry = new CompoundTag();
        entry.putInt("i", 40);
        entry.putString("v", "@{id:\"minecraft:iron_ingot\",components:{\"minecraft:custom_name\":'\"Exact\"'}}");
        entry.putLong("a", 128);
        entry.putLong("r", 4096);
        entry.putBoolean("u", true);
        entry.putBoolean("n", true);
        var entries = new ListTag();
        entries.add(entry);
        filter.put("entries", entries);
        var result = new CompoundTag();
        result.put(KEY, filter);
        result.putBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_INPUT, true);
        return result;
    }
}
