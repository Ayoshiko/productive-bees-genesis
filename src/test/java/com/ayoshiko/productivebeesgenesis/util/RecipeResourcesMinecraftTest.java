package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.mojang.serialization.Lifecycle;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.recipe.BeeFishingRecipe;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class RecipeResourcesMinecraftTest {
	private static final ResourceLocation OTHER = ResourceLocation.parse("productivebees:amethyst");
	private static boolean previousMyriadEnabled;
	private static boolean previousFishingEnabled;
	private static boolean previousSpawningEnabled;
	private static List<? extends String> previousFishingBiomes;
	private static String previousSpawningBiomes;
	private static Holder<Biome> plains;
	private static RecipeIngredientFactory factory;

	@BeforeAll
	static void prepareRegistriesAndConfig() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		previousMyriadEnabled = ModConfig.SERVER.myriadCreationsEnabled.get();
		previousFishingEnabled = ModConfig.SERVER.fishingEnabled.get();
		previousSpawningEnabled = ModConfig.SERVER.spawningEnabled.get();
		previousFishingBiomes = ModConfig.SERVER.fishingBiomes.get();
		previousSpawningBiomes = ModConfig.SERVER.spawningBiomes.get();
		ModConfig.SERVER.myriadCreationsEnabled.set(true);
		ModConfig.SERVER.fishingEnabled.set(true);
		ModConfig.SERVER.spawningEnabled.set(true);
		MyriadCreationsEventHandler.invalidateEnabledCache();

		var vanilla = VanillaRegistries.createLookup();
		var biomes = new MappedRegistry<Biome>(Registries.BIOME, Lifecycle.stable());
		plains = biomes.register(Biomes.PLAINS,
				vanilla.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS).value(), RegistrationInfo.BUILT_IN);
		TagKey<Biome> emptyTag = TagKey.create(Registries.BIOME, ResourceLocation.parse("test:empty"));
		TagKey<Biome> plainsTag = TagKey.create(Registries.BIOME, ResourceLocation.parse("test:plains"));
		biomes.freeze();
		biomes.bindTags(Map.of(emptyTag, List.of(), plainsTag, List.of(plains)));
		var provider = HolderLookup.Provider.create(Stream.<HolderLookup.RegistryLookup<?>>of(biomes.asLookup()));
		factory = new RecipeIngredientFactory(provider);
	}

	@AfterAll
	static void restoreConfig() {
		ModConfig.SERVER.myriadCreationsEnabled.set(previousMyriadEnabled);
		ModConfig.SERVER.fishingEnabled.set(previousFishingEnabled);
		ModConfig.SERVER.spawningEnabled.set(previousSpawningEnabled);
		ModConfig.SERVER.fishingBiomes.set(previousFishingBiomes);
		ModConfig.SERVER.spawningBiomes.set(previousSpawningBiomes);
		MyriadCreationsEventHandler.invalidateEnabledCache();
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
	}

	@Test
	void fishingDistinguishesExplicitUnrestrictedFromUnresolvedRestrictions() {
		assertEquals(0, factory.createBiomeHolderSet(List.of()).orElseThrow().size());
		assertTrue(factory.createBiomeHolderSet(List.of("test:missing")).isEmpty());
		var resolved = factory.createBiomeHolderSet(List.of("test:missing", "minecraft:plains")).orElseThrow();
		assertEquals(1, resolved.size());
		assertTrue(resolved.contains(plains));

		var processor = new MyriadRecipeProcessor(factory);
		var source = new BeeFishingRecipe(bee(PBConstants.MYRIADCREATIONS_TYPE), HolderSet.direct(plains), 0.5F);
		var holder = new RecipeHolder<>(ResourceLocation.parse("test:myriad_fishing"), source);
		ModConfig.SERVER.fishingBiomes.set(List.of("test:missing"));
		assertNull(processor.processRecipe(holder));
		ModConfig.SERVER.fishingBiomes.set(List.of());
		var unrestricted = (BeeFishingRecipe) processor.processRecipe(holder).value();
		assertEquals(0, unrestricted.biomes.size());
		ModConfig.SERVER.fishingBiomes.set(List.of("test:missing", "minecraft:plains"));
		var partial = (BeeFishingRecipe) processor.processRecipe(holder).value();
		assertEquals(1, partial.biomes.size());
		assertTrue(partial.biomes.contains(plains));
		var ordinary = new RecipeHolder<>(ResourceLocation.parse("test:ordinary_fishing"),
				new BeeFishingRecipe(bee(OTHER), source.biomes, 0.5F));
		assertSame(ordinary, processor.processRecipe(ordinary));
	}

	@Test
	void missingAndEmptySpawningTagsRejectOnlyMyriadAndPreserveSharedNest() {
		assertTrue(factory.createBiomeHolderSetFromString("#test:missing").isEmpty());
		assertTrue(factory.createBiomeHolderSetFromString("#test:empty").isEmpty());
		assertTrue(factory.createBiomeHolderSetFromString("test:missing").isEmpty());
		assertTrue(factory.createBiomeHolderSetFromString("").isEmpty());
		assertTrue(factory.createBiomeHolderSetFromString("#test:plains").orElseThrow().contains(plains));
		assertTrue(factory.createBiomeHolderSetFromString("minecraft:plains").orElseThrow().contains(plains));

		var processor = new MyriadRecipeProcessor(factory);
		var source = new BeeSpawningRecipe(Ingredient.of(Items.STONE), Ingredient.of(Items.DIAMOND),
				List.of(bee(PBConstants.MYRIADCREATIONS_TYPE), bee(OTHER)), HolderSet.direct(plains));
		var holder = new RecipeHolder<>(ResourceLocation.parse("test:shared_spawning"), source);
		for (String spec : List.of("#test:missing", "#test:empty", "test:missing")) {
			ModConfig.SERVER.spawningBiomes.set(spec);
			var preserved = (BeeSpawningRecipe) processor.processRecipe(holder).value();
			assertEquals(1, preserved.output.size());
			assertEquals(OTHER, preserved.output.getFirst().get().getBeeType());
			assertSame(source.ingredient, preserved.ingredient);
			assertSame(source.spawnItem, preserved.spawnItem);
			assertSame(source.biomes, preserved.biomes);
		}
		var onlyMyriad = new RecipeHolder<>(ResourceLocation.parse("test:myriad_spawning"),
				new BeeSpawningRecipe(source.ingredient, source.spawnItem,
						List.of(bee(PBConstants.MYRIADCREATIONS_TYPE)), source.biomes));
		assertNull(processor.processRecipe(onlyMyriad));
		var ordinary = new RecipeHolder<>(ResourceLocation.parse("test:ordinary_spawning"),
				new BeeSpawningRecipe(source.ingredient, source.spawnItem, List.of(bee(OTHER)), source.biomes));
		assertSame(ordinary, processor.processRecipe(ordinary));
		assertEquals(2, source.output.size());
	}

	private static Supplier<BeeIngredient> bee(ResourceLocation id) {
		return () -> new BeeIngredient(ModEntities.CONFIGURABLE_BEE.get(), id);
	}
}
