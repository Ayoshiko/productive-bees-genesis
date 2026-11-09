package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@Tag("minecraft")
class Ae2FilterRecoveryMinecraftTest {
    @BeforeAll
    static void loadConfigs() {
        MachineMenuAccessMinecraftTest.loadConfigs();
    }

    @AfterAll
    static void unloadConfigs() {
        MachineMenuAccessMinecraftTest.unloadConfigs();
    }

    @Test
    void damagedFilterRejectsEditingWithFeedbackUntilExplicitReplacement() {
        try (var fixture = new MachineMenuAccessMinecraftTest.Fixture()) {
            assertTrue(MachineMenuAccess.canAccess(fixture.player, BlockPos.ZERO));
            var holder = fixture.tile.productivebeesgenesis$getAe2StateHolder();
            var configuration = new CompoundTag();
            configuration.putString("productivebeesgenesis_ae_input_filter", "damaged");
            holder.loadPerTileState(configuration);

            assertFalse(Ae2PayloadHandlers.validateInputFilterAvailable(fixture.player, BlockPos.ZERO));
            verify(fixture.player).displayClientMessage(any(Component.class), eq(true));
            var saved = new CompoundTag();
            holder.savePerTileState(saved);
            assertEquals("damaged", saved.getString("productivebeesgenesis_ae_input_filter"));

            holder.loadPerTileState(new CompoundTag());
            assertTrue(Ae2PayloadHandlers.validateInputFilterAvailable(fixture.player, BlockPos.ZERO));
        }
    }
}
