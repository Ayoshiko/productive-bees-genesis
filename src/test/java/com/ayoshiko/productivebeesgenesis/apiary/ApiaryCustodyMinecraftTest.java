package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(upgraded.nbtSerializer().applyUpgradeData(registries(), upgrade));
        assertEquals(first, upgraded.getOutputBuffer().save(registries()));
    }

    @Test void failedGroupRetainsUnstartedCyclesWhileCompletedGroupCannotReplay() {
        var state = new ApiaryProductionState(3, () -> {});
        for (int i = 0; i < 3; i++) {
            state.counts[i] = 4;
            var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee");
            state.bees[i].setBeeData(bee);
        }
        var group = new OrderedSlotIndex(); group.reset(3); group.add(0);
        state.begin(group); state.converted();
        state.freeze(new BeeProduceProcessor.Generated(List.of(new ItemStack(Items.DIAMOND, 4)), FluidStack.EMPTY, 0));
        state.items().getFirst().setCount(0); state.itemsChanged();
        group.reset(3); group.add(1);
        state.begin(group);
        state.fail(new IllegalStateException("flower callback failed"));
        assertArrayEquals(new int[]{0, 4, 4}, state.counts);
        var copy = new ApiaryProductionState(5, () -> {});
        copy.load(registries(), state.save(registries()));
        assertTrue(copy.suspended());
        assertArrayEquals(new int[]{0, 4, 4, 0, 0}, copy.counts);
        copy.acknowledgeSettled(copy.request());
        assertArrayEquals(new int[]{0, 0, 4, 0, 0}, copy.counts);
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
        state.fail(new IllegalStateException("conversion completed, generation outcome unknown"));
        var saved = state.save(registries());
        var restored = new ApiaryProductionState(1, () -> {});
        restored.load(registries(), saved);
        assertTrue(restored.suspended());
        assertEquals(state.request(), restored.request());
        assertEquals(saved, restored.save(registries()));
        restored.detachReplaced(new BeeSlot[]{slot});
        assertEquals(saved, restored.save(registries()));
        assertThrows(IllegalStateException.class, () -> restored.begin(group, true));
        restored.acknowledgeSettled(restored.request());
        assertFalse(restored.suspended());
        assertNull(restored.retained().bees[0].getBeeData());
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
        assertEquals(2, migrated.getInt("version"));
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
            var state = tile.productionState();
            for (int i = 0; i < 3; i++) {
                state.counts[i] = 4;
                var bee = new CompoundTag(); bee.putString("id", "productivebees:configurable_bee");
                state.bees[i].setBeeData(bee);
            }
            var field = mekanism.common.inventory.slot.BasicInventorySlot.class.getDeclaredField("listener");
            field.setAccessible(true);
            int failingIndex = failing;
            for (var slot : tile.getOutputSlots()) {
                var original = (mekanism.api.IContentsListener) field.get(slot);
                field.set(slot, (mekanism.api.IContentsListener) () -> {
                    if (original != null) original.onContentsChanged();
                    var name = slot.getStack().get(DataComponents.CUSTOM_NAME);
                    if (name != null && name.getString().equals("group-" + failingIndex)) throw new IllegalStateException("after local write");
                });
            }
            for (int i = 0; i <= failing; i++) {
                var group = new OrderedSlotIndex(); group.reset(3); group.add(i);
                state.begin(group); state.converted();
                state.freeze(new BeeProduceProcessor.Generated(List.of(named(i)), FluidStack.EMPTY, 0));
                tile.getProduceProcessor().flushFrozen(ApiaryBatchUpgradeSnapshot.capture(tile, tile.upgradeHandler));
            }
            assertTrue(OutputCustody.of(tile).blocked());
            assertEquals(10, OutputCustody.of(tile).amount());
            for (int i = 0; i < 3; i++) assertEquals(i <= failing ? 0 : 4, state.counts[i]);
            var saved = state.save(registries());
            var next = new ApiaryProductionState(3, () -> {}); next.load(registries(), saved);
            assertArrayEquals(state.counts, next.counts);
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
            assertTrue(upgraded.nbtSerializer().applyUpgradeData(registries(), restored.getUpgradeData(registries())));
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
        assertTrue(upgraded.nbtSerializer().applyUpgradeData(registries(), source.getUpgradeData(registries())));
        assertTrue(upgraded.getOutputBuffer().productionBlocked());
        assertTrue(OutputCustody.of(upgraded).blocked());
        assertEquals(raw, upgraded.getOutputBuffer().save(registries()).get("invalid_data"));
        assertEquals(raw, OutputCustody.of(upgraded).save(registries()).get("invalid_data"));
    }
}
