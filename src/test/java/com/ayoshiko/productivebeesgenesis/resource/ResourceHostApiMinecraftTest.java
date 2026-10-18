package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.api.resource.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceHostApiMinecraftTest {
	private static ResourceHostAdapter adapter() {
		return new ResourceHostAdapter() {
			public boolean ready(BlockEntity host) { return true; }
			public boolean canAccess(ServerPlayer player, BlockEntity host) { return false; }
			public boolean hasLiveWork(BlockEntity host) { return true; }
		};
	}

	@Test void registrationRejectsDuplicatesAndReturnsImmutableSnapshot() {
		var event = new RegisterResourceHostsEvent();
		var adapter = adapter();
		event.register(BlockEntityType.FURNACE, adapter);
		assertThrows(IllegalArgumentException.class, () -> event.register(BlockEntityType.FURNACE, adapter()));
		var snapshot = event.finish();
		assertSame(adapter, snapshot.get(BlockEntityType.FURNACE));
		assertThrows(UnsupportedOperationException.class, snapshot::clear);
		assertThrows(IllegalStateException.class, () -> event.register(BlockEntityType.CHEST, adapter()));
	}

	@Test void registrationRejectsCrossThreadMutation() throws Exception {
		var event = new RegisterResourceHostsEvent();
		var failure = new AtomicReference<Throwable>();
		var thread = new Thread(() -> {
			try { event.register(BlockEntityType.FURNACE, adapter()); } catch (Throwable error) { failure.set(error); }
		});
		thread.start();
		thread.join();
		assertInstanceOf(IllegalStateException.class, failure.get());
		assertTrue(event.finish().isEmpty());
	}

	@Test void hostRegistryIsIsolatedPerServerAndDoesNotRepostOnRepeatedLookup() {
		var calls = new AtomicInteger();
		Consumer<RegisterResourceHostsEvent> listener = event -> {
			calls.incrementAndGet();
			event.register(BlockEntityType.FURNACE, adapter());
		};
		NeoForge.EVENT_BUS.addListener(listener);
		var first = mock(MinecraftServer.class);
		var second = mock(MinecraftServer.class);
		when(first.isSameThread()).thenReturn(true);
		when(second.isSameThread()).thenReturn(true);
		try {
			var a = ResourceRuntime.hosts(first);
			assertSame(a, ResourceRuntime.hosts(first));
			assertNotSame(a, ResourceRuntime.hosts(second));
			assertEquals(2, calls.get());
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
			ResourceRuntime.stopped(new ServerStoppedEvent(first));
			ResourceRuntime.stopped(new ServerStoppedEvent(second));
		}
	}

	@Test void failedRegistryPublishesNoPartialAdaptersAndDoesNotRetryEveryTick() {
		var calls = new AtomicInteger();
		var retained = new AtomicReference<RegisterResourceHostsEvent>();
		Consumer<RegisterResourceHostsEvent> listener = event -> {
			retained.set(event);
			calls.incrementAndGet();
			event.register(BlockEntityType.FURNACE, adapter());
			throw new IllegalStateException("expected host registration failure");
		};
		NeoForge.EVENT_BUS.addListener(listener);
		var server = mock(MinecraftServer.class);
		when(server.isSameThread()).thenReturn(true);
		try {
			for (int i = 0; i < 30; i++) assertTrue(ResourceRuntime.hosts(server).isEmpty());
			assertEquals(1, calls.get());
			assertThrows(IllegalStateException.class, () -> retained.get().register(BlockEntityType.CHEST, adapter()));
		} finally {
			NeoForge.EVENT_BUS.unregister(listener);
			ResourceRuntime.stopped(new ServerStoppedEvent(server));
		}
	}

	@Test void hostRegistryRejectsOffThreadCallsBeforePublishing() {
		var server = mock(MinecraftServer.class);
		when(server.isSameThread()).thenReturn(false);
		assertThrows(IllegalStateException.class, () -> ResourceRuntime.hosts(server));
	}
}
