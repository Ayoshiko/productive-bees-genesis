package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import mekanism.api.Upgrade;
import mekanism.common.tile.component.ITileComponent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ApiaryRecoveryMinecraftTest {
    @BeforeAll
    static void configs() {
        ConfigTracker.INSTANCE.loadDefaultServerConfigs();
    }

    @AfterAll
    static void unload() {
        ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
    }

    private static RegistryAccess registries() {
        return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    }

    private static HolderLookup.Provider unavailableProvider() {
        var provider = mock(HolderLookup.Provider.class);
        doThrow(new IllegalStateException("Registry temporarily unavailable"))
                .when(provider).createSerializationContext(any());
        return provider;
    }

    private static TileEntityMekApiary tile(ServerLevel level) {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
        var tile = new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
        tile.setLevel(level);
        return tile;
    }

    private static ItemStack output(int index, int count) {
        var item = new ItemStack(Items.DIAMOND, count);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("原产物-" + index));
        var components = new CompoundTag();
        components.putInt("identity", index);
        item.set(DataComponents.CUSTOM_DATA, net.minecraft.world.item.component.CustomData.of(components));
        return item;
    }

    private static CompoundTag buffer(List<ItemStack> items) {
        var tag = new CompoundTag();
        var entries = new ListTag();
        for (var item : items) {
            var entry = (CompoundTag) item.copyWithCount(1).save(registries());
            entry.putInt("productivebeesgenesis_count", item.getCount());
            entries.add(entry);
        }
        tag.put("stacks", entries);
        return tag;
    }

    private record Fixture(MinecraftServer server, ServerLevel level, int[] tick) implements AutoCloseable {
        @Override
        public void close() {
            ResourceRuntime.stopped(new ServerStoppedEvent(server));
        }
    }

    private static Fixture fixture() {
        var server = mock(MinecraftServer.class);
        var level = mock(ServerLevel.class);
        int[] tick = {0};
        when(server.isSameThread()).thenReturn(true);
        when(server.getTickCount()).thenAnswer(invocation -> tick[0]);
        when(level.getServer()).thenReturn(server);
        when(level.registryAccess()).thenReturn(registries());
        return new Fixture(server, level, tick);
    }

    @Test
    void restoredPbUpgradesApplyBeforeTheFirstProductionQuery() {
        try (var fixture = fixture()) {
            for (var combUpgrade : List.of(PbUpgradeType.BLOCK, PbUpgradeType.PRODUCTIVITY_4)) {
                var source = tile(fixture.level());
                source.getComponent().addUpgrades(Upgrade.SPEED, 1);
                assertTrue(source.installPbUpgrade(combUpgrade), combUpgrade.getId());
                if (combUpgrade == PbUpgradeType.BLOCK) {
                    assertTrue(source.installPbUpgrade(PbUpgradeType.PRODUCTIVITY_3), "productivity_3");
                }
                assertTrue(source.installPbUpgrade(PbUpgradeType.TIME_2), "time_2");
                assertTrue(source.installPbUpgrade(PbUpgradeType.GENE_SAMPLER), "gene_sampler");
                var expected = ApiaryBatchUpgradeSnapshot.capture(source, source.upgradeHandler);
                float expectedTime = source.upgradeHandler.getTimeMultiplier();

                var restored = tile(null);
                restored.loadAdditional(source.saveWithFullMetadata(registries()), registries());
                var actual = ApiaryBatchUpgradeSnapshot.capture(restored, restored.upgradeHandler);
                assertEquals(expected, actual);
                assertTrue(actual.hasCombBlockUpgrade());
                assertEquals(expectedTime, restored.upgradeHandler.getTimeMultiplier());
                assertEquals(1, actual.geneSamplerCount());

                var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(
                        "productivebeesgenesis:ultimate_mek_apiary_factory")).defaultBlockState();
                var factory = new TileEntityMekApiaryFactory(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
                var upgrade = source.getUpgradeData(registries());
                assertNotNull(upgrade);
                source.setRemoved();
                assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED,
                        factory.nbtSerializer().applyUpgradeData(registries(), upgrade));
                var upgraded = ApiaryBatchUpgradeSnapshot.capture(factory, factory.upgradeHandler);
                assertEquals(expected, upgraded);
                assertEquals(expectedTime, factory.upgradeHandler.getTimeMultiplier());
            }
        }
    }

    @Test
    void legacyAndEmptyPbCountsReplaceThePreviouslyCachedUpgradeState() {
        var counts = new CompoundTag();
        counts.putInt(PbUpgradeType.PRODUCTIVITY_4.getId(), 8);
        counts.putInt(PbUpgradeType.TIME_2.getId(), 12);
        counts.putInt(PbUpgradeType.GENE_SAMPLER.getId(), 2);
        var handler = new CompoundTag();
        var entries = new ListTag();
        for (var type : List.of(PbUpgradeType.PRODUCTIVITY_4, PbUpgradeType.TIME_2, PbUpgradeType.GENE_SAMPLER)) {
            var entry = (CompoundTag) PbUpgradeInventorySlot.getRepresentativeStack(type)
                    .copyWithCount(counts.getInt(type.getId())).save(registries());
            entry.putInt("Slot", entries.size());
            entries.add(entry);
        }
        handler.put("Items", entries);
        handler.putInt("Size", entries.size());
        var current = new CompoundTag();
        current.put(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS, counts.copy());
        var legacyCounts = new CompoundTag();
        legacyCounts.put(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADES_LEGACY, counts.copy());
        var legacyHandler = new CompoundTag();
        legacyHandler.put(ApiaryPbUpgradeNbtMigrator.NBT_KEY_PB_UPGRADE_HANDLER_LEGACY, handler);
        var emptyCounts = new CompoundTag();
        emptyCounts.put(ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS, new CompoundTag());

        for (var saved : List.of(current, legacyCounts, legacyHandler)) {
            var restored = tile(null);
            assertFalse(restored.upgradeHandler.hasCombBlockUpgrade());
            restored.pbUpgradeHandler.loadPbUpgradeCounts(saved.copy(), registries());
            assertTrue(restored.upgradeHandler.hasCombBlockUpgrade());
            assertEquals(1 + 8 * PbUpgradeType.PRODUCTIVITY_4.getProductivityFactor(),
                    restored.upgradeHandler.getProductivityMultiplier(), 0.00001F);
            assertEquals(2, restored.upgradeHandler.getGeneSamplerCount());
            assertEquals(12, restored.getPbUpgradeCount(PbUpgradeType.TIME_2));

            for (var empty : List.of(emptyCounts, new CompoundTag())) {
                restored.pbUpgradeHandler.loadPbUpgradeCounts(empty, registries());
                assertFalse(restored.upgradeHandler.hasCombBlockUpgrade());
                assertEquals(1.0F, restored.upgradeHandler.getProductivityMultiplier());
                assertEquals(0, restored.upgradeHandler.getGeneSamplerCount());
                restored.pbUpgradeHandler.loadPbUpgradeCounts(saved.copy(), registries());
                assertTrue(restored.upgradeHandler.hasCombBlockUpgrade());
            }
        }
    }

    @Test
    void actualDecodeRecoveryRetainsComponentsAmountsAndUsesEightEntryWindow() {
        try (var fixture = fixture()) {
            var tile = tile(fixture.level());
            var expected = new ArrayList<ItemStack>();
            for (int i = 0; i < 17; i++) expected.add(output(i, Integer.MAX_VALUE - i));
            tile.getOutputBuffer().load(unavailableProvider(), buffer(expected));
            assertTrue(tile.getOutputBuffer().hasInvalidData());
            tile.getOutputBuffer().tickRecovery(fixture.level());
            assertTrue(tile.getOutputBuffer().hasInvalidData());
            assertEquals(17, tile.getOutputBuffer().getBufferedGroupCount());
            tile.getOutputBuffer().tickRecovery(fixture.level());
            assertTrue(tile.getOutputBuffer().hasInvalidData());
            tile.getOutputBuffer().tickRecovery(fixture.level());
            assertFalse(tile.getOutputBuffer().hasInvalidData());
            assertEquals(buffer(expected).getList("stacks", 10),
                    tile.getOutputBuffer().save(registries()).getList("stacks", 10));
            long[] received = {0};
            tile.getOutputBuffer().pushToSink(stack -> {
                received[0] += stack.getCount();
                assertTrue(expected.stream().anyMatch(item -> ItemStack.matches(item, stack)));
                return stack.getCount();
            }, stack -> false);
            assertEquals(expected.stream().mapToLong(ItemStack::getCount).sum(), received[0]);
            assertEquals(0, tile.getOutputBuffer().getBufferedGroupCount());
        }
    }

    @Test
    void globalRecoveryBudgetDefersFifthHostAndRemovalReleasesQueuedIdentity() {
        try (var fixture = fixture()) {
            var tiles = new ArrayList<TileEntityMekApiary>();
            for (int i = 0; i < 5; i++) {
                var tile = tile(fixture.level());
                tile.getOutputBuffer().load(unavailableProvider(), buffer(List.of(output(i, 32))));
                tiles.add(tile);
                tile.getOutputBuffer().tickRecovery(fixture.level());
            }
            for (int i = 0; i < 4; i++) assertFalse(tiles.get(i).getOutputBuffer().hasInvalidData());
            assertTrue(tiles.get(4).getOutputBuffer().hasInvalidData());
            tiles.get(4).getOutputBuffer().stopRecovery();
            fixture.tick()[0]++;
            var other = tile(fixture.level());
            other.getOutputBuffer().load(unavailableProvider(), buffer(List.of(output(8, 64))));
            other.getOutputBuffer().tickRecovery(fixture.level());
            assertFalse(other.getOutputBuffer().hasInvalidData());
            tiles.get(4).getOutputBuffer().tickRecovery(fixture.level());
            assertFalse(tiles.get(4).getOutputBuffer().hasInvalidData());
        }
    }

    @Test
    void damagedEntryIsPreservedAndNotRetriedWithoutANewRecipeGeneration() {
        try (var fixture = fixture()) {
            var tile = tile(fixture.level());
            var damaged = buffer(List.of(output(1, 17)));
            damaged.getList("stacks", 10).getCompound(0).putLong("productivebeesgenesis_count", 17L);
            tile.getOutputBuffer().load(registries(), damaged);
            var original = tile.getOutputBuffer().save(registries());
            tile.getOutputBuffer().tickRecovery(fixture.level());
            clearInvocations(fixture.level());
            for (int i = 0; i < 20; i++) {
                fixture.tick()[0]++;
                tile.getOutputBuffer().tickRecovery(fixture.level());
            }
            verify(fixture.level(), never()).registryAccess();
            assertEquals(original, tile.getOutputBuffer().save(registries()));
            long oldVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
            try {
                ProductiveBeesGenesis.RECIPE_VERSION.incrementAndGet();
                tile.getOutputBuffer().tickRecovery(fixture.level());
                verify(fixture.level(), times(1)).registryAccess();
                assertEquals(original, tile.getOutputBuffer().save(registries()));
                assertTrue(tile.getOutputBuffer().productionBlocked());
            } finally {
                ProductiveBeesGenesis.RECIPE_VERSION.set(oldVersion);
            }
        }
    }

    @Test
    void invalidBeeSlotsAndPendingFluidSurviveSaveAndUpgrade() {
        var tile = tile(null);
        var source = new CompoundTag();
        var beeSlots = new ListTag();
        var bee = new CompoundTag();
        bee.putInt("slot_index", 0);
        var entity = new CompoundTag();
        entity.putString("id", "minecraft:bee");
        bee.put("entity_data", entity);
        beeSlots.add(bee);
        beeSlots.add(bee.copy());
        source.put(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, beeSlots);
        var pendingFluid = new CompoundTag();
        pendingFluid.putLong("PendingAmount", 5_000_000_000L);
        var template = new CompoundTag();
        template.putString("id", "missing:fluid");
        template.putInt("amount", 1);
        pendingFluid.put("PendingFluid", template);
        source.put("productivebeesgenesis_apiary_fluid", pendingFluid);

        tile.nbtSerializer().loadApiaryState(source, registries());
        assertTrue(tile.slotManager().hasInvalidBeeData());
        assertTrue(tile.hasInvalidFluidData());
        var saved = new CompoundTag();
        tile.nbtSerializer().saveApiaryState(saved, registries());
        assertEquals(beeSlots, saved.get(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS));
        assertEquals(pendingFluid, saved.get("productivebeesgenesis_apiary_fluid"));
        var upgrade = tile.nbtSerializer().buildUpgradeData(registries(), false, false);
        var target = tile(null);
        assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, target.nbtSerializer().applyUpgradeData(registries(), upgrade));
        var savedAgain = new CompoundTag();
        target.nbtSerializer().saveApiaryState(savedAgain, registries());
        assertEquals(beeSlots, savedAgain.get(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS));
        assertEquals(pendingFluid, savedAgain.get("productivebeesgenesis_apiary_fluid"));
    }

    @Test
    void frozenSnapshotRecoversOriginalOutputWhileUnknownRequestNeverReplays() {
        var generated = new BeeProduceProcessor.Generated(List.of(output(4, 701)), FluidStack.EMPTY, 0);
        var state = new ApiaryProductionState(2, () -> {});
        state.freeze(generated);
        var original = state.save(registries());
        var restored = new ApiaryProductionState(2, () -> {});
        restored.load(unavailableProvider(), original);
        assertTrue(restored.hasInvalidData());
        assertEquals(original, restored.save(registries()));
        assertTrue(restored.retryInvalid(registries()));
        assertFalse(restored.suspended());
        assertEquals(original, restored.save(registries()));
        var group = new OrderedSlotIndex();
        group.reset(2);
        group.add(1);
        var uncertain = new ApiaryProductionState(2, () -> {});
        uncertain.counts[1] = 4;
        var bee = new CompoundTag();
        bee.putString("entity", "productivebees:configurable_bee");
        uncertain.bees[1].setBeeData(bee);
        uncertain.begin(group);
        uncertain.fail(new IllegalStateException("External generation outcome unknown"));
        var unknown = uncertain.save(registries());
        restored.load(registries(), unknown);
        assertFalse(restored.retryInvalid(registries()));
        assertTrue(restored.suspended());
        assertEquals(uncertain.request(), restored.request());
        assertEquals(unknown, restored.save(registries()));
        assertThrows(IllegalStateException.class, () -> restored.begin(group));
    }
    @Test
    void partialUpgradeKeepsFullSourceAndSkipsResourceClaimAcrossReload() {
        var source = tile(null);
        source.getOutputSlots().getFirst().setStack(output(2, 7));
        source.getOutputBuffer().accept(output(3, 19));
        var data = source.nbtSerializer().buildUpgradeData(registries(), false, false);
        var resource = mock(ResourceHiveHost.Upgrade.class);
        data.resourceUpgrade = resource;
        var target = tile(null);
        var component = mock(ITileComponent.class);
        doThrow(new IllegalStateException("Component restore failed after the output slots"))
                .when(component).read(any(CompoundTag.class), any(HolderLookup.Provider.class));
        target.addComponent(component);

        target.parseUpgradeData(registries(), data);
        assertTrue(target.upgradeRecovery().blocked());
        assertTrue(target.upgradeRecovery().phase().startsWith("component/"));
        assertEquals(7, target.getOutputSlots().getFirst().getCount());
        assertEquals(data.sourceSnapshot, ((CompoundTag) target.upgradeRecovery().snapshot()).getCompound("source"));
        verify(resource, never()).apply(any());
        target.getOutputSlots().getFirst().setStackSize(3, mekanism.api.Action.EXECUTE);
        assertEquals(ApiaryNbtSerializer.UpgradeResult.RETAINED, target.nbtSerializer().applyUpgradeData(registries(), data));
        assertEquals(3, target.getOutputSlots().getFirst().getCount());

        var saved = target.saveWithFullMetadata(registries());
        var restored = tile(null);
        restored.loadWithComponents(saved, registries());
        assertTrue(restored.upgradeRecovery().blocked());
        assertEquals(target.upgradeRecovery().snapshot(), restored.upgradeRecovery().snapshot());
        assertEquals(3, restored.getOutputSlots().getFirst().getCount());
        assertFalse(restored.onUpdateServer());
        assertNull(restored.getUpgradeData(registries()));
        assertThrows(IllegalStateException.class, () -> restored.upgradeRecovery().acknowledgeSettled(UUID.randomUUID()));
        UUID request = restored.upgradeRecovery().request();
        restored.upgradeRecovery().acknowledgeSettled(request);
        assertFalse(restored.upgradeRecovery().blocked());
        assertEquals(3, restored.getOutputSlots().getFirst().getCount());
        assertEquals(data.sourceSnapshot, ((CompoundTag) restored.upgradeRecovery().snapshot()).getCompound("source"));
        assertThrows(IllegalStateException.class, () -> restored.upgradeRecovery().acknowledgeSettled(request));
    }

    @Test
    void failedSourceClearRestoresOriginalAssetsBeforeReturningToTheInstaller() {
        boolean[] clearing = {false};
        int[] callbacks = {0};
        var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary"));
        var source = new TileEntityMekApiary(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState()) {
            @Override
            void onOutputSlotContentsChanged() {
                super.onOutputSlotContentsChanged();
                if (clearing[0]) {
                    callbacks[0]++;
                    if (callbacks[0] == 1) {
                        throw new IllegalStateException("Source output callback failed while clearing");
                    }
                }
            }
        };
        var bee = new CompoundTag();
        bee.putString("entity", "productivebees:configurable_bee");
        bee.putString("type", "productivebees:iron");
        source.getBeeSlot(0).setBeeData(bee);
        source.getBeeSlot(0).setTicksInHive(17);
        source.getOutputSlots().getFirst().setStack(output(11, 37));
        source.getOutputBuffer().accept(output(12, 23));
        var original = source.saveWithFullMetadata(registries());
        clearing[0] = true;

        assertNull(source.getUpgradeData(registries()));
        clearing[0] = false;
        assertTrue(callbacks[0] > 1);
        assertFalse(source.upgradeRecovery().blocked());
        assertEquals(37, source.getOutputSlots().getFirst().getCount());
        assertEquals(bee, source.getBeeSlot(0).getBeeData());
        assertEquals(17, source.getBeeSlot(0).getTicksInHive());
        var recovered = source.saveWithFullMetadata(registries());
        recovered.remove(ApiaryUpgradeRecovery.NBT_KEY);
        assertEquals(original, recovered);
        var settled = source.upgradeRecovery().snapshot();

        var restored = tile(null);
        restored.loadAdditional(source.saveWithFullMetadata(registries()), registries());
        assertFalse(restored.upgradeRecovery().blocked());
        assertEquals(settled, restored.upgradeRecovery().snapshot());
        assertEquals(37, restored.getOutputSlots().getFirst().getCount());
        assertNotNull(restored.getUpgradeData(registries()));
    }

    @Test
    void intactLegacySourceClearSettlesAutomaticallyButChangedInventoryStaysRetained() {
        try (var fixture = fixture()) {
            for (boolean factory : List.of(false, true)) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(factory
                        ? "productivebeesgenesis:ultimate_mek_apiary_factory"
                        : "productivebeesgenesis:mek_apiary"));
                for (boolean changed : List.of(false, true)) {
                    TileEntityMekApiary source = factory
                            ? new TileEntityMekApiaryFactory(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState())
                            : tile(null);
                    if (source instanceof TileEntityMekApiaryFactory sortingFactory) {
                        sortingFactory.toggleSorting();
                    }
                    source.getOutputSlots().getFirst().setStack(output(13, 41));
                    source.getOutputBuffer().accept(output(14, 29));
                    var original = source.saveWithFullMetadata(registries());
                    var oldProduction = original.getCompound(ApiaryOutputBuffer.nbtKey()).getCompound("production");
                    oldProduction.putInt("version", 2);
                    oldProduction.remove("held_groups");
                    UUID request = UUID.randomUUID();
                    source.upgradeRecovery().begin(request, original, "source_clear");
                    source.upgradeRecovery().fail(new IllegalStateException("Legacy source clear failed"));
                    if (changed) {
                        source.getOutputSlots().getFirst().setStackSize(3, mekanism.api.Action.EXECUTE);
                    }

                    TileEntityMekApiary restored = factory
                            ? new TileEntityMekApiaryFactory(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState())
                            : tile(null);
                    restored.loadWithComponents(source.saveWithFullMetadata(registries()), registries());
                    assertTrue(restored.upgradeRecovery().blocked());
                    restored.setLevel(fixture.level());
                    when(fixture.level().getBlockEntity(BlockPos.ZERO)).thenReturn(restored);
                    restored.onLoad();
                    assertEquals(changed, restored.upgradeRecovery().blocked());
                    assertEquals(changed ? 3 : 41, restored.getOutputSlots().getFirst().getCount());
                    assertEquals(request, restored.upgradeRecovery().request());
                    assertEquals(original, ((CompoundTag) restored.upgradeRecovery().snapshot()).getCompound("source"));
                    if (restored instanceof TileEntityMekApiaryFactory sortingFactory) {
                        assertTrue(sortingFactory.isSorting());
                    }
                    var savedAgain = restored.saveWithFullMetadata(registries());
                    TileEntityMekApiary reloaded = factory
                            ? new TileEntityMekApiaryFactory(Holder.direct(block), BlockPos.ZERO, block.defaultBlockState())
                            : tile(null);
                    reloaded.loadWithComponents(savedAgain, registries());
                    assertEquals(restored.upgradeRecovery().snapshot(), reloaded.upgradeRecovery().snapshot());
                    assertEquals(changed ? 3 : 41, reloaded.getOutputSlots().getFirst().getCount());
                }
            }
        }
    }

    @Test
    void sourceEncodingFailurePrecedesClearingInventoryAndPaidOutput() {
        var source = tile(null);
        source.getOutputSlots().getFirst().setStack(output(7, 13));
        source.getOutputBuffer().accept(output(8, 29));
        var before = source.getOutputBuffer().save(registries());
        var component = mock(ITileComponent.class);
        doThrow(new IllegalStateException("Source component encoding failed"))
                .when(component).write(any(CompoundTag.class), any(HolderLookup.Provider.class));
        source.addComponent(component);

        assertNull(source.getUpgradeData(registries()));
        assertEquals(13, source.getOutputSlots().getFirst().getCount());
        assertEquals(before, source.getOutputBuffer().save(registries()));
        assertFalse(source.upgradeRecovery().blocked());
    }

    @Test
    void sourceAndTargetKeepOneUpgradeRequestAndSuccessfulTransferCannotReplay() {
        var source = tile(null);
        source.getOutputSlots().getFirst().setStack(output(9, 11));
        source.getOutputBuffer().accept(output(10, 31));
        var data = source.getUpgradeData(registries());
        assertNotNull(data);
        assertTrue(source.getOutputSlots().getFirst().isEmpty());
        assertTrue(source.upgradeRecovery().blocked());
        assertEquals("source_cleared", source.upgradeRecovery().phase());
        assertNull(source.getUpgradeData(registries()));
        var target = tile(null);
        assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, target.nbtSerializer().applyUpgradeData(registries(), data));
        assertFalse(source.upgradeRecovery().blocked());
        assertFalse(target.upgradeRecovery().blocked());
        assertEquals(11, target.getOutputSlots().getFirst().getCount());
        assertEquals(31, target.getOutputBuffer().save(registries()).getList("stacks", 10)
                .getCompound(0).getInt("productivebeesgenesis_count"));
        target.getOutputSlots().getFirst().setStackSize(2, mekanism.api.Action.EXECUTE);
        assertEquals(ApiaryNbtSerializer.UpgradeResult.RETAINED, target.nbtSerializer().applyUpgradeData(registries(), data));
        assertEquals(2, target.getOutputSlots().getFirst().getCount());
    }

    @Test
    void malformedUpgradeRecordIsPreservedAndCannotBeConfirmed() {
        var source = tile(null);
        var raw = net.minecraft.nbt.StringTag.valueOf("保留升级原文");
        var saved = new CompoundTag();
        saved.put(ApiaryUpgradeRecovery.NBT_KEY, raw);
        source.nbtSerializer().loadApiaryState(saved, registries());
        assertTrue(source.upgradeRecovery().blocked());
        assertThrows(IllegalStateException.class, () -> source.upgradeRecovery().acknowledgeSettled(UUID.randomUUID()));
        var roundtrip = new CompoundTag();
        source.nbtSerializer().saveApiaryState(roundtrip, registries());
        assertEquals(raw, roundtrip.get(ApiaryUpgradeRecovery.NBT_KEY));
    }

}
