package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.MyriadRecipeGate;
import com.ayoshiko.productivebeesgenesis.util.MyriadRecipeProcessor;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.ayoshiko.productivebeesgenesis.util.RecipeIngredientFactory;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeBreedingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("minecraft")
class MyriadDisableMinecraftTest {
	private static final ResourceLocation OTHER = ResourceLocation.parse("productivebees:amethyst");
	private static boolean previousEnabled;

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		previousEnabled = ModConfig.SERVER.myriadCreationsEnabled.get();
		ModConfig.SERVER.myriadCreationsEnabled.set(false);
		MyriadCreationsEventHandler.invalidateEnabledCache();
	}

	@AfterAll
	static void restoreConfigs() {
		ModConfig.SERVER.myriadCreationsEnabled.set(previousEnabled);
		MyriadCreationsEventHandler.invalidateEnabledCache();
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void disabledMasterAppliesOnlyToExactMyriadIdentityAndCanResume() {
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsEnabled());
		assertTrue(ModConfig.SERVER.conversionEnabled.get());
		assertTrue(ModConfig.SERVER.breedingEnabled.get());
		assertTrue(ModConfig.SERVER.produceEnabled.get());
		assertTrue(MyriadCreationsEventHandler.isMyriadCreationsDisabled(PBConstants.MYRIADCREATIONS_TYPE));
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsDisabled(OTHER));
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsDisabled(ResourceLocation.parse("other:myriadcreations")));
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsDisabled(null));
		try {
			ModConfig.SERVER.myriadCreationsEnabled.set(true);
			MyriadCreationsEventHandler.invalidateEnabledCache();
			assertFalse(MyriadCreationsEventHandler.isMyriadCreationsDisabled(PBConstants.MYRIADCREATIONS_TYPE));
		} finally {
			ModConfig.SERVER.myriadCreationsEnabled.set(false);
			MyriadCreationsEventHandler.invalidateEnabledCache();
		}
	}

	@Test
	void oldConversionBreedingAndProductionRecipesCannotBypassMaster() {
		Supplier<BeeIngredient> vanilla = () -> new BeeIngredient(EntityType.BEE);
		var conversion = new BeeConversionRecipe(vanilla, bee(PBConstants.MYRIADCREATIONS_TYPE),
				Ingredient.of(Items.STICK), 1);
		var breeding = new BeeBreedingRecipe(bee(OTHER), bee(OTHER), bee(PBConstants.MYRIADCREATIONS_TYPE), 0);
		var production = new AdvancedBeehiveRecipe(bee(PBConstants.MYRIADCREATIONS_TYPE), List.of());
		assertTrue(MyriadRecipeGate.blocked(conversion));
		assertTrue(MyriadRecipeGate.blocked(breeding));
		assertTrue(MyriadRecipeGate.blocked(production));
		assertFalse(conversion.matches(null, null));
		assertFalse(breeding.matches(null, null));
		assertFalse(production.matches(null, null));
		assertFalse(MyriadRecipeGate.blocked(new BeeConversionRecipe(vanilla, bee(OTHER), Ingredient.of(Items.STICK), 1)));
		assertFalse(MyriadRecipeGate.blocked(new BeeBreedingRecipe(bee(OTHER), bee(OTHER), bee(OTHER), 0)));
		assertFalse(MyriadRecipeGate.blocked(new AdvancedBeehiveRecipe(bee(OTHER), List.of())));
	}

	@Test
	void sharedNestKeepsOtherResultsAndOriginalConditions() {
		var source = new BeeSpawningRecipe(Ingredient.of(Items.STONE), Ingredient.of(Items.DIAMOND),
				List.of(bee(PBConstants.MYRIADCREATIONS_TYPE), bee(OTHER)), HolderSet.direct());
		var holder = new RecipeHolder<>(ResourceLocation.parse("test:shared_nest"), source);
		var processor = new MyriadRecipeProcessor(new RecipeIngredientFactory(registries()));
		var filtered = (BeeSpawningRecipe) processor.processRecipe(holder).value();
		assertEquals(1, filtered.output.size());
		assertEquals(OTHER, filtered.output.getFirst().get().getBeeType());
		assertSame(source.ingredient, filtered.ingredient);
		assertSame(source.spawnItem, filtered.spawnItem);
		assertSame(source.biomes, filtered.biomes);
		assertEquals(2, source.output.size());
		var ordinary = new RecipeHolder<>(ResourceLocation.parse("test:ordinary_nest"),
				new BeeSpawningRecipe(source.ingredient, source.spawnItem, List.of(bee(OTHER)), source.biomes));
		assertSame(ordinary, processor.processRecipe(ordinary));
	}

	@Test
	void combIdentityIsRetainedWhileOnlyMyriadInputIsBlocked() {
		var handler = new InventoryHandlerHelper.BlockEntityItemStackHandler(12);
		var myriad = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get(), 7);
		myriad.set(PbDataComponents.beeType(), PBConstants.MYRIADCREATIONS_TYPE);
		handler.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, myriad);
		assertTrue(MyriadCreationsEventHandler.isMyriadCreationsHoneycomb(myriad));
		assertTrue(MyriadCreationsEventHandler.shouldBlockOperation(handler));
		assertFalse(MyriadCreationsEventHandler.appendRandomCombs(myriad, handler,
				net.minecraft.util.RandomSource.create(42), 1, List.of()));
		assertEquals(7, handler.getStackInSlot(InventoryHandlerHelper.INPUT_SLOT).getCount());
		var ordinary = myriad.copy();
		ordinary.set(PbDataComponents.beeType(), OTHER);
		handler.setStackInSlot(InventoryHandlerHelper.INPUT_SLOT, ordinary);
		assertFalse(MyriadCreationsEventHandler.isMyriadCreationsItem(ordinary));
		assertFalse(MyriadCreationsEventHandler.shouldBlockOperation(handler));
	}

	@Test
	void disabledStateAndPaidBeeSurviveSerializationWithoutChangingAssets() {
		var bee = new BeeSlot();
		var data = new CompoundTag();
		data.putString("entity", "productivebees:configurable_bee");
		data.putString("type", PBConstants.MYRIADCREATIONS_TYPE_STRING);
		bee.setBeeData(data);
		bee.setTicksInHive(73);
		bee.setState(BeeState.MYRIAD_DISABLED);
		var manager = mock(ApiarySlotManager.class);
		when(manager.getBeeSlotCount()).thenReturn(1);
		when(manager.getBeeSlots()).thenReturn(new BeeSlot[]{bee});
		var serializer = new ApiarySlotSerializer(manager);
		var original = new CompoundTag();
		serializer.saveBeeSlots(original);
		var restored = new BeeSlot();
		when(manager.getBeeSlots()).thenReturn(new BeeSlot[]{restored});
		serializer.loadBeeSlots(original);
		var roundTrip = new CompoundTag();
		serializer.saveBeeSlots(roundTrip);
		assertEquals(original, roundTrip);
		assertEquals(BeeState.MYRIAD_DISABLED, restored.getState());
		var paid = new ApiaryProductionState(1, () -> {});
		paid.counts[0] = 5;
		paid.bees[0].setBeeData(data.copy());
		var saved = paid.save(registries());
		var restoredPaid = new ApiaryProductionState(1, () -> {});
		restoredPaid.load(registries(), saved);
		assertEquals(saved, restoredPaid.save(registries()));
		assertEquals(5, restoredPaid.total());
	}

	private static Supplier<BeeIngredient> bee(ResourceLocation id) {
		return () -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id);
	}

	private static RegistryAccess registries() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}
}
