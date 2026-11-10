package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.logistics.IndexedOutputSlots;
import com.ayoshiko.productivebeesgenesis.logistics.LocalOutputTransfer;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import mekanism.api.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("minecraft")
class ApiaryIndexedOutputMinecraftTest {

	@BeforeAll
	static void configs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unload() {
		ConfigTracker.INSTANCE.unloadConfigs(ModConfig.Type.SERVER);
	}

	@Test
	void physicalApiarySlotsShareTheIndexForFrozenAndBufferedOutput() {
		var state = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("productivebeesgenesis:mek_apiary")).defaultBlockState();
		var tile = new TileEntityMekApiary(Holder.direct(state.getBlock()), BlockPos.ZERO, state);
		var slots = tile.getOutputSlots();
		assertInstanceOf(IndexedOutputSlots.class, slots);
		assertFalse(tile.productivebeesgenesis$hasOutputItems());
		assertEquals(0, tile.productivebeesgenesis$outputItemCount());
		var last = slots.getLast();
		last.setStack(new ItemStack(Items.DIAMOND, 8));
		assertTrue(tile.productivebeesgenesis$hasOutputItems());
		assertEquals(8, tile.productivebeesgenesis$outputItemCount());
		assertEquals(4, LocalOutputTransfer.insert(slots, new ItemStack(Items.DIAMOND, 4), OutputCustody.of(tile)));
		assertTrue(slots.getFirst().isEmpty());
		assertEquals(12, last.getCount());
		last.shrinkStack(5, Action.EXECUTE);
		assertEquals(7, tile.productivebeesgenesis$outputItemCount());
		tile.getOutputBuffer().accept(new ItemStack(Items.EMERALD, 3));
		tile.getOutputBuffer().tickRedistribute(slots);
		assertEquals(10, tile.productivebeesgenesis$outputItemCount());
		assertEquals(3, slots.getFirst().getCount());
		assertTrue(slots.getFirst().getStack().is(Items.EMERALD));
		assertEquals(0, tile.getOutputBuffer().getBufferedGroupCount());
	}
}
