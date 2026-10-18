package com.ayoshiko.productivebeesgenesis.resource;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceInventoryMinecraftTest {
    private static Map<ResourceKey, Long> products(int size) {
        var values = new LinkedHashMap<ResourceKey, Long>();
        for (int i = 0; i < size; i++) values.put(ResourceKey.chemical(ResourceLocation.parse("example:chemical_" + i)), 100L + i);
        return Map.copyOf(values);
    }
    private static Map<ResourceKey, Long> amounts(List<ResourceStack> rows) {
        var values = new HashMap<ResourceKey, Long>(); rows.forEach(row -> values.put(row.key(), row.amount())); return values;
    }
    @Test void nineteenLanesWithSixteenProductsMergeAndSurviveReload() {
        var store = new ResourceAccounts(() -> {});
        var account = store.create(UUID.randomUUID(), new ResourceAccount.Binding(ResourceLocation.parse("minecraft:overworld"),
                0L, ResourceLocation.parse("productivebeesgenesis:mek_centrifuge")));
        var all = new ArrayList<>(products(19 * 16).entrySet());
        for (int lane = 0; lane < 19; lane++) {
            var result = new LinkedHashMap<ResourceKey, Long>();
            for (int i = 0; i < 16; i++) { var entry = all.get(lane * 16 + i); result.put(entry.getKey(), entry.getValue()); }
            var ticket = account.reserve(account.reference(), 0, new CompoundTag(), result);
            account.start(ticket); account.freeze(ticket, result); account.commit(ticket);
        }
        assertEquals(products(304), account.available());
        var again = ResourceAccounts.load(store.save(), () -> {}).find(account.reference().sourceId());
        assertEquals(account.available(), again.available());
        var first = all.getFirst();
        var output = Map.of(first.getKey(), first.getValue());
        var ticket = again.reserve(again.reference(), 0, new CompoundTag(), output);
        again.start(ticket); again.freeze(ticket, output); again.commit(ticket);
        assertEquals(first.getValue() * 2, again.available().get(first.getKey()));
        assertEquals(304, again.available().size());
    }
    @Test void deltaOnlySendsChangedAmountsAndReconcilesRemovalReplacementAndLostReply() {
        var server = new ResourceInventoryDelta.Publisher(); var client = new ResourceInventoryDelta.Receiver();
        var current = products(304);
        var full = server.update(current, 0);
        assertTrue(full.reset()); assertEquals(current, amounts(client.apply(roundTrip(full))));
        assertTrue(server.update(current, client.revision()).entries().isEmpty());
        var changed = new HashMap<>(current); var key = changed.keySet().iterator().next(); changed.put(key, Long.MAX_VALUE);
        var delta = server.update(Map.copyOf(changed), client.revision());
        assertEquals(1, delta.entries().size()); assertNull(delta.entries().getFirst().key());
        assertEquals(changed, amounts(client.apply(roundTrip(delta))));
        var removed = changed.keySet().iterator().next(); changed.remove(removed);
        assertEquals(changed, amounts(client.apply(roundTrip(server.update(Map.copyOf(changed), client.revision())))));
        changed.put(ResourceTypes.FE, 99L);
        var missed = server.update(Map.copyOf(changed), client.revision());
        assertFalse(missed.entries().isEmpty());
        var repair = server.update(Map.copyOf(changed), client.revision());
        assertTrue(repair.reset()); assertEquals(changed, amounts(client.apply(roundTrip(repair))));
        assertEquals(Map.of(), amounts(client.apply(roundTrip(server.update(Map.of(), client.revision())))));
    }
    @Test void fullDirectoryChurnStaysBoundedAndDoesNotConfuseReusedIds() {
        var server = new ResourceInventoryDelta.Publisher(); var client = new ResourceInventoryDelta.Receiver();
        var current = products(ResourceInventoryDelta.MAX_ENTRIES);
        assertEquals(current, amounts(client.apply(roundTrip(server.update(current, 0)))));
        for (int step = 0; step < 5; step++) {
            var changed = new LinkedHashMap<>(current);
            changed.remove(changed.keySet().iterator().next());
            changed.put(ResourceKey.chemical(ResourceLocation.parse("example:new_" + step)), Long.MAX_VALUE - step);
            current = Map.copyOf(changed);
            var delta = server.update(current, client.revision());
            assertEquals(1, delta.entries().size(), "A replaced slot needs one final value");
            assertEquals(current, amounts(client.apply(roundTrip(delta))));
        }
    }
    @Test void nineteenFullOutputSnapshotsFitAndOversizedEvidenceIsStillRejected() {
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var outputs = products(16).entrySet().stream()
                .map(entry -> new ResourceProductionRecipe.Output(entry.getKey(), entry.getValue(), 1)).toList();
        var recipe = new ResourceCentrifugeRecipe(ResourceLocation.parse("example:multi"), outputs, 200);
        var work = new ResourceCentrifugeWork(recipe.bee(), recipe, recipe.input(false), 10, 1, 1);
        var snapshot = work.save(registries);
        var lanes = new HashMap<Integer, CompoundTag>();
        for (int i = 0; i < 19; i++) {
            ResourceAccount.validateWork(i, snapshot, lanes); lanes.put(i, snapshot.copy());
        }
        var restored = ResourceCentrifugeWork.load(snapshot, registries);
        assertEquals(recipe, restored.recipe);
        var tooLarge = new CompoundTag(); tooLarge.putByteArray("raw", new byte[ResourceAccount.MAX_LANE_BYTES]);
        assertThrows(IllegalArgumentException.class, () -> ResourceAccount.validateWork(19, tooLarge, lanes));
        assertEquals(19, lanes.size());
    }

    private static ResourceInventoryDelta roundTrip(ResourceInventoryDelta value) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
        try {
            value.encode(buffer);
            assertTrue(buffer.readableBytes() < 1_048_576, "Clientbound payload must fit NeoForge limit");
            var copy = ResourceInventoryDelta.decode(buffer); assertEquals(value, copy); return copy;
        } finally { buffer.release(); }
    }
}
