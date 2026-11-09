package com.ayoshiko.productivebeesgenesis.resource;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourceRecipeMinecraftTest {
	private static final ResourceLocation BEE = ResourceLocation.parse("example:hydrogen");

	private static String json(String amount, String chance) {
		return "{\"type\":\"productivebeesgenesis:resource_production\",\"bee\":\"example:hydrogen\","
				+ "\"outputs\":[{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":"
				+ amount + ",\"chance\":" + chance + "}]}";
	}

	private static RegistryFriendlyByteBuf buffer() {
		return new RegistryFriendlyByteBuf(Unpooled.buffer(),
				RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
	}

	@Test void registeredRecipeDispatchPreservesLongMaxAndRoundTrips() {
		assertSame(ResourceProductionRecipe.SERIALIZER,
				BuiltInRegistries.RECIPE_SERIALIZER.get(ResourceProductionRecipe.ID));
		assertSame(ResourceProductionRecipe.TYPE, BuiltInRegistries.RECIPE_TYPE.get(ResourceProductionRecipe.ID));
		var decoded = Recipe.CODEC.parse(JsonOps.INSTANCE,
				JsonParser.parseString(json("\"9223372036854775807\"", "0.25"))).getOrThrow();
		var recipe = assertInstanceOf(ResourceProductionRecipe.class, decoded);
		assertEquals(Long.MAX_VALUE, recipe.outputs().getFirst().amount());
		var encoded = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe).getOrThrow();
		assertEquals(recipe, Recipe.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
	}

	@Test void numericAndMalformedAmountsAreRejectedBeforeAnyProduction() {
		for (String amount : List.of("100", "\"0\"", "\"-1\"", "\"1.5\"", "\"1e3\"",
				"\"9223372036854775808\"", "\"01\"", "\"+1\"", "\" 1\"")) {
			assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
					JsonParser.parseString(json(amount, "1"))).error().isPresent(), amount);
		}
	}

	@Test void invalidProbabilityAndUnsupportedKindsAreRejected() {
		for (String chance : List.of("-0.1", "1.1", "1e999")) {
			assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
					JsonParser.parseString(json("\"1\"", chance))).error().isPresent());
		}
		var unsupported = json("\"100\"", "1").replace("chemical", "unsupported_kind");
		assertTrue(ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
				JsonParser.parseString(unsupported)).error().isPresent());
	}

	@Test void networkRoundTripIsExactAndCountIsBoundedBeforeAllocation() {
		var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
				JsonParser.parseString(json("\"9223372036854775807\"", "1"))).getOrThrow();
		var buffer = buffer();
		try {
			ResourceProductionRecipe.STREAM_CODEC.encode(buffer, recipe);
			assertEquals(recipe, ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
			buffer.clear();
			buffer.writeResourceLocation(BEE);
			buffer.writeVarInt(Integer.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> ResourceProductionRecipe.STREAM_CODEC.decode(buffer));
		} finally {
			buffer.release();
		}
	}

	@Test void immutableIndexSortsRecipesAndOldSnapshotSurvivesReload() {
		var recipe = ResourceProductionRecipe.CODEC.codec().parse(JsonOps.INSTANCE,
				JsonParser.parseString(json("\"100\"", "1"))).getOrThrow();
		var recipes = new ArrayList<>(List.of(
				new RecipeHolder<>(ResourceLocation.parse("example:z"), recipe),
				new RecipeHolder<>(ResourceLocation.parse("example:a"), recipe)));
		var old = ResourceRecipeIndex.build(1, recipes);
		recipes.clear();
		var current = ResourceRecipeIndex.build(2, recipes);
		assertEquals(List.of("example:a", "example:z"),
				old.forBee(BEE).stream().map(holder -> holder.id().toString()).toList());
		assertTrue(current.forBee(BEE).isEmpty());
		assertEquals(1, old.revision());
		assertThrows(UnsupportedOperationException.class, () -> old.forBee(BEE).clear());
		assertThrows(IllegalArgumentException.class,
				() -> ResourceRecipeIndex.build(3, List.of(old.forBee(BEE).getFirst(), old.forBee(BEE).getFirst())));
	}
}
