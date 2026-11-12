package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.attachments.containers.fluid.AttachedFluids;
import mekanism.common.registries.MekanismDataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@org.junit.jupiter.api.Tag("minecraft")
class MachinePatternFluidMinecraftTest extends MachinePatternTestSupport {

	@ParameterizedTest(name = "{0}")
	@MethodSource("machines")
	void fullTanksAndMixedFluidTypesRemainExactAcrossEncodingAndPlacement(Item item) {
		var original = tile(item);
		populate(original);
		var tanks = tanks(original);
		for (int index = 0; index < tanks.size(); index++) {
			var tank = tanks.get(index);
			tank.setStack(index % 2 == 0 ? honey(tank.getCapacity(), index)
					: new FluidStack(Fluids.WATER, tank.getCapacity()));
		}
		var source = dismantled(original, item);
		var before = source.copy();
		var recipe = recipe(item, item, "A");
		var level = level(recipe);
		var input = CraftingInput.of(1, 1, List.of(source));
		assertTrue(recipe.matches(input, level));
		var result = recipe.assemble(input, registries);
		assertStack(before, result);
		assertEncoded(recipe, source, result, level);
		var restored = tile(item);
		restore(restored, roundTrip(result));
		assertStack(before, dismantled(restored, item));
		assertStack(before, source);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("centrifugeFactories")
	void restoredRoutingUsesFinalFluidComponentsAfterCustomNbtWasApplied(Item item) {
		var original = tile(item);
		populate(original);
		var source = dismantled(original, item);
		var finalFluids = new ArrayList<FluidStack>();
		for (int index = 0; index < tanks(original).size(); index++) {
			finalFluids.add(index == 0 ? new FluidStack(Fluids.WATER, 1000)
					: honey(1000 + index, 30 + index));
		}
		// 放置顺序是自定义 NBT 在先、规范附件在后；最终库存必须成为路由事实。
		source.set(MekanismDataComponents.ATTACHED_FLUIDS, new AttachedFluids(finalFluids));
		var recipe = recipe(item, item, "A");
		var result = recipe.assemble(CraftingInput.of(1, 1, List.of(source)), registries);
		assertFalse(result.isEmpty());
		assertEncoded(recipe, source, result, level(recipe));
		var restored = tile(item);
		restore(restored, roundTrip(result));
		assertEquals(fluidAmount(result), fluidAmount(dismantled(restored, item)));
		var context = (PbRecipeContext) restored;
		for (FluidStack fluid : finalFluids) {
			var selected = context.fluidOutputTankForInsert(fluid.copyWithAmount(1));
			assertNotNull(selected);
			assertTrue(FluidStack.isSameFluidSameComponents(fluid, selected.getFluid()));
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("centrifugeFactories")
	void singleModeUsesEachTiersActualCapacityAndKeepsFullTank(Item item) {
		boolean previous = ModConfig.SERVER.mekCentrifugeMultiFluidTank.get();
		ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(false);
		try {
			var original = tile(item);
			populate(original);
			assertEquals(1, tanks(original).size());
			var tank = tanks(original).getFirst();
			tank.setStack(honey(tank.getCapacity(), 80));
			var source = dismantled(original, item);
			var recipe = recipe(item, item, "A");
			var result = recipe.assemble(CraftingInput.of(1, 1, List.of(source)), registries);
			assertFalse(result.isEmpty());
			assertEquals(tank.getCapacity(), ContainerType.FLUID.createHandler(result).getContainers().getFirst().getCapacity());
			assertEncoded(recipe, source, result, level(recipe));
			var restored = tile(item);
			restore(restored, roundTrip(result));
			assertStack(source, dismantled(restored, item));
		} finally {
			ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(previous);
		}
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("centrifugeFactories")
	void modeChangeAcceptsCompleteCompatibleFluidAndRejectsMixturesOrOverflow(Item item) {
		var original = tile(item);
		var sourceTanks = tanks(original);
		sourceTanks.get(0).setStack(honey(1000, 1));
		sourceTanks.get(1).setStack(honey(2000, 1));
		var compatible = dismantled(original, item);
		sourceTanks.get(1).setStack(new FluidStack(Fluids.WATER, 2000));
		var mixed = dismantled(original, item);
		sourceTanks.get(0).setStack(honey(Integer.MAX_VALUE, 1));
		sourceTanks.get(1).setStack(honey(Integer.MAX_VALUE, 1));
		var overflow = dismantled(original, item);
		boolean previous = ModConfig.SERVER.mekCentrifugeMultiFluidTank.get();
		ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(false);
		try {
			var recipe = recipe(item, item, "A");
			var level = level(recipe);
			var before = compatible.copy();
			var result = recipe.assemble(CraftingInput.of(1, 1, List.of(compatible)), registries);
			assertFalse(result.isEmpty());
			assertEquals(1, ContainerType.FLUID.getOrEmpty(result).containers().size());
			assertEquals(3000, fluidAmount(result));
			assertEncoded(recipe, compatible, result, level);
			var restored = tile(item);
			restore(restored, roundTrip(result));
			assertEquals(3000, fluidAmount(dismantled(restored, item)));
			assertStack(before, compatible);
			for (ItemStack rejected : List.of(mixed, overflow)) {
				var snapshot = rejected.copy();
				var input = CraftingInput.of(1, 1, List.of(rejected));
				assertFalse(recipe.matches(input, level));
				assertTrue(recipe.assemble(input, registries).isEmpty());
				List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
				grid.set(0, rejected);
				new MachinePatternMenuFixture(level).assertRejected(grid);
				assertStack(snapshot, rejected);
			}
		} finally {
			ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(previous);
		}
	}
}
