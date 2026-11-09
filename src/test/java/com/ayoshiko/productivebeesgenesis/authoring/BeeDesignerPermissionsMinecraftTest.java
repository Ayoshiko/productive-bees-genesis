package com.ayoshiko.productivebeesgenesis.authoring;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerPermissionsMinecraftTest {
	@Test
	void dedicatedPublishingNeedsCreativeAndOpEvenWhenOwnerIsReported() throws ReflectiveOperationException {
		MinecraftServer server = mock(MinecraftServer.class);
		ServerPlayer player = player(server);
		when(server.isDedicatedServer()).thenReturn(true);
		when(server.isSingleplayerOwner(player.getGameProfile())).thenReturn(true);
		when(player.isCreative()).thenReturn(true);
		when(player.hasPermissions(2)).thenReturn(false);
		assertFalse(BeeDesignerServer.canPublish(player));
		when(player.hasPermissions(2)).thenReturn(true);
		assertTrue(BeeDesignerServer.canPublish(player));
		when(player.isCreative()).thenReturn(false);
		assertFalse(BeeDesignerServer.canPublish(player));
	}

	@Test
	void integratedOwnerCanPublishWhileGuestStillNeedsOp() throws ReflectiveOperationException {
		MinecraftServer server = mock(MinecraftServer.class);
		ServerPlayer player = player(server);
		when(server.isDedicatedServer()).thenReturn(false);
		when(player.isCreative()).thenReturn(true);
		when(server.isSingleplayerOwner(player.getGameProfile())).thenReturn(true);
		assertTrue(BeeDesignerServer.canPublish(player));
		when(server.isSingleplayerOwner(player.getGameProfile())).thenReturn(false);
		assertFalse(BeeDesignerServer.canPublish(player));
		when(player.hasPermissions(2)).thenReturn(true);
		assertTrue(BeeDesignerServer.canPublish(player));
	}

	private ServerPlayer player(MinecraftServer server) throws ReflectiveOperationException {
		ServerPlayer player = mock(ServerPlayer.class);
		var serverField = ServerPlayer.class.getDeclaredField("server");
		serverField.setAccessible(true);
		serverField.set(player, server);
		when(player.getGameProfile()).thenReturn(new GameProfile(UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"), "PermissionFixture"));
		return player;
	}
}
