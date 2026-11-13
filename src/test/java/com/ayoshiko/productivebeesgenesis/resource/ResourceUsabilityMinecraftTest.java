package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceUsabilityMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

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

}
