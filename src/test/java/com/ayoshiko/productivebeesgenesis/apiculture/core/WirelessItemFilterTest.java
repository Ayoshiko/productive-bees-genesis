package com.ayoshiko.productivebeesgenesis.apiculture.core;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WirelessItemFilterTest {
    private static final UUID DEVICE = UUID.randomUUID(), TOKEN = UUID.randomUUID();
    private static final ResourceLocation STONE = ResourceLocation.parse("minecraft:cobblestone");
    private static final ResourceLocation IRON = ResourceLocation.parse("minecraft:iron_ingot");

    @Test void independentModesAndEmptyListsHaveExplicitMeaning() {
        var allow = new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, List.of(STONE.toString()));
        var deny = new WirelessItemFilter(WirelessItemFilter.Mode.DENY, List.of(STONE.toString()));
        assertTrue(WirelessItemFilter.ALL.allows(STONE));
        assertTrue(allow.allows(STONE)); assertFalse(allow.allows(IRON));
        assertFalse(deny.allows(STONE)); assertTrue(deny.allows(IRON));
        var emptyAllow = new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, List.of());
        var emptyDeny = new WirelessItemFilter(WirelessItemFilter.Mode.DENY, List.of());
        assertTrue(emptyAllow.rejectsAll()); assertFalse(emptyAllow.allows(STONE));
        assertFalse(emptyDeny.rejectsAll()); assertTrue(emptyDeny.allows(STONE));
    }

    @Test void snapshotDoesNotAliasConfigurationAndRejectsMalformedIdsWithoutTruncation() {
        var source = new ArrayList<>(List.of(STONE.toString(), IRON.toString(), STONE.toString()));
        var filter = new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, source);
        source.clear();
        assertTrue(filter.allows(STONE)); assertTrue(filter.allows(IRON)); assertEquals(2, filter.items().size());
        assertThrows(UnsupportedOperationException.class, () -> filter.items().clear());
        for (var id : List.of("", "stone", ":stone", "minecraft:", "#minecraft:logs", "minecraft:*", "Minecraft:stone",
                "minecraft:stone\n", "minecraft:石头", "minecraft:" + "a".repeat(129))) {
            assertFalse(WirelessItemFilter.validId(id), id);
            assertThrows(IllegalArgumentException.class, () -> new WirelessItemFilter(WirelessItemFilter.Mode.DENY, List.of(id)));
        }
        assertThrows(IllegalArgumentException.class, () -> new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW,
                java.util.Collections.nCopies(17, STONE.toString())));
    }

    @Test void heartbeatPreservesIntentButChangingFilterDeviceOrEnabledStateInvalidatesIt() {
        var allow = new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, List.of(STONE.toString(), IRON.toString()));
        var reorder = new WirelessItemFilter(WirelessItemFilter.Mode.ALLOW, List.of(IRON.toString(), STONE.toString()));
        var pickup = new WirelessPickupRequest(1, 0, DEVICE, TOKEN, allow);
        assertTrue(pickup.sameIntent(new WirelessPickupRequest(2, 0, DEVICE, TOKEN, reorder)));
        assertFalse(pickup.sameIntent(new WirelessPickupRequest(2, 0, DEVICE, TOKEN, WirelessItemFilter.ALL)));
        assertFalse(pickup.sameIntent(new WirelessPickupRequest(2, 1, DEVICE, TOKEN, allow)));
        assertFalse(pickup.sameIntent(new WirelessPickupRequest(2, 0, DEVICE, UUID.randomUUID(), allow)));
        assertFalse(pickup.sameIntent(new WirelessPickupRequest(2, -1, null, null, WirelessItemFilter.ALL)));
        var magnet = new WirelessMagnetRequest(1, 40, DEVICE, TOKEN, allow);
        assertTrue(magnet.sameIntent(new WirelessMagnetRequest(2, 40, DEVICE, TOKEN, reorder)));
        assertFalse(magnet.sameIntent(new WirelessMagnetRequest(2, 40, DEVICE, TOKEN, WirelessItemFilter.ALL)));
        assertThrows(IllegalArgumentException.class, () -> new WirelessPickupRequest(3, -1, null, null, allow));
        assertThrows(IllegalArgumentException.class, () -> new WirelessMagnetRequest(3, 39, DEVICE, TOKEN, allow));
    }

    @Test void bothWireFormatsRoundTripAtMaximumBudgetAndRejectTruncatedTrailingAndInvalidFrames() {
        var ids = new ArrayList<String>();
        for (int i = 0; i < 16; i++) ids.add("test:" + "a".repeat(121) + String.format("%02x", i));
        var filter = new WirelessItemFilter(WirelessItemFilter.Mode.DENY, ids);
        checkWire(WirelessPickupRequest.CODEC, new WirelessPickupRequest(1, 0, DEVICE, TOKEN, filter),
                new WirelessPickupRequest(2, -1, null, null, WirelessItemFilter.ALL));
        checkWire(WirelessMagnetRequest.CODEC, new WirelessMagnetRequest(1, 40, DEVICE, TOKEN, filter),
                new WirelessMagnetRequest(2, -1, null, null, WirelessItemFilter.ALL));
    }

    private static <T> void checkWire(StreamCodec<FriendlyByteBuf, T> codec, T enabled, T disabled) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            codec.encode(buffer, enabled);
            assertEquals(2127, buffer.readableBytes()); assertEquals(enabled, codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, disabled);
            assertEquals(9, buffer.readableBytes()); assertEquals(disabled, codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, disabled); buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, enabled); buffer.writerIndex(buffer.writerIndex() - 1);
            assertThrows(RuntimeException.class, () -> codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, enabled); buffer.setByte(45, 3);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, enabled); buffer.setByte(46, 17);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, enabled); buffer.writerIndex(45);
            assertThrows(RuntimeException.class, () -> codec.decode(buffer));
            buffer.clear(); codec.encode(buffer, enabled); buffer.writeByte(0);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(buffer));
        } finally { buffer.release(); }
    }
}
