package com.ayoshiko.productivebeesgenesis.apiary.client;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeEntityCacheMinecraftTest {

	@AfterEach void clear() { BeeEntityCache.clearCache(); }

	@Test void visibleSetSurvivesCapacityBoundaryAndHotScreenSurvivesColdVisits() {
		var level = mock(Level.class);
		var cache = BeeEntityCache.getInstance();
		try (var factory = mockStatic(BeeEntityFactory.class)) {
			factory.when(() -> BeeEntityFactory.createBeeEntity(any(), any())).thenAnswer(call -> mock(Entity.class));
			Entity[] hot = new Entity[60];
			for (int i = 0; i < hot.length; i++) hot[i] = cache.getOrCreate(bee(i), level);
			for (int machine = 1; machine <= 8; machine++) {
				Entity[] visible = new Entity[60];
				for (int i = 0; i < 60; i++) visible[i] = cache.getOrCreate(bee(machine * 60 + i), level);
				for (int i = 0; i < 60; i++) assertSame(visible[i], cache.getOrCreate(bee(machine * 60 + i), level));
				for (int i = 0; i < 60; i++) assertSame(hot[i], cache.getOrCreate(bee(i), level));
			}
		}
	}

	@Test void evictionIsBoundedAndIdentityIncludesTheEntityType() {
		var level = mock(Level.class);
		var cache = BeeEntityCache.getInstance();
		try (var factory = mockStatic(BeeEntityFactory.class)) {
			factory.when(() -> BeeEntityFactory.createBeeEntity(any(), any())).thenAnswer(call -> mock(Entity.class));
			Entity first = cache.getOrCreate(bee(0), level);
			for (int i = 1; i <= 256; i++) cache.getOrCreate(bee(i), level);
			assertNotSame(first, cache.getOrCreate(bee(0), level));
			var other = bee(0); other.putString("id", "minecraft:pig");
			assertNotSame(cache.getOrCreate(bee(0), level), cache.getOrCreate(other, level));
		}
	}

	@Test void reloadAndLevelChangesCannotReuseOldEntities() {
		var level = mock(Level.class);
		var otherLevel = mock(Level.class);
		var cache = BeeEntityCache.getInstance();
		try (var factory = mockStatic(BeeEntityFactory.class)) {
			factory.when(() -> BeeEntityFactory.createBeeEntity(any(), any())).thenAnswer(call -> mock(Entity.class));
			var first = cache.getOrCreate(bee(0), level);
			assertSame(first, cache.getOrCreate(bee(0), level));
			assertNotSame(first, cache.getOrCreate(bee(0), otherLevel));
			long generation = BeeEntityCache.generation();
			BeeEntityCache.clearCache();
			assertTrue(BeeEntityCache.generation() > generation);
			assertNotSame(first, cache.getOrCreate(bee(0), level));
			assertNull(cache.getOrCreate(null, level));
			assertNull(cache.getOrCreate(bee(0), null));
		}
	}

	@Test void concurrentInvalidationDoesNotPublishAnEntityAfterClearReturns() throws Exception {
		var level = mock(Level.class);
		var cache = BeeEntityCache.getInstance();
		var creating = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var render = executor.submit(() -> {
				try (var factory = mockStatic(BeeEntityFactory.class)) {
					factory.when(() -> BeeEntityFactory.createBeeEntity(any(), any())).thenAnswer(call -> {
						creating.countDown();
						assertTrue(release.await(5, TimeUnit.SECONDS));
						return mock(Entity.class);
					});
					return cache.getOrCreate(bee(0), level);
				}
			});
			assertTrue(creating.await(5, TimeUnit.SECONDS));
			var clear = executor.submit(BeeEntityCache::clearCache);
			release.countDown();
			var old = render.get(5, TimeUnit.SECONDS);
			clear.get(5, TimeUnit.SECONDS);
			try (var factory = mockStatic(BeeEntityFactory.class)) {
				factory.when(() -> BeeEntityFactory.createBeeEntity(any(), any())).thenReturn(mock(Entity.class));
				assertNotSame(old, cache.getOrCreate(bee(0), level));
			}
		}
	}

	private static CompoundTag bee(int index) {
		var data = new CompoundTag();
		data.putString("id", "minecraft:bee"); data.putString("type", "probe:bee" + index);
		return data;
	}
}
