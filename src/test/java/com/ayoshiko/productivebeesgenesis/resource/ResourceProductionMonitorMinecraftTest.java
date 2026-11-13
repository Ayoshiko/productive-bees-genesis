package com.ayoshiko.productivebeesgenesis.resource;

import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceProductionMonitorMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test void actualProductionExpiresWhileBlockedIdentityRemainsVisible() {
        var clock = new AtomicLong(10_000);
        var monitor = new ResourceProductionMonitor(clock::get);
        monitor.observe(0, List.of(ResourceTypes.AIR), ResourceProductionSnapshot.State.WAITING_WORK, 1);
        assertEquals(BigInteger.ZERO, monitor.snapshot(1).entries().getFirst().produced());
        monitor.record(Map.of(ResourceTypes.AIR, BigInteger.valueOf(3)));
        var row = monitor.snapshot(1).entries().getFirst();
        assertEquals("0.6", row.rate(5000));
        monitor.route(List.of(ResourceTypes.AIR), true);
        assertEquals(ResourceProductionSnapshot.State.WAITING_OUTPUT, monitor.snapshot(2).entries().getFirst().state());
        clock.addAndGet(5000);
        monitor.route(List.of(ResourceTypes.AIR), true);
        row = monitor.snapshot(101).entries().getFirst();
        assertEquals(BigInteger.ZERO, row.produced());
        assertEquals(ResourceProductionSnapshot.State.WAITING_OUTPUT, row.state());
        monitor.route(List.of(ResourceTypes.AIR), false);
        assertEquals(ResourceProductionSnapshot.State.RECENT, monitor.snapshot(102).entries().getFirst().state());
    }

    @Test void incrementalProductionRetainsZeroAndRebuildsAfterMissingRevision() {
        var publisher = new ResourceProductionDelta.Publisher();
        var receiver = new ResourceProductionDelta.Receiver();
        var first = new ResourceProductionSnapshot(5000, List.of(
                new ResourceProductionSnapshot.Entry(ResourceTypes.AIR, BigInteger.TEN, ResourceProductionSnapshot.State.PRODUCING)));
        var full = publisher.update(first, 0);
        assertEquals(first, receiver.apply(roundTrip(full)));
        var stable = publisher.update(first, receiver.revision());
        assertTrue(stable.entries().isEmpty());
        assertTrue(bytes(stable) < bytes(full));
        var stopped = new ResourceProductionSnapshot(5000, List.of(
                new ResourceProductionSnapshot.Entry(ResourceTypes.AIR, BigInteger.ZERO, ResourceProductionSnapshot.State.WAITING_OUTPUT)));
        var change = publisher.update(stopped, receiver.revision());
        assertNull(change.entries().getFirst().key());
        assertTrue(bytes(change) < bytes(full));
        assertEquals(stopped, receiver.apply(roundTrip(change)));
        var rebuild = publisher.update(stopped, 0);
        assertTrue(rebuild.reset());
        assertEquals(stopped, receiver.apply(roundTrip(rebuild)));
        var removed = publisher.update(ResourceProductionSnapshot.EMPTY, receiver.revision());
        assertTrue(receiver.apply(roundTrip(removed)).entries().isEmpty());
    }

    @Test void malformedProductionDeltaDoesNotChangePublishedClientState() {
        var receiver = new ResourceProductionDelta.Receiver();
        var missing = new ResourceProductionDelta(1, false, 5000, List.of(
                new ResourceProductionDelta.Entry(0, null, BigInteger.ZERO, ResourceProductionSnapshot.State.RECENT)));
        assertThrows(IllegalArgumentException.class, () -> receiver.apply(missing));
        assertEquals(0, receiver.revision());
        assertThrows(IllegalArgumentException.class, () -> new ResourceProductionDelta(1, true, 5000, List.of(
                new ResourceProductionDelta.Entry(0, ResourceTypes.AIR, BigInteger.ZERO, ResourceProductionSnapshot.State.RECENT),
                new ResourceProductionDelta.Entry(0, ResourceTypes.FE, BigInteger.ZERO, ResourceProductionSnapshot.State.RECENT))));
    }

    private static int bytes(ResourceProductionDelta delta) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            delta.encode(buffer);
            return buffer.readableBytes();
        } finally {
            buffer.release();
        }
    }

    private static ResourceProductionDelta roundTrip(ResourceProductionDelta delta) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            delta.encode(buffer);
            return ResourceProductionDelta.decode(buffer);
        } finally {
            buffer.release();
        }
    }
}
