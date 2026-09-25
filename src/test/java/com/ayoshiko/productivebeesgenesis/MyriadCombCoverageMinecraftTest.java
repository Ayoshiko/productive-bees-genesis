package com.ayoshiko.productivebeesgenesis;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ayoshiko.productivebeesgenesis.mek.WeightedTypeSelector;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.HeatedCentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import cy.jdkdigital.productivebees.init.ModBlocks;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.init.ModRecipeTypes;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import cy.jdkdigital.productivelib.common.recipe.TagOutputRecipe.ChancedOutput;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** 使用真实 PB 配方、蜜脾、库存及已应用的 Mixin 验证转化守恒。 */
@Tag("minecraft")
class MyriadCombCoverageMinecraftTest {

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@AfterEach
	void resetCaches() {
		BeeInfoHelper.invalidateCache();
		MyriadBeeTypeCache.clearAll();
	}

	@Test
	void allFourSpecialCombsEnterCatalogWithoutCentrifugeRecipes() {
		var ghostly = comb("honeycomb_ghostly");
		var milky = comb("honeycomb_milky");
		var powdery = comb("honeycomb_powdery");
		var wasted = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		wasted.set(PbDataComponents.beeType(), id("wasted_radioactive"));
		var recipes = List.of(
				recipe("ghostly", new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id("ghostly")), ghostly),
				recipe("rancher", new BeeIngredient(ModEntities.RANCHER_BEE.get()), milky),
				recipe("creeper", new BeeIngredient(ModEntities.CREEPER_BEE.get()), powdery),
				recipe("wasted", new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id("wasted_radioactive")), wasted),
				recipe("self", new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), PBConstants.MYRIADCREATIONS_TYPE), ghostly),
				recipe("not_comb", new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id("not_comb")), new ItemStack(Items.DIAMOND)));
		var level = level(recipes);
		var candidates = AbstractCombEventHandler.buildBeeTypeCache(level, Set.of(PBConstants.MYRIADCREATIONS_TYPE), type -> true);
		assertEquals(Set.of(id("ghostly"), id("rancher_bee"), id("creeper_bee"), id("wasted_radioactive")), Set.copyOf(candidates));
		assertEquals(List.of(id("ghostly")), AbstractCombEventHandler.buildBeeTypeCache(level,
				Set.of(PBConstants.MYRIADCREATIONS_TYPE), id("ghostly")::equals));
		publish(level);
		var snapshot = MyriadBeeTypeCache.snapshot();
		for (var expected : List.of(ghostly, milky, powdery, wasted)) {
			assertTrue(java.util.Arrays.stream(snapshot.honeycombTemplates())
					.anyMatch(actual -> ItemStack.isSameItemSameComponents(expected, actual)));
		}
		var output = RandomHoneycombSelector.generateAggregatedStacks(40, snapshot, false, RandomSource.create(5));
		assertEquals(40, output.stream().mapToInt(ItemStack::getCount).sum());
		assertEquals(4, output.size());
		assertEquals(4, snapshot.combBlockBeeTypes().size());
	}

	@Test
	void multipleRecipesPreserveDistinctComponentsAndReloadReplacesTemplates() {
		var type = id("variants");
		var named = new ItemStack(Items.HONEYCOMB);
		named.set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
		var ingredient = new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), type);
		var level = level(List.of(recipe("a", ingredient, new ItemStack(Items.HONEYCOMB)),
				recipe("b", ingredient, named), recipe("duplicate", ingredient, named)));
		publish(level);
		assertEquals(2, MyriadBeeTypeCache.snapshot().honeycombVariants().get(type).size());
		assertTrue(RandomHoneycombSelector.buildCombBlockTemplate(type, new ItemStack(Items.DIAMOND)).isEmpty());
		when(level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get()))
				.thenReturn(List.of(recipe("reloaded", ingredient, comb("honeycomb_ghostly"))));
		BeeInfoHelper.invalidateCache();
		MyriadBeeTypeCache.invalidate();
		publish(level);
		assertEquals(1, MyriadBeeTypeCache.snapshot().honeycombVariants().get(type).size());
		assertTrue(MyriadBeeTypeCache.snapshot().honeycombVariants().get(type).getFirst().is(comb("honeycomb_ghostly").getItem()));
	}

	@Test
	void partiallyReadyRecipesRetryAfterBackoff() {
		var ready = new AtomicBoolean(false);
		var recipe = new AdvancedBeehiveRecipe(() -> {
			if (!ready.get()) throw new IllegalStateException("not ready");
			return new BeeIngredient(ModEntities.RANCHER_BEE.get());
		}, List.of(new ChancedOutput(Ingredient.of(comb("honeycomb_milky")), 1, 1, 1)));
		var level = level(List.of(new RecipeHolder<>(id("late"), recipe)));
		assertTrue(BeeInfoHelper.getBeeTypesWithProduce(level).isEmpty());
		assertFalse(BeeInfoHelper.isProduceIndexComplete());
		ready.set(true);
		when(level.getGameTime()).thenReturn(19L);
		assertTrue(BeeInfoHelper.getBeeTypesWithProduce(level).isEmpty());
		when(level.getGameTime()).thenReturn(20L);
		assertEquals(List.of(id("rancher_bee")), BeeInfoHelper.getBeeTypesWithProduce(level));
		assertTrue(BeeInfoHelper.isProduceIndexComplete());
	}

	@Test
	void selectionCachesSeparatePoolsAndTrackMoreThan512Types() {
		var level = level(List.of());
		var otherLevel = level(List.of());
		List<ResourceLocation> first = IntStream.range(0, 600).mapToObj(i -> id("first_" + i)).toList();
		List<ResourceLocation> second = IntStream.range(0, 10).mapToObj(i -> id("second_" + i)).toList();
		var a = MyriadSelectionCache.selectDistinctBeeTypesCached(3, level, first);
		assertSame(a, MyriadSelectionCache.selectDistinctBeeTypesCached(3, level, first));
		assertTrue(second.containsAll(MyriadSelectionCache.selectDistinctBeeTypesCached(3, level, second)));
		assertNotSame(a, MyriadSelectionCache.selectDistinctBeeTypesCached(3, otherLevel, first));
		var selector = WeightedTypeSelector.getInstance();
		selector.onTypesUpdated(first);
		var factory = new Object();
		var selected = selector.selectWeighted(3, level, first, factory);
		assertEquals(selected, selector.selectWeighted(3, level, first, factory));
		assertTrue(second.containsAll(selector.selectWeighted(3, level, second, factory)));
		selector.recordOutputs(Map.of(first.get(599), 10000));
		selector.rebuildWeightsIfNeeded(level);
		when(level.getGameTime()).thenReturn(20L);
		selector.rebuildWeightsIfNeeded(level);
		var weights = selector.getWeightsFor(List.of(first.getFirst(), first.get(599)));
		assertTrue(weights[1] < weights[0], "Type 600 must participate in weight accounting");
	}

	@Test
	void pbAndHeatedMixinsProduceLastInputAndPreserveItWhenFull() throws Exception {
		var ingredient = new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id("ghostly"));
		publish(level(List.of(recipe("ghostly", ingredient, comb("honeycomb_ghostly")))));
		var recipe = new RecipeHolder<>(id("conversion"), new CentrifugeRecipe(
				Ingredient.of(ModItems.CONFIGURABLE_HONEYCOMB.get()), List.of(),
				SizedFluidIngredient.of(Fluids.WATER, 1), 1));
		for (boolean heated : new boolean[]{false, true}) {
			CentrifugeBlockEntity entity = heated
					? new HeatedCentrifugeBlockEntity(BlockPos.ZERO, ModBlocks.HEATED_CENTRIFUGE.get().defaultBlockState())
					: new CentrifugeBlockEntity(BlockPos.ZERO, ModBlocks.CENTRIFUGE.get().defaultBlockState());
			var method = (heated ? HeatedCentrifugeBlockEntity.class : CentrifugeBlockEntity.class).getDeclaredMethod(
					"completeRecipeProcessing", RecipeHolder.class, IItemHandlerModifiable.class, RandomSource.class);
			method.setAccessible(true);
			var inventory = (InventoryHandlerHelper.BlockEntityItemStackHandler) entity.inventoryHandler;
			var input = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
			input.set(PbDataComponents.beeType(), PBConstants.MYRIADCREATIONS_TYPE);
			inventory.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, input.copy());
			method.invoke(entity, recipe, inventory, RandomSource.create(1));
			assertTrue(inventory.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT).isEmpty());
			assertEquals(1, inventory.getStackInSlot(2).getCount());
			assertTrue(inventory.getStackInSlot(2).is(comb("honeycomb_ghostly").getItem()));
			for (int slot : inventory.getOutputSlots()) inventory.setStackInSlot(slot, new ItemStack(Items.DIAMOND, 64));
			inventory.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, input.copy());
			method.invoke(entity, recipe, inventory, RandomSource.create(1));
			assertEquals(1, inventory.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT).getCount());
			for (int slot : inventory.getOutputSlots()) assertEquals(64, inventory.getStackInSlot(slot).getCount());
		}
	}

	@Test
	void pbSideProductsNeedAWholeChunkAndFailureDoesNotMutate() {
		var type = id("ghostly");
		var template = comb("honeycomb_ghostly");
		var snapshot = new MyriadBeeTypeCache.BeeTypeCacheSnapshot(List.of(type), new ItemStack[]{template},
				new ItemStack[0], Map.of(type, template), Map.of(), Map.of(type, List.of(template)), Map.of(), List.of());
		var inventory = new InventoryHandlerHelper.BlockEntityItemStackHandler(12);
		for (int slot : inventory.getOutputSlots()) inventory.setStackInSlot(slot, new ItemStack(Items.DIAMOND, 64));
		inventory.setStackInSlot(2, template.copyWithCount(63));
		inventory.setStackInSlot(3, new ItemStack(Items.GOLD_INGOT, 60));
		inventory.setStackInSlot(4, new ItemStack(Items.GOLD_INGOT, 60));
		assertFalse(AbstractCombEventHandler.appendRandomCombsInternal(new ItemStack(Items.HONEYCOMB),
				inventory, RandomSource.create(1), 1, stack -> true, stack -> false, snapshot,
				List.of(new ItemStack(Items.GOLD_INGOT, 8))));
		assertEquals(63, inventory.getStackInSlot(2).getCount());
		assertEquals(60, inventory.getStackInSlot(3).getCount());
	}

	private static void publish(ServerLevel level) {
		try (var base = mockStatic(AbstractCombEventHandler.class, CALLS_REAL_METHODS)) {
			base.when(AbstractCombEventHandler::isBeeReloadListenerReady).thenReturn(true);
			MyriadBeeTypeCache.updateBeeTypeCache(level);
		}
	}

	private static ServerLevel level(List<RecipeHolder<AdvancedBeehiveRecipe>> recipes) {
		var level = mock(ServerLevel.class);
		var manager = mock(RecipeManager.class);
		when(level.getRecipeManager()).thenReturn(manager);
		when(level.getRandom()).thenReturn(RandomSource.create(5));
		when(manager.getAllRecipesFor(ModRecipeTypes.ADVANCED_BEEHIVE_TYPE.get())).thenReturn(recipes);
		return level;
	}

	private static RecipeHolder<AdvancedBeehiveRecipe> recipe(String name, BeeIngredient ingredient, ItemStack stack) {
		return new RecipeHolder<>(id(name), new AdvancedBeehiveRecipe(() -> ingredient,
				List.of(new ChancedOutput(Ingredient.of(stack), 1, 1, 1))));
	}

	private static ItemStack comb(String path) {
		var stack = new ItemStack(BuiltInRegistries.ITEM.get(id(path)));
		assertFalse(stack.isEmpty(), path);
		return stack;
	}

	private static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath("productivebees", path);
	}
}
