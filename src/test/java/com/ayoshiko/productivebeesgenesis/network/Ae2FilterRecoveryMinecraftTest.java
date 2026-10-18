package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class Ae2FilterRecoveryMinecraftTest {
    @Test
    @SuppressWarnings("unchecked")
    void damagedFilterRejectsEditingWithFeedbackUntilExplicitReplacement() {
        var holder = new Ae2OutputStateHolder();
        var configuration = new CompoundTag();
        configuration.putString("productivebeesgenesis_ae_input_filter", "damaged");
        holder.loadPerTileState(configuration);
        var tile = mock(TileEntityMekCentrifuge.class);
        when(tile.getBlockPos()).thenReturn(BlockPos.ZERO);
        when(tile.productivebeesgenesis$getAe2StateHolder()).thenReturn(holder);
        var world = mock(Level.class);
        when(world.getBlockEntity(BlockPos.ZERO)).thenReturn(tile);
        var player = mock(ServerPlayer.class);
        when(player.level()).thenReturn(world);
        MekanismTileContainer<TileEntityMekCentrifuge> menu = mock(MekanismTileContainer.class);
        when(menu.getTileEntity()).thenReturn(tile);
        player.containerMenu = menu;
        assertFalse(Ae2PayloadHandlers.validateInputFilterAvailable(player, BlockPos.ZERO, "test_filter_recovery"));
        verify(player).displayClientMessage(any(Component.class), eq(true));
        var saved = new CompoundTag();
        holder.savePerTileState(saved);
        assertEquals("damaged", saved.getString("productivebeesgenesis_ae_input_filter"));
        holder.loadPerTileState(new CompoundTag());
        assertTrue(Ae2PayloadHandlers.validateInputFilterAvailable(player, BlockPos.ZERO, "test_filter_recovery"));
    }
}
