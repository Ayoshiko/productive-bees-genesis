package com.ayoshiko.productivebeesgenesis.logistics;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import mekanism.common.tile.component.config.ConfigInfo;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class TemplateDirectEjectMinecraftTest {
	@Test
	void actualNeighborAcceptsExplicitAmountAndPreservesTheTemplate() {
		var target = new ItemStackHandler(1) {
			@Override public int getSlotLimit(int slot) { return 13; }
		};
		try (var targets = mockConstruction(NeighborItemTargets.class, (mock, context) -> {
			when(mock.outputSides(any())).thenReturn(List.of(Direction.NORTH));
			when(mock.handler(Direction.NORTH)).thenReturn(target);
		})) {
			var ejector = mock(TileComponentEjector.class);
			var config = mock(ConfigInfo.class);
			when(ejector.isEjecting(config, TransmissionType.ITEM)).thenReturn(true);
			var fast = new FastItemEjector(mock(TileEntityMekanism.class));
			var template = new ItemStack(Items.DIAMOND);
			assertEquals(13, fast.pushDirect(ejector, config, template, 50, 11L));
			assertEquals(13, target.getStackInSlot(0).getCount());
			assertEquals(1, template.getCount());
		}
	}

	@Test
	void disabledOrMissingTargetDoesNotCopyTheTemplate() {
		var template = spy(new ItemStack(Items.DIAMOND));
		var fast = new FastItemEjector(mock(TileEntityMekanism.class));
		assertEquals(0, fast.pushDirect(mock(TileComponentEjector.class), null, template, 4096, 12L));
		assertEquals(0, IFastEjectHost.push(null, template, 4096));
		verify(template, never()).copyWithCount(anyInt());
	}

	@Test
	void legacyHostReceivesAnIndependentFullRequest() {
		var template = new ItemStack(Items.DIAMOND);
		IFastEjectHost legacy = request -> {
			assertNotSame(template, request);
			assertEquals(500, request.getCount());
			request.shrink(7);
			return 7;
		};
		assertEquals(7, IFastEjectHost.push(legacy, template, 500));
		assertEquals(1, template.getCount());
	}
}
