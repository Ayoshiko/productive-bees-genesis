package com.ayoshiko.productivebeesgenesis.resource;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
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
    @Test void deltaOnlySendsChangedAmountsAndReconcilesRemovalReplacementAndLostReply() {
        var server = new ResourceInventoryDelta.Publisher(); var client = new ResourceInventoryDelta.Receiver();
        var current = products(2);
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
