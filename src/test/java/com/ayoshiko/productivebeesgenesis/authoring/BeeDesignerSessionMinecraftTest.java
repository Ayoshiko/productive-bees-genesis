package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.init.ModItems;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerSessionMinecraftTest {
	@Test
	void losingHeldToolRevokesOldSessionEvenAfterToolReturns() throws ReflectiveOperationException {
		Fixture fixture = fixture();
		try {
			when(fixture.player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
			tick(fixture);
			assertTrue(fixture.sessions.isEmpty());
			when(fixture.player.getMainHandItem()).thenReturn(new ItemStack(ModItems.BEE_DESIGNER.get()));
			assertFalse(active(fixture));
		} finally {
			states().remove(fixture.server);
		}
	}

	@Test
	void movingToolToOffhandKeepsSessionButLosingCreativeRevokesIt() throws ReflectiveOperationException {
		Fixture fixture = fixture();
		try {
			when(fixture.player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
			when(fixture.player.getOffhandItem()).thenReturn(new ItemStack(ModItems.BEE_DESIGNER.get()));
			tick(fixture);
			assertSame(fixture.session, fixture.sessions.get(fixture.player.getUUID()));
			when(fixture.player.isCreative()).thenReturn(false);
			tick(fixture);
			assertTrue(fixture.sessions.isEmpty());
			when(fixture.player.isCreative()).thenReturn(true);
			assertFalse(active(fixture));
		} finally {
			states().remove(fixture.server);
		}
	}

	@Test
	void asyncAuthorizationRemovesExpiredSessionInsteadOfRestoringIt() throws ReflectiveOperationException {
		Fixture fixture = fixture();
		try {
			Field access = fixture.session.getClass().getDeclaredField("lastAccess");
			access.setAccessible(true);
			access.setLong(fixture.session, System.nanoTime() - 31L * 60 * 1_000_000_000);
			assertFalse(active(fixture));
			assertTrue(fixture.sessions.isEmpty());
			access.setLong(fixture.session, System.nanoTime());
			assertFalse(active(fixture));
		} finally {
			states().remove(fixture.server);
		}
	}

	private static void tick(Fixture fixture) {
		ServerTickEvent.Post event = mock(ServerTickEvent.Post.class);
		when(event.getServer()).thenReturn(fixture.server);
		BeeDesignerServer.tick(event);
	}

	private static boolean active(Fixture fixture) throws ReflectiveOperationException {
		var method = BeeDesignerServer.class.getDeclaredMethod("active", ServerPlayer.class,
				fixture.state.getClass(), fixture.session.getClass());
		method.setAccessible(true);
		return (boolean) method.invoke(null, fixture.player, fixture.state, fixture.session);
	}

	@SuppressWarnings("unchecked")
	private static Map<MinecraftServer, Object> states() throws ReflectiveOperationException {
		Field field = BeeDesignerServer.class.getDeclaredField("STATES");
		field.setAccessible(true);
		return (Map<MinecraftServer, Object>) field.get(null);
	}

	private static Fixture fixture() throws ReflectiveOperationException {
		MinecraftServer server = mock(MinecraftServer.class);
		ServerPlayer player = mock(ServerPlayer.class);
		PlayerList players = mock(PlayerList.class);
		UUID owner = UUID.fromString("51234567-89ab-cdef-0123-456789abcdef");
		when(server.getPlayerList()).thenReturn(players);
		when(players.getPlayer(owner)).thenReturn(player);
		when(player.getUUID()).thenReturn(owner);
		when(player.isCreative()).thenReturn(true);
		when(player.getMainHandItem()).thenReturn(new ItemStack(ModItems.BEE_DESIGNER.get()));
		when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
		Class<?> stateClass = Class.forName(BeeDesignerServer.class.getName() + "$State");
		Object state = mock(stateClass);
		Map<UUID, Object> sessions = new HashMap<>();
		Field sessionsField = stateClass.getDeclaredField("sessions");
		sessionsField.setAccessible(true);
		sessionsField.set(state, sessions);
		Class<?> sessionClass = Class.forName(BeeDesignerServer.class.getName() + "$Session");
		Constructor<?> constructor = sessionClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object session = constructor.newInstance();
		sessions.put(owner, session);
		states().put(server, state);
		return new Fixture(server, player, state, session, sessions);
	}

	private record Fixture(MinecraftServer server, ServerPlayer player, Object state, Object session,
			Map<UUID, Object> sessions) {
	}
}
