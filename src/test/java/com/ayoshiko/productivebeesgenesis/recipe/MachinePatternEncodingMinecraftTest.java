package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import appeng.api.stacks.AEItemKey;
import appeng.crafting.pattern.AECraftingPattern;
import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import mekanism.common.attachments.containers.ContainerType;
import mekanism.common.attachments.containers.fluid.AttachedFluids;
import mekanism.common.registries.MekanismDataComponents;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@org.junit.jupiter.api.Tag("minecraft")
class MachinePatternEncodingMinecraftTest extends MachinePatternTestSupport {

	@ParameterizedTest(name = "{0}")
	@MethodSource("machines")
	void dismantledContentsSurvivePreviewEncodingAndPlacementAtEveryRegisteredTier(Item item) {
		TileEntityMekanism original = tile(item);
		populate(original);
		ItemStack source = dismantled(original, item);
		ItemStack before = source.copy();
		List<FluidStack> savedFluids = ContainerType.FLUID.getOrEmpty(source).containers();
		assertEquals(tanks(original).size(), savedFluids.size());
		assertEquals(savedFluids.size(), ContainerType.FLUID.createHandler(source).getContainers().size());
		ApiaryShapedRecipe recipe = recipe(item, item, "A");
		CraftingInput input = CraftingInput.of(1, 1, List.of(source));
		ServerLevel level = level(recipe);
		assertTrue(recipe.matches(input, level));
		ItemStack result = recipe.assemble(input, registries);
		assertFalse(result.isEmpty());
		assertStack(before, result, "recipe result");
		assertStack(before, source, "preview input");
		assertEncoded(recipe, source, result, level);

		TileEntityMekanism restored = tile(item);
		restore(restored, roundTrip(result));
		assertStack(before, dismantled(restored, item), "placement result");
		assertStack(before, dismantled(original, item), "original machine");
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("machines")
	void singleTankLegacyAndEmptyAttachmentsRemainReadableAtEveryRegisteredTier(Item item) {
		ItemStack source = dismantled(tile(item), item);
		var honey = honey(25_000, 1);
		source.set(MekanismDataComponents.ATTACHED_FLUIDS, new AttachedFluids(List.of(honey)));
		ItemStack before = source.copy();
		assertEquals(1, ContainerType.FLUID.createHandler(source).getContainers().size());
		var recipe = recipe(item, item, "A");
		var result = recipe.assemble(CraftingInput.of(1, 1, List.of(source)), registries);
		assertFalse(result.isEmpty());
		assertEquals(25_000L, fluidAmount(result));
		assertTrue(FluidStack.isSameFluidSameComponents(honey,
				ContainerType.FLUID.getOrEmpty(result).containers().getFirst()));
		assertStack(before, source);
		assertDoesNotThrow(() -> roundTrip(result));
	}

	@Test
	void reportedAdvancedToEliteRecipePreservesHoneyInventoryAndSettings() {
		Item sourceItem = ModItems.ADVANCED_MEK_CENTRIFUGE_FACTORY.get();
		Item targetItem = ModItems.ELITE_MEK_CENTRIFUGE_FACTORY.get();
		var original = tile(sourceItem);
		populate(original);
		var source = dismantled(original, sourceItem);
		var before = source.copy();
		var recipe = recipe(sourceItem, targetItem, " B ", "BAB", " B ");
		var grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
		grid.set(1, new ItemStack(Items.GOLD_INGOT));
		grid.set(3, new ItemStack(Items.GOLD_INGOT));
		grid.set(4, source);
		grid.set(5, new ItemStack(Items.GOLD_INGOT));
		grid.set(7, new ItemStack(Items.GOLD_INGOT));
		var level = level(recipe);
		assertTrue(recipe.matches(CraftingInput.of(3, 3, grid), level));
		var result = recipe.assemble(CraftingInput.of(3, 3, grid), registries);
		assertFalse(result.isEmpty());
		assertEquals(fluidAmount(source), fluidAmount(result));
		assertEquals(source.get(MekanismDataComponents.SIDE_CONFIG), result.get(MekanismDataComponents.SIDE_CONFIG));
		assertEquals(source.get(MekanismDataComponents.EJECTOR), result.get(MekanismDataComponents.EJECTOR));
		var sourceInventory = ContainerType.ITEM.getOrEmpty(source).containers();
		var resultInventory = ContainerType.ITEM.getOrEmpty(result).containers();
		for (int slot = 0; slot < sourceInventory.size() - 1; slot++) {
			assertStack(sourceInventory.get(slot), resultInventory.get(slot));
		}
		assertStack(sourceInventory.getLast(), resultInventory.getLast());
		var restored = tile(targetItem);
		restore(restored, roundTrip(result));
		assertEquals(fluidAmount(source), fluidAmount(dismantled(restored, targetItem)));
		assertStack(before, source);
	}

	@Test
	void incompatibleFluidOrFullInventoryRejectsCombinationWithoutChangingInputs() {
		Item item = ModItems.MEK_CENTRIFUGE.get();
		var first = dismantled(tile(item), item);
		var second = first.copy();
		first.set(MekanismDataComponents.ATTACHED_FLUIDS, new AttachedFluids(List.of(honey(1000, 1))));
		second.set(MekanismDataComponents.ATTACHED_FLUIDS, new AttachedFluids(List.of(honey(1000, 2))));
		var beforeFirst = first.copy();
		var beforeSecond = second.copy();
		var recipe = recipe(item, item, "AA");
		assertTrue(recipe.assemble(CraftingInput.of(2, 1, List.of(first, second)), registries).isEmpty());
		assertStack(beforeFirst, first);
		assertStack(beforeSecond, second);
	}

	@Test
	void singleFluidModeKeepsDismantledHoneyAcrossFactoryUpgrade() {
		ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(false);
		try {
			Item sourceItem = ModItems.ADVANCED_MEK_CENTRIFUGE_FACTORY.get();
			Item targetItem = ModItems.ELITE_MEK_CENTRIFUGE_FACTORY.get();
			var original = tile(sourceItem);
			assertEquals(1, tanks(original).size());
			tanks(original).getFirst().setStack(honey(tanks(original).getFirst().getCapacity() / 2, 1));
			var source = dismantled(original, sourceItem);
			var recipe = recipe(sourceItem, targetItem, "A");
			var result = recipe.assemble(CraftingInput.of(1, 1, List.of(source)), registries);
			assertFalse(result.isEmpty());
			var restored = tile(targetItem);
			restore(restored, roundTrip(result));
			assertEquals(fluidAmount(source), fluidAmount(dismantled(restored, targetItem)));
		} finally {
			ModConfig.SERVER.mekCentrifugeMultiFluidTank.set(true);
		}
	}

	@Test
	void releasedUpgradeRecipesRetainEachMachineOrRejectUnmergeableAuxiliaryAssets() throws Exception {
		Path root = Path.of("src/generated/resources/data/productivebeesgenesis/recipe/factory");
		if (!Files.isDirectory(root)) {
			root = Path.of("../../src/generated/resources/data/productivebeesgenesis/recipe/factory");
		}
		assertTrue(Files.isDirectory(root), "Actual generated recipe directory");
		int checked = 0;
		try (var paths = Files.walk(root)) {
			for (Path path : paths.filter(file -> file.toString().endsWith(".json")).sorted().toList()) {
				var json = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
				if (!json.get("type").getAsString().equals("productivebeesgenesis:apiary_shaped")) {
					continue;
				}
				var recipe = assertInstanceOf(ApiaryShapedRecipe.class, Recipe.CODEC.parse(
						registries.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow());
				Item targetItem = recipe.getResultItem(registries).getItem();
				if (!(targetItem instanceof ItemBlockMekApiary) && !(targetItem instanceof ItemBlockMekCentrifuge)) {
					continue;
				}
				List<ItemStack> fresh = new ArrayList<>();
				for (var ingredient : recipe.getIngredients()) {
					fresh.add(ingredient.isEmpty() ? ItemStack.EMPTY : ingredient.getItems()[0].copy());
				}
				var freshInput = CraftingInput.of(recipe.getWidth(), recipe.getHeight(), fresh);
				var freshLevel = level(recipe);
				assertTrue(recipe.matches(freshInput, freshLevel), path.toString());
				var freshResult = recipe.assemble(freshInput, registries);
				List<ItemStack> freshGrid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
				for (int y = 0; y < recipe.getHeight(); y++) {
					for (int x = 0; x < recipe.getWidth(); x++) {
						freshGrid.set(y * 3 + x, fresh.get(y * recipe.getWidth() + x));
					}
				}
				var freshEncoded = new MachinePatternMenuFixture(freshLevel).encode(freshGrid);
				assertEquals(AEItemKey.of(freshResult),
						new AECraftingPattern(AEItemKey.of(roundTrip(freshEncoded)), freshLevel)
								.getOutputs().getFirst().what(), path.toString());

				List<ItemStack> grid = new ArrayList<>();
				List<ItemStack> machines = new ArrayList<>();
				for (var ingredient : recipe.getIngredients()) {
					if (ingredient.isEmpty()) {
						grid.add(ItemStack.EMPTY);
						continue;
					}
					assertTrue(ingredient.getItems().length > 0, path + " has no registered ingredient");
					ItemStack stack = ingredient.getItems()[0].copy();
					if (stack.getItem() instanceof ItemBlockMekApiary || stack.getItem() instanceof ItemBlockMekCentrifuge) {
						var machine = tile(stack.getItem());
						populate(machine);
						if (machine instanceof TileEntityMekApiary && targetItem instanceof ItemBlockMekApiary) {
							int targetSlots = tile(targetItem).getInventorySlots(null).size();
							var sourceSlots = machine.getInventorySlots(null);
							if (sourceSlots.size() > targetSlots) {
								// 等级路线确实缩小物理输出区；可升级场景只使用目标能够承接的槽。
								for (int slot = targetSlots - 2; slot < sourceSlots.size() - 2; slot++) {
									sourceSlots.get(slot).setStack(ItemStack.EMPTY);
								}
							}
						}
						stack = dismantled(machine, stack.getItem());
						machines.add(stack);
					}
					grid.add(stack);
				}
				var before = machines.stream().map(ItemStack::copy).toList();
				var input = CraftingInput.of(recipe.getWidth(), recipe.getHeight(), grid);
				var result = recipe.assemble(input, registries);
				if (machines.size() > 1) {
					assertTrue(result.isEmpty(), path + " cannot discard auxiliary machine state");
				} else {
					assertFalse(result.isEmpty(), path.toString());
					var source = machines.getFirst();
					assertEquals(fluidAmount(source), fluidAmount(result), path.toString());
					for (var entry : source.getComponentsPatch().entrySet()) {
						if (entry.getKey() != DataComponents.BLOCK_ENTITY_DATA
								&& entry.getKey() != ContainerType.ITEM.getComponentType().get()
								&& entry.getKey() != ContainerType.FLUID.getComponentType().get()) {
							assertEquals(source.get(entry.getKey()), result.get(entry.getKey()), path.toString());
						}
					}
					var sourceData = source.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
					var resultData = result.get(DataComponents.BLOCK_ENTITY_DATA).copyTag();
					for (String key : sourceData.getAllKeys()) {
						if (!key.equals("id")) {
							assertEquals(sourceData.get(key), resultData.get(key), path + ": " + key);
						}
					}
					var restored = tile(targetItem);
					restore(restored, roundTrip(result));
					assertEquals(fluidAmount(source), fluidAmount(dismantled(restored, targetItem)), path.toString());
					var sourceInventory = ContainerType.ITEM.getOrEmpty(source).containers();
					var resultInventory = ContainerType.ITEM.getOrEmpty(result).containers();
					int tail = targetItem instanceof ItemBlockMekApiary ? 2 : 1;
					for (int slot = 0; slot < Math.min(sourceInventory.size(), resultInventory.size()) - tail; slot++) {
						assertStack(sourceInventory.get(slot), resultInventory.get(slot), path.toString());
					}
					for (int slot = 1; slot <= tail; slot++) {
						assertStack(sourceInventory.get(sourceInventory.size() - slot),
								resultInventory.get(resultInventory.size() - slot), path.toString());
					}
					var level = level(recipe);
					assertTrue(recipe.matches(input, level), path.toString());
					List<ItemStack> menuGrid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
					for (int y = 0; y < recipe.getHeight(); y++) {
						for (int x = 0; x < recipe.getWidth(); x++) {
							menuGrid.set(y * 3 + x, grid.get(y * recipe.getWidth() + x));
						}
					}
					var encoded = new MachinePatternMenuFixture(level).encode(menuGrid);
					assertEquals(AEItemKey.of(result), new AECraftingPattern(AEItemKey.of(roundTrip(encoded)), level)
							.getOutputs().getFirst().what(), path.toString());
				}
				for (int index = 0; index < machines.size(); index++) {
					assertStack(before.get(index), machines.get(index), path.toString());
				}
				checked++;
			}
		}
		assertEquals(34, checked, "Every released centrifuge and apiary factory upgrade recipe");
	}

	@Test
	void shrinkingApiaryOutputAreaRejectsFullInventoryWithoutLosingAnyBeeOrItem() {
		Item sourceItem = machines().filter(item -> BuiltInRegistries.ITEM.getKey(item).getPath()
				.equals("overclocked_mek_apiary_factory")).findFirst().orElseThrow();
		Item targetItem = machines().filter(item -> BuiltInRegistries.ITEM.getKey(item).getPath()
				.equals("quantum_mek_apiary_factory")).findFirst().orElseThrow();
		var original = tile(sourceItem);
		populate(original);
		var source = dismantled(original, sourceItem);
		var before = source.copy();
		assertTrue(original.getInventorySlots(null).size() > tile(targetItem).getInventorySlots(null).size());
		var recipe = recipe(sourceItem, targetItem, "A");
		var input = CraftingInput.of(1, 1, List.of(source));
		assertFalse(recipe.matches(input, level(recipe)));
		assertTrue(recipe.assemble(input, registries).isEmpty());
		assertStack(before, source);
	}

}
