package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** 样板回归使用正式缓冲和冻结 API 创建已付产物，保持生产接口的包访问范围。 */
public final class ApiaryPatternStateFixture {

	private ApiaryPatternStateFixture() {
	}

	public static void populate(TileEntityMekApiary tile, HolderLookup.Provider registries,
			ItemStack buffered, ItemStack frozen, FluidStack fluid, long fluidAmount) {
		tile.getOutputBuffer().offer(buffered);
		var state = tile.productionState();
		state.freeze(new BeeProduceProcessor.Generated(List.of(frozen), fluid, fluidAmount));
		int slot = tile.getBeeSlotCount() - 1;
		state.counts[slot] = 3;
		state.bees[slot].setBeeData(tile.getBeeSlot(slot).getBeeData().copy());
		var buffer = tile.getOutputBuffer().save(registries);
		tile.getOutputBuffer().load(registries, buffer);
		assertEquals(buffer, tile.getOutputBuffer().save(registries));
	}
}
