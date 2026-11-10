package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class NeighborItemTargetsMinecraftTest {

	@Test
	void realCapabilityCacheNotifiesWithoutQueryingAndCanReacquireTheSameHandler() {
		var tile = mock(TileEntityMekanism.class);
		var level = mock(ServerLevel.class);
		var pos = new BlockPos(0, 64, 0);
		var targetPos = pos.north();
		var handler = new ItemStackHandler(1);
		List<ICapabilityInvalidationListener> registered = new ArrayList<>();
		List<Direction> invalidated = new ArrayList<>();
		when(tile.getLevel()).thenReturn(level);
		when(tile.getBlockPos()).thenReturn(pos);
		when(level.isLoaded(targetPos)).thenReturn(true);
		when(level.getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.SOUTH)).thenReturn(handler);
		doAnswer(call -> {
			registered.add(call.getArgument(1));
			return null;
		}).when(level).registerCapabilityListener(eq(targetPos), any());
		var targets = new NeighborItemTargets(tile, invalidated::add);

		assertSame(handler, targets.handler(Direction.NORTH));
		assertEquals(1, registered.size());
		assertTrue(registered.getFirst().onInvalidate());
		assertEquals(List.of(Direction.NORTH), invalidated);
		verify(level, times(1)).getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.SOUTH);
		assertSame(handler, targets.handler(Direction.NORTH));
		verify(level, times(2)).getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.SOUTH);

		when(tile.isRemoved()).thenReturn(true);
		assertFalse(registered.getFirst().onInvalidate());
		assertEquals(List.of(Direction.NORTH, Direction.NORTH), invalidated);
		when(tile.isRemoved()).thenReturn(false);
		assertSame(handler, targets.handler(Direction.NORTH));
		assertEquals(2, registered.size());
		verify(level, times(3)).getCapability(Capabilities.ItemHandler.BLOCK, targetPos, Direction.SOUTH);

		when(tile.isRemoved()).thenReturn(true);
		assertNull(targets.handler(Direction.NORTH));
		when(tile.isRemoved()).thenReturn(false);
		assertSame(handler, targets.handler(Direction.NORTH));
		assertEquals(3, registered.size());
		int wakes = invalidated.size();
		assertFalse(registered.get(1).onInvalidate());
		assertEquals(wakes, invalidated.size());
		assertTrue(registered.get(2).onInvalidate());
		assertEquals(wakes + 1, invalidated.size());
	}
}
