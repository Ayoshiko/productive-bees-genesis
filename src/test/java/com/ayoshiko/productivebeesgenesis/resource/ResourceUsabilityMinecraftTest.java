package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.client.resource.MyriadGuideLink;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceUsabilityMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

    @Test void guideLinkRequiresTheMyriadComponentsOnAllThreeSharedItems() {
        for (var id : List.of("configurable_honeycomb", "configurable_comb", "spawn_egg_configurable_bee")) {
            var stack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("productivebees:" + id)));
            assertFalse(MyriadGuideLink.matches(stack));
            for (var bee : List.of("productivebees:myriadcreations", "example:hydrogen", "productivebees:iron")) {
                if (id.startsWith("spawn_egg")) {
                    var data = new CompoundTag(); data.putString("id", "productivebees:configurable_bee"); data.putString("type", bee);
                    stack.set(DataComponents.ENTITY_DATA, CustomData.of(data));
                } else stack.set(ModDataComponents.BEE_TYPE.get(), ResourceLocation.parse(bee));
                assertEquals(bee.equals("productivebees:myriadcreations"), MyriadGuideLink.matches(stack), id + "/" + bee);
            }
        }
    }

    @Test void menuSnapshotRoundTripRetainsSessionAndFullResourceQuantities() {
        var request = new ResourceMenuSync.Request(17, new BlockPos(1, 2, 3), UUID.randomUUID(), 9);
        var status = new ResourceOutputStatus(ResourceOutputStatus.State.ME_FULL, true, 1,
                List.of(new ResourceStack(ResourceTypes.FE, Long.MAX_VALUE)));
        var reply = new ResourceMenuSync.Reply(request, status);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            ResourceMenuSync.Reply.CODEC.encode(buffer, reply);
            assertEquals(reply, ResourceMenuSync.Reply.CODEC.decode(buffer));
        } finally { buffer.release(); }
    }

    @Test void oldInterfaceSwitchWaitsForMigrationAndNewSwitchPersists() {
        var old = new CompoundTag(); old.putInt("version", 1); old.putUUID("source", UUID.randomUUID());
        old.putLong("generation", 1); old.putLong("capacity", 1000);
        old.putUUID("output_port", UUID.randomUUID()); old.putLong("output_position", BlockPos.ZERO.asLong());
        var state = new ResourceHiveHost(); state.deserializeNBT(REGISTRIES, old);
        assertFalse(state.meOutputEnabled(), "Do not export before reading the old interface switch");
        assertEquals(old, state.serializeNBT(REGISTRIES));
        old.putBoolean("me_output", false); state.deserializeNBT(REGISTRIES, old);
        assertFalse(state.meOutputEnabled()); assertEquals(old, state.serializeNBT(REGISTRIES));
        old.putBoolean("me_output", true); state.deserializeNBT(REGISTRIES, old);
        assertTrue(state.meOutputEnabled()); assertEquals(old, state.serializeNBT(REGISTRIES));
        old.putString("me_output", "true"); state.deserializeNBT(REGISTRIES, old);
        assertEquals("invalid_host_data", state.status());
        assertEquals(old, state.serializeNBT(REGISTRIES), "Invalid switch data must survive unchanged");
    }

    @Test void recipientIdentityPersistsAndMalformedIdentitySurvivesUnchanged() {
        var tag = new CompoundTag();
        tag.putInt("version", 1);
        tag.putUUID("source", UUID.randomUUID());
        tag.putLong("generation", 1);
        tag.putLong("capacity", 1000);
        tag.putBoolean("me_output", true);
        tag.putUUID("emc_recipient", UUID.randomUUID());
        var state = new ResourceHiveHost();
        state.deserializeNBT(REGISTRIES, tag);
        assertEquals(tag, state.serializeNBT(REGISTRIES));
        tag.putString("emc_recipient", "invalid");
        state.deserializeNBT(REGISTRIES, tag);
        assertEquals("invalid_host_data", state.status());
        assertEquals(tag, state.serializeNBT(REGISTRIES));
    }

    @Test void recipientReplyAndSelectionPreserveSessionGenerationAndExpectedPlayer() {
        var request = new ResourceMenuSync.Request(17, BlockPos.ZERO, UUID.randomUUID(), 9);
        var source = new ResourceAccount.Reference(UUID.randomUUID(), 7);
        var recipient = new ResourceMenuSync.EmcRecipient(UUID.randomUUID(), "TeamPlayer", "offline", true);
        var reply = new ResourceMenuSync.Reply(request, new ResourceOutputStatus(ResourceOutputStatus.State.READY,
                true, 0, List.of()), source, 1000, ResourceInventoryDelta.empty(), 1, recipient);
        var action = new ResourceMenuSync.SelectEmcRecipient(request, source, recipient.player());
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), REGISTRIES);
        try {
            ResourceMenuSync.Reply.CODEC.encode(buffer, reply);
            assertEquals(reply, ResourceMenuSync.Reply.CODEC.decode(buffer));
            ResourceMenuSync.SelectEmcRecipient.CODEC.encode(buffer, action);
            assertEquals(action, ResourceMenuSync.SelectEmcRecipient.CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
