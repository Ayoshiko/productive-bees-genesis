package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.MyriadBeeTypeCache;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceCatalog;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.ayoshiko.productivebeesgenesis.util.MultiFlowerBeeAdapter;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import cy.jdkdigital.productivebees.init.ModFluids;
import mekanism.api.IContentsListener;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ApiaryCustodyMinecraftTest {
    @BeforeAll static void configs() { net.neoforged.fml.config.ConfigTracker.INSTANCE.loadDefaultServerConfigs(); }
    @AfterAll static void unload() { net.neoforged.fml.config.ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER); }
    private static TileEntityMekApiary tile() {
        var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
        return new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
    }
    private static RegistryAccess registries() { return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY); }
    private static ItemStack named(int i) {
        var stack = new ItemStack(Items.IRON_INGOT, 10);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("group-" + i));
        return stack;
    }

    @Test void fullBufferRefusesNewIdentityAndKeepsAllOldAssets() {
        var tile = tile();
        var buffer = tile.getOutputBuffer();
        for (int i = 0; i < 512; i++) assertEquals(10, buffer.accept(named(i)));
        assertEquals(0, buffer.accept(named(512)));
        assertEquals(512, buffer.getBufferedGroupCount());
        assertTrue(buffer.productionBlocked());
        var saved = buffer.save(registries());
        assertEquals(512, saved.getList("stacks", 10).size());
        assertEquals(10, buffer.accept(named(0)));
        assertEquals(20, buffer.save(registries()).getList("stacks", 10).getCompound(0).getInt("productivebeesgenesis_count"));
    }

    @Test void mixedBadEntryLargeCountsAndUpgradeKeepOriginalData() {
        var tile = tile();
        var tag = new CompoundTag();
        var list = new ListTag();
        var valid = (CompoundTag) new ItemStack(Items.DIAMOND).save(registries());
        valid.putInt("productivebeesgenesis_count", Integer.MAX_VALUE);
        list.add(valid);
        var bad = new CompoundTag();
        bad.putString("id", "absent:asset");
        bad.putInt("count", 1);
        bad.putString("evidence", "原始组件");
        list.add(bad);
        tag.put("stacks", list);
        tile.getOutputBuffer().load(registries(), tag);
        var first = tile.getOutputBuffer().save(registries());
        assertTrue(tile.getOutputBuffer().productionBlocked());
        assertEquals(bad, first.getList("stacks", 10).getCompound(1));
        var copy = tile();
        copy.getOutputBuffer().load(registries(), first);
        assertEquals(first, copy.getOutputBuffer().save(registries()));
        var upgrade = tile.getUpgradeData(registries());
        var upgraded = tile();
        assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, upgraded.nbtSerializer().applyUpgradeData(registries(), upgrade));
        assertEquals(first, upgraded.getOutputBuffer().save(registries()));
    }

    @Test void failedGroupRetainsUnstartedCyclesWhileCompletedGroupCannotReplay() {
        var state = new ApiaryProductionState(4, () -> {});
        for (int i = 0; i < 4; i++) {
            state.counts[i] = 4;
            var bee = new CompoundTag();
            bee.putString("entity", "productivebees:configurable_bee");
            bee.putString("type", "productivebees:iron");
            bee.putInt("identity", i);
            state.bees[i].setBeeData(bee);
        }
        var group = new OrderedSlotIndex();
        group.reset(4);
        group.add(0);
        state.begin(group);
        state.converted();
        state.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.DIAMOND, 4)), FluidStack.EMPTY, 0));
        assertEquals(4, state.items().getFirst().getCount());
        state.items().getFirst().setCount(0);
        state.itemsChanged();
        group.reset(4);
        group.add(1);
        state.begin(group);
        var failedRequest = state.request();
        state.fail(new IllegalStateException("flower callback failed"));
        assertArrayEquals(new int[]{0, 4, 4, 4}, state.counts);
        assertTrue(state.groupSuspended(1, false));
        assertFalse(state.groupSuspended(0, false));
        assertFalse(state.groupSuspended(2, false));
        assertThrows(IllegalStateException.class, () -> state.begin(group));

        group.reset(4);
        group.add(2);
        state.begin(group);
        var secondRequest = state.request();
        state.fail(new IllegalStateException("second flower callback failed"));
        var requests = List.of(
                new ApiaryProductionState.RequestStatus(failedRequest, "conversion", "IllegalStateException: flower callback failed"),
                new ApiaryProductionState.RequestStatus(secondRequest, "conversion", "IllegalStateException: second flower callback failed"));
        assertEquals(requests, state.requestStatus());

        group.reset(4);
        group.add(3);
        state.begin(group);
        state.converted();
        state.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.EMERALD, 4)), FluidStack.EMPTY, 0));
        assertArrayEquals(new int[]{0, 4, 4, 0}, state.counts);
        var copy = new ApiaryProductionState(5, () -> {});
        copy.load(registries(), state.save(registries()));
        assertTrue(copy.suspended());
        assertArrayEquals(new int[]{0, 4, 4, 0, 0}, copy.counts);
        for (int i = 0; i < copy.counts.length; i++) {
            assertEquals(i == 1 || i == 2, copy.slotSuspended(i));
        }
        assertEquals(failedRequest, copy.request());
        assertEquals(requests, copy.requestStatus());
        assertEquals(Items.EMERALD, copy.items().getFirst().getItem());
        assertEquals(4, copy.items().getFirst().getCount());
        copy.acknowledgeSettled(failedRequest);
        assertTrue(copy.suspended());
        assertArrayEquals(new int[]{0, 0, 4, 0, 0}, copy.counts);
        assertFalse(copy.slotSuspended(1));
        assertTrue(copy.slotSuspended(2));
        assertEquals(List.of(requests.get(1)), copy.requestStatus());
        assertThrows(IllegalStateException.class, () -> copy.acknowledgeSettled(failedRequest));
        copy.acknowledgeSettled(secondRequest);
        assertFalse(copy.suspended());
        assertTrue(copy.requestStatus().isEmpty());
        assertArrayEquals(new int[]{0, 0, 0, 0, 0}, copy.counts);
        assertThrows(IllegalStateException.class, () -> copy.acknowledgeSettled(secondRequest));
        assertEquals(4, copy.items().getFirst().getCount());
    }

    @Test void largeGeneOutputsKeepTheirQuantityAndComponentsAcrossStackSplits() {
        var template = named(7).copyWithCount(1);
        var output = new java.util.ArrayList<ItemStack>();
        var chanced = new cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput(
                net.minecraft.world.item.crafting.Ingredient.of(template), 1_000_000_000, 1_000_000_000, 1);
        BeeProduceBatchSampler.sampleInto(output, java.util.Map.of(template, chanced),
                2, 1, 0, BeeProductivityGene.VERY_HIGH);
        assertEquals(3, output.size());
        assertEquals(6_400_000_000L, output.stream().mapToLong(ItemStack::getCount).sum());
        assertTrue(output.stream().allMatch(stack -> ItemStack.isSameItemSameComponents(template, stack)));
        output.clear();
        BeeProduceBatchSampler.sampleGuaranteedInto(output, template,
                2, 1_000_000_000F, BeeProductivityGene.VERY_HIGH);
        assertEquals(6_400_000_000L, output.stream().mapToLong(ItemStack::getCount).sum());
    }

    @Test void oversizedGenerationKeepsPaidCyclesWithoutExpandingTheItemList() {
        var state = new ApiaryProductionState(1, () -> {});
        state.counts[0] = Integer.MAX_VALUE;
        var data = new CompoundTag();
        data.putString("entity", "productivebees:lumber_bee");
        state.bees[0].setBeeData(data);
        var indices = new OrderedSlotIndex();
        indices.reset(1);
        indices.add(0);
        state.begin(indices);
        state.converted();
        var output = new java.util.ArrayList<ItemStack>();
        var template = named(1);
        var chanced = new cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput(
                net.minecraft.world.item.crafting.Ingredient.of(template), 1_000_000_000, 1_000_000_000, 1);
        var failure = assertThrows(ArithmeticException.class, () -> BeeProduceBatchSampler.sampleInto(
                output, java.util.Map.of(template, chanced), Integer.MAX_VALUE, 1, 0, BeeProductivityGene.NORMAL));
        state.fail(failure);
        assertTrue(output.isEmpty());
        assertThrows(ArithmeticException.class, () -> ItemStackMergeHelper.appendBulkCount(output, template, Long.MAX_VALUE));
        assertTrue(output.isEmpty());
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), state.save(registries()));
        assertFalse(restored.suspended());
        assertFalse(restored.active());
        assertEquals(Integer.MAX_VALUE, restored.counts[0]);
        assertEquals(data, restored.bees[0].getBeeData());
        assertNull(restored.request());
        assertTrue(restored.conversionCompleted(0, false));
        assertFalse(restored.hasFrozen());
        restored.begin(indices);
        assertEquals("generation", restored.phase());
        assertTrue(restored.conversionCompleted(0, false));
        assertEquals(Integer.MAX_VALUE, restored.counts[0]);
    }

    @Test void largePaidGenerationDrainsThroughTickerPrefixesAfterLegacyReload() {
        var source = tile();
        var level = mock(Level.class);
        when(level.registryAccess()).thenReturn(registries());
        when(level.getRecipeManager()).thenReturn(mock(RecipeManager.class));
        source.setLevel(level);
        var tile = spy(source);
        tile.setCentrifugePriorityEnabled(false);
        tile.setDirectContainerOutputEnabled(true);
        doReturn(true).when(tile).canFunction();
        doReturn(false).when(tile).shouldHoldForCentrifuge(any());
        long[] delivered = {0};
        doAnswer(call -> {
            ItemStack offered = call.getArgument(0);
            delivered[0] += offered.getCount();
            return offered.getCount();
        }).when(tile).pushGeneratedItemToNeighbors(any());
        var processor = spy(new BeeProduceProcessor(tile.upgradeHandler, tile));
        var manager = tile.getSlotManager();
        var feeder = tile.getFeederSlotManager();
        var type = ResourceLocation.parse("productivebees:iron");
        var template = named(9).copyWithCount(1);
        var recipe = new ChancedOutput(Ingredient.of(template), 1_000_000_000, 1_000_000_000, 1);
        doReturn(Map.of(template, recipe)).when(processor).getCachedProduce(type, level, feeder);
        var state = processor.production;
        var data = new CompoundTag();
        data.putString("entity", "productivebees:configurable_bee");
        data.putString("type", type.toString());
        var bee = manager.getBeeSlots()[0];
        bee.setBeeData(data);
        state.counts[0] = 10_000;
        state.capture(0, bee);
        var group = new OrderedSlotIndex();
        group.reset(state.counts.length);
        group.add(0);
        state.begin(group);
        state.converted();
        var legacy = state.save(registries());
        legacy.putInt("version", 2);
        legacy.remove("held_groups");
        legacy.getList("pending", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("converted");
        state.load(registries(), legacy);
        assertTrue(state.conversionCompleted(0, false));
        var conversion = spy(new ApiaryConversionProcessor(tile, manager, feeder));
        var ticker = new BeeSlotTickProcessor(tile, manager, processor, tile.upgradeHandler, feeder,
                new ApiaryBeeActivationCounter(manager.getBeeSlotCount()), new LogThrottle(), conversion);
        try (var balance = mockStatic(BalanceConfig.class);
                var fluid = mockStatic(com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.class)) {
            fluid.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.resolveFluidOutput(type, level))
                    .thenReturn(FluidStack.EMPTY);
            ticker.tick();
            ticker.tick();
            assertTrue(state.counts[0] > 0 && state.counts[0] < 10_000);
            assertEquals((10_000L - state.counts[0]) * 1_000_000_000L, delivered[0]);
            assertTrue(state.conversionCompleted(0, false));
            assertFalse(state.active());
            assertFalse(state.hasFrozen());
            var saved = state.save(registries());
            state.load(registries(), saved);
            assertEquals(saved, state.save(registries()));
            ticker.tick();
            assertEquals(0, state.total());
            assertEquals(10_000_000_000_000L, delivered[0]);
            verify(conversion, never()).processGroupConversions(any(), any(), any(), any(), any());
            assertFalse(state.suspended());
        }
    }

    @Test void singleFlowerBudgetUsesOnlySelectedOutputAndAllFlowerModeKeepsEveryOutput() {
        var level = mock(Level.class);
        var feeder = mock(FeederSlotManager.class);
        var type = ResourceLocation.parse("productivebees:lumber_bee");
        var bee = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:lumber_bee");
        bee.setBeeData(data);
        var counts = new int[]{10_000};
        var indices = new OrderedSlotIndex();
        indices.reset(1);
        indices.add(0);
        var upgrades = new ApiaryBatchUpgradeSnapshot(0, false, false,
                1_000_000_000, true, false, false, false, false, false);
        var flowerOutputs = List.of(new ItemStack(Items.OAK_LOG), new ItemStack(Items.BIRCH_LOG),
                new ItemStack(Items.SPRUCE_LOG));
        try (var balance = mockStatic(BalanceConfig.class);
                var flowers = mockStatic(MultiFlowerBeeAdapter.class)) {
            flowers.when(() -> MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
                    type, feeder, level)).thenReturn(flowerOutputs.getFirst());
            flowers.when(() -> MultiFlowerBeeAdapter.allProduceFromFeeder(
                    type, feeder, level)).thenThrow(new IllegalStateException("Unselected flower recipe unavailable"));
            var single = new BeeProductionGenerator(new Object(), new MultiFlowerProductionCache());
            var generated = single.prepare(new BeeSlot[]{bee}, counts, indices, type, Map.of(),
                    feeder, BlockPos.ZERO, level, upgrades, true, false, -1, null, null, 0, true, false);
            int singleCycles = single.generatedPaidCounts()[0];
            assertEquals((long) ItemStackMergeHelper.MAX_GENERATED_STACKS * Integer.MAX_VALUE / 1_000_000_000L,
                    singleCycles);
            assertEquals(singleCycles * 1_000_000_000L,
                    generated.items().stream().mapToLong(ItemStack::getCount).sum());
            assertTrue(generated.items().stream().allMatch(stack -> stack.is(Items.OAK_LOG)));
            assertArrayEquals(new int[]{10_000}, counts);
            flowers.verify(() -> MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
                    type, feeder, level), times(1));
            flowers.verify(() -> MultiFlowerBeeAdapter.allProduceFromFeeder(
                    type, feeder, level), never());

            var mixedFlowers = List.of(flowerOutputs.get(0), flowerOutputs.get(1), flowerOutputs.get(2),
                    new ItemStack(Items.JUNGLE_LOG));
            var mixedBees = new BeeSlot[4];
            var mixedCounts = new int[]{2_500, 2_500, 2_500, 2_500};
            var mixedIndices = new OrderedSlotIndex();
            mixedIndices.reset(4);
            for (int gene = 0; gene < mixedBees.length; gene++) {
                mixedBees[gene] = spy(new BeeSlot());
                mixedBees[gene].setBeeData(data.copy());
                doReturn(gene).when(mixedBees[gene]).getProductivityLevel();
                mixedIndices.add(gene);
            }
            flowers.when(() -> MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(type, feeder, level))
                    .thenReturn(mixedFlowers.get(0), mixedFlowers.get(1), mixedFlowers.get(2), mixedFlowers.get(3));
            var mixed = new BeeProductionGenerator(new Object(), new MultiFlowerProductionCache());
            var mixedGenerated = mixed.prepare(mixedBees, mixedCounts, mixedIndices, type, Map.of(),
                    feeder, BlockPos.ZERO, level, upgrades, true, false, -1, null, null, 0, true, false);
            assertTrue(mixedGenerated.items().size() <= ItemStackMergeHelper.MAX_GENERATED_STACKS);
            for (int gene = 0; gene < mixedBees.length; gene++) {
                ItemStack flower = mixedFlowers.get(gene);
                long expected = BeeProductivityGene.adjustCount(1_000_000_000, gene)
                        * mixed.generatedPaidCounts()[gene];
                assertEquals(expected, mixedGenerated.items().stream()
                        .filter(stack -> stack.is(flower.getItem())).mapToLong(ItemStack::getCount).sum());
            }
            assertArrayEquals(new int[]{2_500, 2_500, 2_500, 2_500}, mixedCounts);
            flowers.verify(() -> MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(type, feeder, level), times(5));
            flowers.verify(() -> MultiFlowerBeeAdapter.allProduceFromFeeder(type, feeder, level), never());

            balance.when(BalanceConfig::apiaryProduceAllFlowers).thenReturn(true);
            flowers.when(() -> MultiFlowerBeeAdapter.allProduceFromFeeder(
                    type, feeder, level)).thenReturn(flowerOutputs);
            var all = new BeeProductionGenerator(new Object(), new MultiFlowerProductionCache());
            var allGenerated = all.prepare(new BeeSlot[]{bee}, counts, indices, type, Map.of(),
                    feeder, BlockPos.ZERO, level, upgrades, true, false, -1, null, null, 0, true, false);
            int allCycles = all.generatedPaidCounts()[0];
            assertTrue(allCycles > 0 && allCycles < singleCycles);
            for (ItemStack flower : flowerOutputs) {
                assertEquals(allCycles * 1_000_000_000L, allGenerated.items().stream()
                        .filter(stack -> stack.is(flower.getItem())).mapToLong(ItemStack::getCount).sum());
            }
            assertArrayEquals(new int[]{10_000}, counts);
            flowers.verify(() -> MultiFlowerBeeAdapter.sampleProduceStackFromFeeder(
                    type, feeder, level), times(5));
        }
    }

    @Test void wannaPaidPrefixesReusePreparedLootAndRecoverAfterReload() {
        var source = tile();
        var level = mock(ServerLevel.class);
        when(level.registryAccess()).thenReturn(registries());
        when(level.getRecipeManager()).thenReturn(mock(RecipeManager.class));
        source.setLevel(level);
        var tile = spy(source);
        tile.setCentrifugePriorityEnabled(false);
        tile.setDirectContainerOutputEnabled(true);
        doReturn(true).when(tile).canFunction();
        doReturn(false).when(tile).shouldHoldForCentrifuge(any());
        long[] delivered = {0};
        doAnswer(call -> {
            ItemStack offered = call.getArgument(0);
            delivered[0] += offered.getCount();
            return offered.getCount();
        }).when(tile).pushGeneratedItemToNeighbors(any());
        var manager = tile.getSlotManager();
        var feeder = mock(FeederSlotManager.class);
        var type = ResourceLocation.parse("productivebees:wanna");
        when(feeder.hasValidFlower(type)).thenReturn(true);
        var processor = spy(new BeeProduceProcessor(tile.upgradeHandler, tile));
        doReturn(Map.of()).when(processor).getCachedProduce(type, level, feeder);
        var template = named(12).copyWithCount(1);
        var snapshot = mock(com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter.BatchSnapshot.class);
        when(snapshot.fitsStackBudget(anyLong(), anyInt())).thenAnswer(call -> {
            long rolls = call.getArgument(0);
            int available = call.getArgument(1);
            return rolls <= (long) available * Integer.MAX_VALUE / 1_000_000_000L;
        });
        when(snapshot.sample(eq(level), any(), anyFloat())).thenAnswer(call -> {
            long[] counts = call.getArgument(1);
            long amount = java.util.Arrays.stream(counts).sum() * 1_000_000_000L;
            var items = new java.util.ArrayList<ItemStack>();
            ItemStackMergeHelper.appendBulkCount(items, template, amount);
            return items;
        });
        var state = processor.production;
        var bee = manager.getBeeSlots()[0];
        var data = new CompoundTag();
        data.putString("entity", "productivebees:configurable_bee");
        data.putString("type", type.toString());
        bee.setBeeData(data);
        assertEquals(type, bee.getBeeTypeKey());
        state.counts[0] = 10_000;
        state.capture(0, bee);
        var indices = new OrderedSlotIndex();
        indices.reset(state.counts.length);
        indices.add(0);
        state.begin(indices);
        state.converted();
        state.fail(new IllegalStateException("old generation interrupted"));
        var conversion = spy(new ApiaryConversionProcessor(tile, manager, feeder));
        var ticker = new BeeSlotTickProcessor(tile, manager, processor, tile.upgradeHandler, feeder,
                new ApiaryBeeActivationCounter(manager.getBeeSlotCount()), new LogThrottle(), conversion);
        try (var balance = mockStatic(BalanceConfig.class);
                var resources = mockStatic(ResourceRuntime.class);
                var fluid = mockStatic(com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.class);
                var wanna = mockStatic(com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter.class)) {
            resources.when(() -> ResourceRuntime.pbCatalog(level)).thenReturn(mock(PbResourceCatalog.class));
            fluid.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.resolveFluidOutput(type, level))
                    .thenReturn(FluidStack.EMPTY);
            wanna.when(() -> com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter.prepareBatch(
                    eq(level), any(), eq(feeder), any(), any())).thenReturn(snapshot);
            ticker.tick();
            ticker.tick();
            assertTrue(state.counts[0] > 0 && state.counts[0] < 10_000);
            assertEquals((10_000L - state.counts[0]) * 1_000_000_000L, delivered[0]);
            verify(snapshot, times(1)).sample(eq(level), any(), anyFloat());
            wanna.verify(() -> com.ayoshiko.productivebeesgenesis.util.WannaBeeAmberAdapter.prepareBatch(
                    eq(level), any(), eq(feeder), any(), any()), times(1));
            state.load(registries(), state.save(registries()));
            ticker.tick();
            assertEquals(0, state.total());
            assertEquals(10_000_000_000_000L, delivered[0]);
            verify(conversion, never()).processGroupConversions(any(), any(), any(), any(), any());
            assertFalse(state.suspended());
        }
    }

    @Test void myriadFallbackPreservesBothFailuresAndKeepsSuccessfulRecovery() {
        var level = mock(Level.class);
        when(level.getRandom()).thenReturn(RandomSource.create(19));
        var type = ResourceLocation.parse("productivebees:iron");
        var cache = mock(MyriadBeeTypeCache.BeeTypeCacheSnapshot.class);
        when(cache.beeTypes()).thenReturn(List.of(type));
        when(cache.combBlockBeeTypes()).thenReturn(List.of(type));
        var selector = mock(WeightedTypeSelector.class);
        var original = new IllegalStateException("primary random selection failure");
        var secondary = new IllegalStateException("fallback random output failure");
        when(selector.selectForProcess(anyInt(), anyInt(), eq(level), any(), any())).thenThrow(original);
        try (var events = mockStatic(MyriadCreationsEventHandler.class);
                var caches = mockStatic(MyriadBeeTypeCache.class);
                var selectors = mockStatic(WeightedTypeSelector.class)) {
            events.when(MyriadCreationsEventHandler::isMyriadCreationsEnabled).thenReturn(true);
            caches.when(MyriadBeeTypeCache::snapshot).thenReturn(cache);
            selectors.when(WeightedTypeSelector::getInstance).thenReturn(selector);
            for (boolean blocks : new boolean[]{false, true}) {
                var builder = new MyriadAggregatedStacksBuilder();
                if (blocks) {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(anyInt(), any()))
                            .thenThrow(secondary);
                } else {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(anyInt(), any()))
                            .thenThrow(secondary);
                }
                var failure = assertThrows(IllegalStateException.class, () -> {
                    if (blocks) builder.buildAggregatedCombBlocks(3, level, this);
                    else builder.buildAggregatedHoneycombs(3, level, this);
                });
                assertSame(original, failure.getCause());
                assertArrayEquals(new Throwable[]{secondary}, failure.getSuppressed());
                ItemStack output = new ItemStack(blocks ? Blocks.HONEYCOMB_BLOCK.asItem() : Items.HONEYCOMB, blocks ? 12 : 3);
                if (blocks) {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(eq(12), any()))
                            .thenReturn(List.of(output));
                } else {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(eq(3), any()))
                            .thenReturn(List.of(output));
                }
                var recovered = blocks ? builder.buildAggregatedCombBlocks(3, level, this)
                        : builder.buildAggregatedHoneycombs(3, level, this);
                assertEquals(1, recovered.size());
                assertTrue(ItemStack.matches(output, recovered.getFirst()));
            }
        }
    }

    @Test void failedMyriadFallbackKeepsPaidCyclesAndHealthyGroupProductionAcrossRecovery() {
        for (boolean blocks : new boolean[]{false, true}) {
            var source = tile();
            var level = mock(Level.class);
            when(level.registryAccess()).thenReturn(registries());
            when(level.getRecipeManager()).thenReturn(mock(RecipeManager.class));
            when(level.getRandom()).thenReturn(RandomSource.create(31));
            source.setLevel(level);
            var tile = spy(source);
            tile.setCentrifugePriorityEnabled(false);
            tile.setDirectContainerOutputEnabled(true);
            doReturn(true).when(tile).canFunction();
            doReturn(false).when(tile).shouldHoldForCentrifuge(any());
            long[] delivered = {0, 0, 0};
            doAnswer(call -> {
                ItemStack offered = call.getArgument(0);
                if (offered.is(Items.DIAMOND)) delivered[0] += offered.getCount();
                else if (offered.is(Items.EMERALD)) delivered[1] += offered.getCount();
                else delivered[2] += offered.getCount();
                return offered.getCount();
            }).when(tile).pushGeneratedItemToNeighbors(any());
            var manager = tile.getSlotManager();
            var feeder = tile.getFeederSlotManager();
            var processor = spy(new BeeProduceProcessor(tile.upgradeHandler, tile));
            var healthy = ResourceLocation.parse("productivebees:emerald");
            doReturn(Map.of(new ItemStack(Items.DIAMOND), new ChancedOutput(Ingredient.of(Items.DIAMOND), 1, 1, 1)))
                    .when(processor).getCachedProduce(PBConstants.MYRIADCREATIONS_TYPE, level, feeder);
            doReturn(Map.of(new ItemStack(Items.EMERALD), new ChancedOutput(Ingredient.of(Items.EMERALD), 1, 1, 1)))
                    .when(processor).getCachedProduce(healthy, level, feeder);
            var state = processor.production;
            var indices = new OrderedSlotIndex();
            for (int i = 0; i < 2; i++) {
                var data = new CompoundTag();
                data.putString("entity", "productivebees:configurable_bee");
                data.putString("type", i == 0 ? PBConstants.MYRIADCREATIONS_TYPE_STRING : healthy.toString());
                var bee = manager.getBeeSlots()[i];
                bee.setBeeData(data);
                state.counts[i] = i == 0 ? 3 : 5;
                state.capture(i, bee);
                indices.reset(state.counts.length);
                indices.add(i);
                state.begin(indices);
                state.converted();
                state.fail(new IllegalStateException("old paid generation interrupted"));
            }
            var cache = mock(MyriadBeeTypeCache.BeeTypeCacheSnapshot.class);
            when(cache.beeTypes()).thenReturn(List.of(healthy));
            when(cache.combBlockBeeTypes()).thenReturn(List.of(healthy));
            var selector = mock(WeightedTypeSelector.class);
            when(selector.selectForProcess(anyInt(), anyInt(), eq(level), any(), any()))
                    .thenThrow(new IllegalStateException("primary random selection failure"));
            var conversion = spy(new ApiaryConversionProcessor(tile, manager, feeder));
            var ticker = new BeeSlotTickProcessor(tile, manager, processor, tile.upgradeHandler, feeder,
                    new ApiaryBeeActivationCounter(manager.getBeeSlotCount()), new LogThrottle(), conversion);
            var failedBee = state.bees[0].getBeeData().copy();
            var snapshot = new ApiaryBatchUpgradeSnapshot(0, false, false, 1, false, blocks, false, false, true, false);
            try (var balance = mockStatic(BalanceConfig.class);
                    var fluids = mockStatic(com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.class);
                    var upgrades = mockStatic(ApiaryBatchUpgradeSnapshot.class);
                    var events = mockStatic(MyriadCreationsEventHandler.class);
                    var caches = mockStatic(MyriadBeeTypeCache.class);
                    var selectors = mockStatic(WeightedTypeSelector.class)) {
                fluids.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeFluidOutputResolver.resolveFluidOutput(any(), any()))
                        .thenReturn(FluidStack.EMPTY);
                upgrades.when(() -> ApiaryBatchUpgradeSnapshot.capture(tile, tile.upgradeHandler)).thenReturn(snapshot);
                events.when(MyriadCreationsEventHandler::isMyriadCreationsEnabled).thenReturn(true);
                caches.when(MyriadBeeTypeCache::snapshot).thenReturn(cache);
                selectors.when(WeightedTypeSelector::getInstance).thenReturn(selector);
                if (blocks) {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(anyInt(), any()))
                            .thenThrow(new IllegalStateException("fallback random output failure"));
                } else {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(anyInt(), any()))
                            .thenThrow(new IllegalStateException("fallback random output failure"));
                }
                for (int tick = 0; tick < 10; tick++) {
                    ticker.tick();
                }
                if (blocks) {
                    events.verify(() -> MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(eq(12), any()), times(1));
                } else {
                    events.verify(() -> MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(eq(3), any()), times(1));
                }
                assertEquals(3, state.counts[0]);
                assertEquals(failedBee, state.bees[0].getBeeData());
                assertFalse(state.myriadCompleted(0, false));
                assertFalse(state.active());
                assertFalse(state.hasFrozen());
                assertEquals(0, state.counts[1]);
                assertTrue(state.bees[1].isEmpty());
                assertArrayEquals(new long[]{0, 5, 0}, delivered);
                assertTrue(manager.getOutputSlots().stream().allMatch(slot -> slot.getStack().isEmpty()));
                assertEquals(BeeState.PREPARATION_FAILED, manager.getBeeSlots()[0].getState());
                var saved = state.save(registries());
                state.load(registries(), saved);
                assertEquals(3, state.counts[0]);
                assertEquals(failedBee, state.bees[0].getBeeData());
                assertEquals(0, state.counts[1]);
                assertTrue(state.bees[1].isEmpty());
                assertFalse(state.myriadCompleted(0, false));
                ItemStack randomOutput = new ItemStack(blocks ? Blocks.HONEYCOMB_BLOCK.asItem() : Items.HONEYCOMB, blocks ? 12 : 3);
                if (blocks) {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomCombBlocks(eq(12), any()))
                            .thenReturn(List.of(randomOutput));
                } else {
                    events.when(() -> MyriadCreationsEventHandler.getAggregatedRandomHoneycombs(eq(3), any()))
                            .thenReturn(List.of(randomOutput));
                }
                for (int tick = 0; tick < 10; tick++) {
                    ticker.tick();
                }
                assertEquals(0, state.total());
                assertArrayEquals(new long[]{3, 5, blocks ? 12 : 3}, delivered);
                assertFalse(state.suspended());
                verify(conversion, never()).processGroupConversions(any(), any(), any(), any(), any());
            }
        }
    }

    @Test void myriadSuffixKeepsOneBatchBonusAcrossReloadAndRemovedBee() {
        var state = new ApiaryProductionState(1, () -> {});
        var bee = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:configurable_bee");
        data.putString("type", "productivebees:myriadcreations");
        bee.setBeeData(data);
        state.counts[0] = 10_000;
        state.capture(0, bee);
        var indices = new OrderedSlotIndex();
        indices.reset(1);
        indices.add(0);
        state.begin(indices);
        state.converted();
        state.freeze(new BeeProduceProcessor.Generated(List.of(named(21)), FluidStack.EMPTY, 0), new int[]{4_000}, true);
        assertEquals(6_000, state.counts[0]);
        assertTrue(state.myriadCompleted(0, false));
        state.items().clear();
        state.itemsChanged();
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), state.save(registries()));
        assertTrue(restored.myriadCompleted(0, false));
        bee.clear();
        restored.detachReplaced(new BeeSlot[]{bee});
        assertEquals(0, restored.counts[0]);
        assertEquals(6_000, restored.retained().counts[0]);
        assertTrue(restored.myriadCompleted(0, true));
        restored.retained().accept(0, 3, data.copy(), true, false, index -> true);
        assertEquals(6_000, restored.retained().counts[0]);
        assertEquals(3, restored.retained().counts[1]);
        assertFalse(restored.myriadCompleted(1, true));
        var finalState = new ApiaryProductionState(1, () -> {});
        finalState.load(registries(), restored.save(registries()));
        indices.reset(ApiaryRetainedCycles.MAX_GROUPS);
        indices.add(0);
        finalState.begin(indices, true);
        assertTrue(finalState.activeMyriadCompleted());
        finalState.freeze(new BeeProduceProcessor.Generated(List.of(), FluidStack.EMPTY, 0), new int[]{6_000}, false);
        assertEquals(3, finalState.total());
        assertFalse(finalState.myriadCompleted(1, true));
    }

    @Test void newRetainedCyclesNeverMergeIntoAnUnknownOlderGroup() {
        var state = new ApiaryProductionState(1, () -> {});
        var slot = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:lumber_bee");
        slot.setBeeData(data);
        state.counts[0] = 2;
        state.capture(0, slot);
        slot.clear();
        state.detachReplaced(new BeeSlot[]{slot});
        var group = new OrderedSlotIndex();
        group.reset(ApiaryRetainedCycles.MAX_GROUPS);
        group.add(0);
        state.begin(group, true);
        state.fail(new IllegalStateException("older conversion outcome unknown"));
        var unknown = state.request();
        slot.setBeeData(data.copy());
        state.counts[0] = 3;
        state.capture(0, slot);
        slot.clear();
        state.detachReplaced(new BeeSlot[]{slot});
        assertEquals(2, state.retained().counts[0]);
        assertEquals(3, state.retained().counts[1]);
        assertEquals(0, state.counts[0]);
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), state.save(registries()));
        restored.acknowledgeSettled(unknown);
        assertEquals(3, restored.total());
        assertEquals(3, restored.retained().counts[1]);
        group.reset(ApiaryRetainedCycles.MAX_GROUPS);
        group.add(1);
        restored.begin(group, true);
        restored.converted();
        restored.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.OAK_LOG, 3)), FluidStack.EMPTY, 0));
        assertEquals(3, restored.items().getFirst().getCount());
        assertEquals(0, restored.total());
    }

    @Test void conversionPreparationFailureDoesNotPublishAnotherBeeCachedMatch() {
        var tile = mock(TileEntityMekApiary.class);
        when(tile.isFeederConversionEnabled()).thenReturn(true);
        var manager = mock(ApiarySlotManager.class);
        when(manager.getBeeSlotCount()).thenReturn(1);
        var feeder = mock(FeederSlotManager.class);
        when(feeder.hasAnyFlower()).thenReturn(true);
        var slot = spy(FeederInventorySlot.create(null));
        slot.setStack(new ItemStack(Items.STONE, 2));
        when(feeder.getFeederInventorySlots()).thenReturn(List.of(slot));
        var first = ResourceLocation.parse("test:first");
        var second = ResourceLocation.parse("test:second");
        var firstRecipe = new net.minecraft.world.item.crafting.RecipeHolder<>(first,
                new cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe(List.of(), Ingredient.of(Items.STONE),
                        new ItemStack(Items.DIAMOND), 1, false));
        var secondRecipe = new net.minecraft.world.item.crafting.RecipeHolder<>(second,
                new cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe(List.of(), Ingredient.of(Items.STONE),
                        new ItemStack(Items.EMERALD), 1, false));
        var conversion = new ApiaryConversionProcessor(tile, manager, feeder);
        var level = mock(Level.class);
        try (var queries = mockStatic(com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries.class)) {
            queries.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries.hasAnyConversionRecipe(any())).thenReturn(true);
            queries.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries.hasItemConversionRecipes(any())).thenReturn(true);
            queries.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries.findItemConversionRecipe(eq(first), any())).thenReturn(firstRecipe);
            queries.when(() -> com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries.findItemConversionRecipe(eq(second), any())).thenReturn(secondRecipe);
            assertTrue(conversion.prepareResource(first, level, 0, 1).maximumOutput().getFirst().is(Items.DIAMOND));
            boolean[] unreadable = {true};
            doAnswer(call -> {
                if (unreadable[0]) throw new IllegalStateException("temporary feeder read failure");
                return call.callRealMethod();
            }).when(slot).getStack();
            assertThrows(IllegalStateException.class, () -> conversion.prepareResource(second, level, 0, 1));
            unreadable[0] = false;
            var recovered = conversion.prepareResource(second, level, 0, 1);
            assertNotNull(recovered);
            assertTrue(recovered.maximumOutput().getFirst().is(Items.EMERALD));
            assertEquals(2, slot.getCount());
        }
    }

    @Test void paidBeeSnapshotRejectsReplacementAndSurvivesReload() {
        var state = new ApiaryProductionState(1, () -> {});
        var bee = new BeeSlot();
        var data = new CompoundTag();
        data.putString("id", "productivebees:configurable_bee");
        data.putString("type", "example:first");
        bee.setBeeData(data);
        state.counts[0] = 4;
        state.capture(0, bee);
        assertTrue(state.canAdvance(0, bee));
        assertNotSame(data, state.bees[0].getBeeData());
        state.releaseEmptyBee(0);
        assertTrue(state.canAdvance(0, bee));
        var replacement = data.copy();
        replacement.putString("type", "example:second");
        bee.setBeeData(replacement);
        assertFalse(state.canAdvance(0, bee));
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), state.save(registries()));
        assertFalse(restored.canAdvance(0, bee));
        bee.setBeeData(data.copy());
        assertTrue(restored.canAdvance(0, bee));
        assertTrue(restored.canAdvance(0, bee));
        var group = new OrderedSlotIndex();
        group.reset(1);
        group.add(0);
        state.begin(group);
        state.converted();
        state.freeze(new BeeProduceProcessor.Generated(List.of(), FluidStack.EMPTY, 0));
        state.counts[0] = 3;
        state.capture(0, bee);
        assertEquals(data, state.bees[0].getBeeData());
    }

    @Test void boundedScanIsFairAcrossHeldPrefixAndDoesNotAllocateNewOwners() {
        var tile = tile();
        for (int i = 0; i < 100; i++) tile.getOutputBuffer().accept(named(i));
        int[] scans = {0}, received = {0};
        var held = new java.util.HashSet<String>();
        for (int i = 0; i < 70; i++) held.add("group-" + i);
        for (int call = 0; call < 4; call++) {
            scans[0] = 0;
            tile.getOutputBuffer().pushToSink(stack -> { received[0] += stack.getCount(); return stack.getCount(); },
                    stack -> { scans[0]++; return held.contains(stack.get(DataComponents.CUSTOM_NAME).getString()); });
            assertTrue(scans[0] <= 32);
        }
        assertEquals(300, received[0]);
        assertEquals(70, tile.getOutputBuffer().getBufferedGroupCount());
    }

    @Test void removedBeeCyclesDetachWithoutBlockingReplacementAndSurviveUpgrade() {
        var state = new ApiaryProductionState(1, () -> {});
        var oldBee = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:lumber_bee");
        data.putString("evidence", "完整旧蜜蜂数据");
        oldBee.setBeeData(data);
        state.counts[0] = 61;
        state.capture(0, oldBee);
        oldBee.clear();
        state.detachReplaced(new BeeSlot[]{oldBee});
        assertEquals(0, state.counts[0]);
        assertEquals(61, state.total());
        assertEquals(data, state.retained().bees[0].getBeeData());
        var replacement = data.copy();
        replacement.putString("entity", "productivebees:configurable_bee");
        replacement.putString("type", "productivebees:emerald");
        oldBee.setBeeData(replacement);
        assertTrue(state.canAdvance(0, oldBee));
        state.counts[0] = 2;
        state.capture(0, oldBee);
        var restored = new ApiaryProductionState(3, () -> {});
        restored.load(registries(), state.save(registries()));
        assertFalse(restored.suspended());
        assertEquals(63, restored.total());
        assertEquals(data, restored.retained().bees[0].getBeeData());
        var group = new OrderedSlotIndex();
        group.reset(ApiaryRetainedCycles.MAX_GROUPS);
        group.add(0);
        restored.begin(group, true);
        restored.converted();
        restored.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.OAK_LOG, 61)), FluidStack.EMPTY, 0));
        assertEquals(0, restored.retained().counts[0]);
        assertEquals(2, restored.counts[0]);
        assertEquals(61, restored.items().getFirst().getCount());
    }

    @Test void retainedCapacityRefusesNewIdentityAndPreservesCyclesUntilSpaceReturns() {
        var state = new ApiaryProductionState(1, () -> {});
        var slot = new BeeSlot();
        for (int i = 0; i < ApiaryRetainedCycles.MAX_GROUPS; i++) {
            var bee = new CompoundTag();
            bee.putString("entity", "productivebees:lumber_bee");
            bee.putInt("identity", i);
            slot.setBeeData(bee);
            state.counts[0] = 1;
            state.capture(0, slot);
            slot.clear();
            state.detachReplaced(new BeeSlot[]{slot});
        }
        assertEquals(ApiaryRetainedCycles.MAX_GROUPS, state.retained().groups());
        var data = new CompoundTag();
        data.putString("entity", "productivebees:quarry_bee");
        slot.setBeeData(data);
        state.counts[0] = 4;
        state.capture(0, slot);
        slot.clear();
        state.detachReplaced(new BeeSlot[]{slot});
        assertEquals(4, state.counts[0]);
        assertEquals(132, state.total());
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), state.save(registries()));
        assertFalse(restored.suspended());
        assertEquals(132, restored.total());
        restored.retained().clear(0);
        restored.detachReplaced(new BeeSlot[]{slot});
        assertEquals(0, restored.counts[0]);
        assertEquals(131, restored.total());
        assertEquals(data, restored.retained().bees[0].getBeeData());
    }

    @Test void retainedConversionUnknownKeepsZeroSuffixRequestAndNeverDetachesOrReplays() {
        var state = new ApiaryProductionState(1, () -> {});
        var slot = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:lumber_bee");
        slot.setBeeData(data);
        state.counts[0] = 1;
        state.capture(0, slot);
        slot.clear();
        state.detachReplaced(new BeeSlot[]{slot});
        var group = new OrderedSlotIndex();
        group.reset(ApiaryRetainedCycles.MAX_GROUPS);
        group.add(0);
        state.begin(group, true);
        state.retained().counts[0] = 0;
        state.fail(new IllegalStateException("conversion outcome unknown"));
        var failedRequest = state.request();
        var saved = state.save(registries());
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), saved);
        assertTrue(restored.suspended());
        assertEquals(failedRequest, restored.request());
        assertEquals(saved, restored.save(registries()));
        restored.detachReplaced(new BeeSlot[]{slot});
        assertEquals(saved, restored.save(registries()));
        assertThrows(IllegalStateException.class, () -> restored.begin(group, true));
        assertEquals(data, restored.retained().bees[0].getBeeData());

        var nextBee = new CompoundTag();
        nextBee.putString("entity", "productivebees:quarry_bee");
        assertTrue(restored.retained().accept(0, 2, nextBee));
        assertEquals(0, restored.retained().counts[0]);
        assertEquals(data, restored.retained().bees[0].getBeeData());
        assertEquals(2, restored.retained().counts[1]);
        assertEquals(nextBee, restored.retained().bees[1].getBeeData());

        slot.setBeeData(nextBee.copy());
        restored.counts[0] = 3;
        restored.capture(0, slot);
        group.reset(1);
        group.add(0);
        restored.begin(group);
        restored.converted();
        restored.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.DIAMOND, 3)), FluidStack.EMPTY, 0));
        assertEquals(3, restored.items().getFirst().getCount());
        assertEquals(0, restored.counts[0]);
        restored.items().getFirst().setCount(0);
        restored.itemsChanged();

        group.reset(ApiaryRetainedCycles.MAX_GROUPS);
        group.add(1);
        restored.begin(group, true);
        restored.converted();
        restored.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.COBBLESTONE, 2)), FluidStack.EMPTY, 0));
        assertEquals(0, restored.retained().counts[1]);
        assertEquals(2, restored.items().getFirst().getCount());
        assertTrue(restored.groupSuspended(0, true));
        assertEquals(data, restored.retained().bees[0].getBeeData());
        restored.acknowledgeSettled(failedRequest);
        assertFalse(restored.suspended());
        assertNull(restored.retained().bees[0].getBeeData());
        assertThrows(IllegalStateException.class, () -> restored.acknowledgeSettled(failedRequest));
        assertEquals(2, restored.items().getFirst().getCount());
    }

    @Test void legacyPaidSnapshotMigratesAndMalformedRetainedStateKeepsOriginal() {
        var state = new ApiaryProductionState(1, () -> {});
        var slot = new BeeSlot();
        var data = new CompoundTag();
        data.putString("entity", "productivebees:quarry_bee");
        slot.setBeeData(data);
        state.counts[0] = 20;
        state.capture(0, slot);
        var legacy = state.save(registries());
        legacy.putInt("version", 1);
        legacy.remove("live_slots");
        legacy.remove("retained");
        var migrated = new ApiaryProductionState(1, () -> {});
        migrated.load(registries(), legacy);
        assertFalse(migrated.suspended());
        assertEquals(20, migrated.total());
        slot.clear();
        migrated.detachReplaced(new BeeSlot[]{slot});
        assertEquals(20, migrated.retained().total());
        var damaged = migrated.save(registries());
        damaged.getList("retained", 10).getCompound(0).putLong("count", 4_294_967_316L);
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), damaged);
        assertTrue(restored.suspended());
        assertEquals(damaged, restored.save(registries()));
        var zero = migrated.save(registries());
        zero.getList("retained", 10).getCompound(0).putInt("count", 0);
        var invalidZero = new ApiaryProductionState(1, () -> {});
        invalidZero.load(registries(), zero);
        assertTrue(invalidZero.suspended());
        assertEquals(zero, invalidZero.save(registries()));
    }

    @Test void legacyItemPlacementMergeMigratesWithoutLosingPaidBeeSnapshot() {
        var source = tile();
        var state = source.productionState();
        var bee = new CompoundTag();
        bee.putString("entity", "productivebees:quarry_bee");
        bee.putString("evidence", "旧扳手机器的完整蜜蜂数据");
        state.counts[0] = 61;
        state.bees[0].setBeeData(bee);
        var legacyRoot = source.saveCustomOnly(registries());
        var legacy = legacyRoot.getCompound(ApiaryOutputBuffer.nbtKey()).getCompound("production");
        legacy.putInt("version", 1);
        legacy.remove("live_slots");
        legacy.remove("retained");
        var placed = tile();
        assertTrue(net.minecraft.world.item.component.CustomData.of(legacyRoot).loadInto(placed, registries()));
        assertFalse(placed.productionState().suspended());
        assertEquals(61, placed.productionState().total());
        assertEquals(bee, placed.productionState().bees[0].getBeeData());
        var migrated = placed.productionState().save(registries());
        var restored = tile();
        restored.productionState().load(registries(), migrated);
        assertFalse(restored.productionState().suspended());
        assertEquals(migrated, restored.productionState().save(registries()));
    }

    @Test void mergedLegacyFieldsRejectAssetsWrongTypesAndUnexpectedSlotCounts() {
        var state = new ApiaryProductionState(3, () -> {});
        var legacy = state.save(registries());
        legacy.putInt("version", 1);
        var illegalRetained = new CompoundTag();
        illegalRetained.putString("evidence", "不能丢弃的旧资产");
        for (int variant = 0; variant < 4; variant++) {
            var damaged = legacy.copy();
            switch (variant) {
                case 0 -> damaged.getList("retained", 10).add(illegalRetained.copy());
                case 1 -> damaged.putString("retained", "原始保管数据");
                case 2 -> damaged.putInt("live_slots", 2);
                case 3 -> damaged.putBoolean("retained_request", false);
                default -> throw new AssertionError(variant);
            }
            var restored = new ApiaryProductionState(3, () -> {});
            restored.load(registries(), damaged);
            assertTrue(restored.suspended());
            assertEquals(damaged, restored.save(registries()));
        }
    }

    @Test void legacyRequestMergedWithDefaultsRemainsSuspendedAndCannotReplay() {
        var state = new ApiaryProductionState(3, () -> {});
        var bee = new CompoundTag();
        bee.putString("entity", "productivebees:configurable_bee");
        bee.putString("type", "productivebees:emerald");
        state.counts[0] = 4;
        state.bees[0].setBeeData(bee);
        var group = new OrderedSlotIndex();
        group.reset(3);
        group.add(0);
        state.begin(group);
        state.fail(new IllegalStateException("unknown external result"));
        var legacy = state.save(registries());
        legacy.putInt("version", 1);
        var held = legacy.getList("held_groups", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0);
        legacy.putUUID("request", held.getUUID("request"));
        legacy.putIntArray("active", held.getIntArray("active"));
        legacy.putString("phase", held.getString("phase"));
        legacy.putString("failure", held.getString("failure"));
        legacy.remove("held_groups");
        legacy.remove("retained_request");
        var restored = new ApiaryProductionState(3, () -> {});
        restored.load(registries(), legacy);
        assertTrue(restored.suspended());
        assertEquals(state.request(), restored.request());
        assertEquals(state.failure(), restored.failure());
        assertEquals(4, restored.total());
        assertThrows(IllegalStateException.class, () -> restored.begin(group));
        assertEquals(bee, restored.bees[0].getBeeData());
    }

    @Test void firstMiddleAndLastDispatchFailuresPreserveRemainingGroups() throws Exception {
        for (int failing = 0; failing < 3; failing++) {
            var tile = tile();
            var level = mock(ServerLevel.class);
            when(level.registryAccess()).thenReturn(registries());
            tile.setLevel(level);
            var state = tile.productionState();
            for (int i = 0; i < 3; i++) {
                state.counts[i] = 4;
                var bee = new CompoundTag();
                bee.putString("entity", "productivebees:configurable_bee");
                bee.putString("type", "productivebees:iron");
                state.bees[i].setBeeData(bee);
            }
            var field = BasicInventorySlot.class.getDeclaredField("listener");
            field.setAccessible(true);
            int failingIndex = failing;
            int[] failures = {0};
            for (var slot : tile.getOutputSlots()) {
                var original = (IContentsListener) field.get(slot);
                field.set(slot, (IContentsListener) () -> {
                    if (original != null) original.onContentsChanged();
                    var name = slot.getStack().get(DataComponents.CUSTOM_NAME);
                    if (name != null && name.getString().equals("group-" + failingIndex)) {
                        failures[0]++;
                        throw new IllegalStateException("after local write");
                    }
                });
            }
            var upgrades = ApiaryBatchUpgradeSnapshot.capture(tile, tile.upgradeHandler);
            for (int i = 0; i < 3; i++) {
                var group = new OrderedSlotIndex();
                group.reset(state.counts.length);
                group.add(i);
                state.begin(group);
                state.converted();
                state.freeze(new BeeProduceProcessor.Generated(List.of(named(i)), FluidStack.EMPTY, 0));
                assertTrue(tile.getProduceProcessor().flushFrozen(upgrades));
            }
            assertEquals(1, failures[0]);
            var custody = OutputCustody.of(tile);
            assertFalse(custody.blocked());
            assertFalse(custody.localOutputBlocked());
            assertEquals(0, custody.amount());
            assertTrue(custody.requests().isEmpty());
            for (int i = 0; i < 3; i++) {
                var template = named(i);
                long amount = tile.getOutputSlots().stream()
                        .map(slot -> slot.getStack())
                        .filter(stack -> ItemStack.isSameItemSameComponents(stack, template))
                        .mapToLong(ItemStack::getCount)
                        .sum();
                assertEquals(10, amount);
                assertEquals(0, state.counts[i]);
            }
            assertFalse(state.hasFrozen());
            assertTrue(tile.getProduceProcessor().flushFrozen(upgrades));
            assertEquals(1, failures[0]);
            assertTrue(custody.requests().isEmpty());

            var saved = state.save(registries());
            var next = new ApiaryProductionState(state.counts.length, () -> {});
            next.load(registries(), saved);
            assertArrayEquals(state.counts, next.counts);
            var components = new CompoundTag();
            tile.getComponents().forEach(component -> component.write(components, registries()));
            var restored = tile();
            restored.getComponents().forEach(component -> component.read(components, registries()));
            assertFalse(OutputCustody.of(restored).blocked());
            assertTrue(OutputCustody.of(restored).requests().isEmpty());
        }
    }

    @Test void legacyZeroEnergyLocalOutputRecoversOriginalProgressAndDeliversOnce() {
        for (int savedTicks : new int[]{11, 3}) {
            var source = tile();
            var bee = new CompoundTag();
            bee.putString("entity", "productivebees:configurable_bee");
            bee.putString("type", "productivebees:iron");
            var item = named(0);
            var fluid = new FluidStack(ModFluids.HONEY.get(), 1);
            var generated = new BeeProduceProcessor.Generated(List.of(item), fluid, 250);
            var context = new CompoundTag();
            context.putString("host", "mek_apiary");
            context.putString("bee", "productivebees:iron");
            context.put("bee_data", bee.copy());
            context.putInt("slot", 0);
            context.putInt("previous_ticks", 11);
            context.putInt("next_ticks", 3);
            context.putLong("cycles", 1);
            context.putLong("energy_before", 0);
            context.putLong("energy_cost", 0);
            var mixed = new ApiaryRoutedOutput(source, generated, ApiaryBatchUpgradeSnapshot.capture(source, source.upgradeHandler));
            context.put("mixed_local", mixed.context(registries()));
            var request = source.productionState().beginLocal(0, context, generated);
            source.productionState().fail(new IllegalStateException("legacy save callback unavailable"));
            var saved = source.productionState().save(registries());
            saved.putInt("version", 2);
            saved.remove("held_groups");
            var restored = tile();
            var level = mock(ServerLevel.class);
            when(level.registryAccess()).thenReturn(registries());
            restored.setLevel(level);
            restored.getBeeSlots()[0].setBeeData(bee.copy());
            restored.getBeeSlots()[0].setTicksInHive(savedTicks);
            restored.productionState().load(registries(), saved);
            assertEquals(request, restored.productionState().request());
            assertTrue(restored.productionState().frozenOutputBlocked());
            assertTrue(restored.productionState().recoverLocal(restored.getBeeSlots(), registries()));
            assertEquals(3, restored.getBeeSlots()[0].getTicksInHive());
            assertEquals(bee, restored.getBeeSlots()[0].getBeeData());
            assertFalse(restored.productionState().frozenOutputBlocked());
            assertTrue(ItemStack.matches(item, restored.productionState().items().getFirst()));
            assertEquals(250, restored.productionState().fluidAmount());
            var afterRecovery = restored.productionState().save(registries());
            var reloaded = tile();
            reloaded.setLevel(level);
            reloaded.productionState().load(registries(), afterRecovery);
            var local = new ApiaryBatchUpgradeSnapshot(0, false, false, 1, false, false, false, false, false, false);
            assertTrue(reloaded.getProduceProcessor().flushFrozen(local));
            assertEquals(10, reloaded.getOutputSlots().stream().map(slot -> slot.getStack())
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, item)).mapToInt(ItemStack::getCount).sum());
            assertEquals(250, reloaded.getFluidTank().getFluidAmount());
            assertFalse(reloaded.productionState().recoverLocal(reloaded.getBeeSlots(), registries()));
            assertTrue(reloaded.getProduceProcessor().flushFrozen(local));
            assertEquals(10, reloaded.getOutputSlots().stream().map(slot -> slot.getStack())
                    .filter(stack -> ItemStack.isSameItemSameComponents(stack, item)).mapToInt(ItemStack::getCount).sum());
            assertEquals(250, reloaded.getFluidTank().getFluidAmount());
            assertFalse(reloaded.productionState().hasFrozen());
        }
    }

    @Test void localRecoveryKeepsPaidFlowerPartialOutputAndChangedBeeRequestsHeld() {
        var source = tile();
        var bee = new CompoundTag();
        bee.putString("entity", "productivebees:configurable_bee");
        bee.putString("type", "productivebees:iron");
        var generated = new BeeProduceProcessor.Generated(List.of(named(0)), FluidStack.EMPTY, 0);
        var context = new CompoundTag();
        context.putString("host", "mek_apiary");
        context.putString("bee", "productivebees:iron");
        context.put("bee_data", bee.copy());
        context.putInt("slot", 0);
        context.putInt("previous_ticks", 11);
        context.putInt("next_ticks", 3);
        context.putLong("cycles", 1);
        context.putLong("energy_before", 10);
        context.putLong("energy_cost", 0);
        context.put("mixed_local", new ApiaryRoutedOutput(source, generated,
                ApiaryBatchUpgradeSnapshot.capture(source, source.upgradeHandler)).context(registries()));
        var request = source.productionState().beginLocal(0, context, generated);
        var saved = source.productionState().save(registries());
        for (int variant = 0; variant < 5; variant++) {
            var held = saved.copy();
            var restored = tile();
            restored.getBeeSlots()[0].setBeeData(bee.copy());
            restored.getBeeSlots()[0].setTicksInHive(11);
            switch (variant) {
                case 0 -> held.getCompound("local_context").putLong("energy_cost", 5);
                case 1 -> held.getCompound("local_context").put("flower", new CompoundTag());
                case 2 -> held.getList("items", 10).getCompound(0).putInt("count", 5);
                case 3 -> restored.getBeeSlots()[0].getBeeData().putString("type", "productivebees:gold");
                case 4 -> held.getCompound("local_context").putInt("energy_cost", 0);
                default -> throw new AssertionError(variant);
            }
            restored.productionState().load(registries(), held);
            assertFalse(restored.productionState().recoverLocal(restored.getBeeSlots(), registries()));
            assertEquals(request, restored.productionState().request());
            assertTrue(restored.productionState().frozenOutputBlocked());
            assertEquals(held, restored.productionState().save(registries()));
            assertEquals(11, restored.getBeeSlots()[0].getTicksInHive());
        }
    }

    @Test void paidGroupPreparationFailureRetriesThroughTickerWithoutStoppingHealthyGroup() {
        for (int version = 1; version <= 3; version++) {
            var source = tile();
            var level = mock(Level.class);
            var recipes = mock(RecipeManager.class);
            when(level.registryAccess()).thenReturn(registries());
            when(level.getRecipeManager()).thenReturn(recipes);
            source.setLevel(level);
            var tile = spy(source);
            doReturn(true).when(tile).canFunction();
            var manager = tile.getSlotManager();
            var feeder = tile.getFeederSlotManager();
            var processor = spy(tile.getProduceProcessor());
            var failedType = ResourceLocation.parse("productivebees:iron");
            var healthyType = ResourceLocation.parse("productivebees:gold");
            var failedOutput = new ItemStack(Items.IRON_INGOT);
            var healthyOutput = new ItemStack(Items.GOLD_INGOT);
            var failedRecipe = new ChancedOutput(
                    Ingredient.of(failedOutput), 1, 1, 1);
            var healthyRecipe = new ChancedOutput(
                    Ingredient.of(healthyOutput), 1, 1, 1);
            doThrow(new IllegalStateException("recipe preparation failed"))
                    .when(processor).getCachedProduce(failedType, level, feeder);
            doReturn(Map.of(healthyOutput, healthyRecipe))
                    .when(processor).getCachedProduce(healthyType, level, feeder);
            var state = tile.productionState();
            for (int i = 0; i < 2; i++) {
                var data = new CompoundTag();
                data.putString("entity", "productivebees:configurable_bee");
                data.putString("type", (i == 0 ? failedType : healthyType).toString());
                var bee = manager.getBeeSlots()[i];
                bee.setBeeData(data);
                state.counts[i] = 4;
                state.capture(i, bee);
            }
            var group = new OrderedSlotIndex();
            group.reset(state.counts.length);
            group.add(0);
            group.add(2);
            state.bees[2].setBeeData(state.bees[0].getBeeData().copy());
            state.begin(group);
            state.converted();
            var oldRequest = state.request();
            var saved = state.save(registries());
            if (version < 3) {
                saved.putInt("version", version);
                saved.remove("held_groups");
                saved.getList("pending", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).remove("converted");
                if (version == 1) {
                    saved.remove("live_slots");
                    saved.remove("retained");
                    saved.remove("retained_request");
                }
            } else {
                var held = new CompoundTag();
                held.putUUID("request", oldRequest);
                held.putIntArray("active", new int[]{0, 2});
                held.putBoolean("retained", false);
                held.putString("phase", "generation");
                held.putString("failure", "old generation preparation failed");
                saved.getList("held_groups", net.minecraft.nbt.Tag.TAG_COMPOUND).add(held);
                saved.remove("request");
                saved.remove("active");
                saved.remove("phase");
                saved.remove("failure");
                saved.remove("retained_request");
            }
            if (version < 3) {
                var pending = saved.getList("pending", net.minecraft.nbt.Tag.TAG_COMPOUND);
                for (int i = pending.size() - 1; i >= 0; i--) {
                    if (pending.getCompound(i).getInt("count") == 0) pending.remove(i);
                }
            }
            var withFrozen = saved.copy();
            var frozen = new CompoundTag();
            frozen.put("item", failedOutput.save(registries()));
            frozen.putInt("count", 2);
            withFrozen.getList("items", net.minecraft.nbt.Tag.TAG_COMPOUND).add(frozen);
            var uncertain = new ApiaryProductionState(state.counts.length, () -> {});
            uncertain.load(registries(), withFrozen);
            assertTrue(uncertain.suspended());
            assertEquals(oldRequest, uncertain.request());
            assertEquals(4, uncertain.counts[0]);
            assertEquals(2, uncertain.items().getFirst().getCount());
            assertThrows(IllegalStateException.class, () -> uncertain.begin(group));
            state.load(registries(), saved);
            assertFalse(state.suspended());
            assertTrue(state.requestStatus().isEmpty());
            assertEquals(4, state.counts[0]);
            assertTrue(state.conversionCompleted(0, false));
            assertTrue(state.bees[2].isEmpty());
            assertThrows(IllegalStateException.class, () -> state.acknowledgeSettled(oldRequest));
            var migrated = state.save(registries());
            state.load(registries(), migrated);
            assertEquals(migrated, state.save(registries()));
            assertFalse(state.suspended());
            assertTrue(state.conversionCompleted(0, false));
            var conversion = spy(new ApiaryConversionProcessor(tile, manager, feeder));
            var ticker = new BeeSlotTickProcessor(tile, manager, processor, tile.upgradeHandler, feeder,
                    new ApiaryBeeActivationCounter(manager.getBeeSlotCount()),
                    new LogThrottle(), conversion);
            try (var config = mockStatic(BalanceConfig.class)) {
                for (int tick = 0; tick < 10; tick++) {
                    ticker.tick();
                }
                assertEquals(BeeState.PREPARATION_FAILED, manager.getBeeSlots()[0].getState());
                assertEquals(4, state.counts[0]);
                assertEquals(0, state.counts[1]);
                assertFalse(state.suspended());
                assertFalse(state.active());
                assertEquals(4, tile.getOutputSlots().stream()
                        .map(slot -> slot.getStack())
                        .filter(stack -> stack.is(Items.GOLD_INGOT))
                        .mapToLong(ItemStack::getCount)
                        .sum());
                doReturn(Map.of(failedOutput, failedRecipe))
                        .when(processor).getCachedProduce(failedType, level, feeder);
                for (int tick = 0; tick < 10; tick++) {
                    ticker.tick();
                }
                verify(conversion, never()).processGroupConversions(eq(failedType), any(), any(), any(), any());
                assertEquals(0, state.total());
                assertFalse(state.suspended());
                assertEquals(4, tile.getOutputSlots().stream()
                        .map(slot -> slot.getStack())
                        .filter(stack -> stack.is(Items.IRON_INGOT))
                        .mapToLong(ItemStack::getCount)
                        .sum());
                assertEquals(4, tile.getOutputSlots().stream()
                        .map(slot -> slot.getStack())
                        .filter(stack -> stack.is(Items.GOLD_INGOT))
                        .mapToLong(ItemStack::getCount)
                        .sum());
            }
        }
    }

    @Test void bufferCallbackUnknownIsRemovedFromRetryInventoryAndSavedInComponent() {
        var tile = tile();
        tile.getOutputBuffer().accept(new ItemStack(Items.DIAMOND, 10));
        var result = tile.getOutputBuffer().pushToSink(stack -> { throw new IllegalStateException("unknown"); }, null);
        assertEquals(10, result.pushed());
        assertEquals(0, tile.getOutputBuffer().getBufferedGroupCount());
        assertEquals(10, OutputCustody.of(tile).amount());
        var tag = new CompoundTag();
        tile.getComponents().forEach(component -> component.write(tag, registries()));
        var next = tile();
        next.getComponents().forEach(component -> component.read(tag, registries()));
        assertEquals(10, OutputCustody.of(next).amount());
        assertEquals(0, next.getOutputBuffer().pushToSink(stack -> fail("no retry"), null).pushed());
    }

    @Test void malformedPaidStateKeepsOriginalDataAndCannotReplay() {
        var state = new ApiaryProductionState(3, () -> {});
        state.counts[0] = 4;
        var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee");
        state.bees[0].setBeeData(bee);
        var group = new OrderedSlotIndex(); group.reset(3); group.add(0);
        state.begin(group);
        var valid = state.save(registries());
        List<java.util.function.Consumer<CompoundTag>> corruptions = List.of(
                tag -> tag.putString("pending", "原始未结算周期"),
                tag -> tag.put("items", new net.minecraft.nbt.IntArrayTag(new int[]{7})),
                tag -> tag.putString("request", "broken-request"),
                tag -> tag.putIntArray("active", new int[]{0, 0}),
                tag -> tag.getList("pending", 10).getCompound(0).putLong("count", 4_294_967_300L),
                tag -> tag.putString("fluid_amount", "unknown"));
        for (var corrupt : corruptions) {
            var damaged = valid.copy(); corrupt.accept(damaged);
            var restored = new ApiaryProductionState(3, () -> {});
            restored.load(registries(), damaged);
            assertTrue(restored.suspended());
            assertEquals(damaged, restored.save(registries()));
            assertThrows(IllegalStateException.class, () -> restored.acknowledgeSettled(state.request()));
        }
    }

    @Test void malformedProductionEnvelopeAndWideCountsSurviveBufferUpgrade() {
        for (boolean envelope : new boolean[]{true, false}) {
            var source = tile(); source.getOutputBuffer().accept(new ItemStack(Items.DIAMOND, 8));
            var damaged = source.getOutputBuffer().save(registries());
            if (envelope) damaged.putString("production", "原始已付批次");
            else damaged.getList("stacks", 10).getCompound(0)
                    .putLong("productivebeesgenesis_count", 4_294_967_304L);
            var restored = tile(); restored.getOutputBuffer().load(registries(), damaged);
            assertTrue(restored.getOutputBuffer().productionBlocked());
            assertEquals(damaged, restored.getOutputBuffer().save(registries()));
            var upgraded = tile();
            assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, upgraded.nbtSerializer().applyUpgradeData(registries(), restored.getUpgradeData(registries())));
            assertEquals(damaged, upgraded.getOutputBuffer().save(registries()));
            assertEquals(0, upgraded.getOutputBuffer().pushToSink(stack -> fail("isolated data"), null).pushed());
        }
    }

    @Test void malformedOuterFieldsReachCustodyThroughActualLoadAndUpgrade() {
        var source = tile();
        var raw = net.minecraft.nbt.StringTag.valueOf("不可解码的原始资产");
        var saved = new CompoundTag(); saved.put(ApiaryOutputBuffer.nbtKey(), raw);
        source.nbtSerializer().loadApiaryState(saved, registries());
        var component = source.getComponents().stream()
                .filter(com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector.class::isInstance)
                .map(com.ayoshiko.productivebeesgenesis.logistics.GenesisTileComponentEjector.class::cast).findFirst().orElseThrow();
        var componentTag = component.serialize(registries()); componentTag.put("output_custody", raw);
        component.deserialize(componentTag, registries());
        var upgraded = tile();
        assertEquals(ApiaryNbtSerializer.UpgradeResult.APPLIED, upgraded.nbtSerializer().applyUpgradeData(registries(), source.getUpgradeData(registries())));
        assertTrue(upgraded.getOutputBuffer().productionBlocked());
        assertTrue(OutputCustody.of(upgraded).blocked());
        assertEquals(raw, upgraded.getOutputBuffer().save(registries()).get("invalid_data"));
        assertEquals(raw, OutputCustody.of(upgraded).save(registries()).get("invalid_data"));
    }
}
