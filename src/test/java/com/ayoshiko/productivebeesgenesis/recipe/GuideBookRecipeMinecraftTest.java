package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("minecraft")
class GuideBookRecipeMinecraftTest {

	@ParameterizedTest
	@CsvSource({"guide_book,bee_cage", "guide_book_sturdy,sturdy_bee_cage"})
	void survivalRecipeMatchesAndCreatesGenesisBook(String name, String cage) throws Exception {
		var provider = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		var resource = getClass().getResourceAsStream("/data/productivebeesgenesis/recipe/" + name + ".json");
		assertNotNull(resource);
		try (var reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
			var recipe = assertInstanceOf(ShapelessRecipe.class, Recipe.CODEC.parse(
					provider.createSerializationContext(JsonOps.INSTANCE), JsonParser.parseReader(reader)).getOrThrow());
			var cageStack = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("productivebees", cage)));
			var input = CraftingInput.of(3, 1, List.of(cageStack, new ItemStack(Items.HONEYCOMB), new ItemStack(Items.BOOK)));
			assertTrue(recipe.matches(input, null));
			assertFalse(recipe.matches(CraftingInput.of(2, 1, List.of(cageStack, new ItemStack(Items.BOOK))), null));
			var result = recipe.assemble(input, provider);
			assertEquals(ResourceLocation.parse("patchouli:guide_book"), BuiltInRegistries.ITEM.getKey(result.getItem()));
			assertEquals(ResourceLocation.parse("productivebeesgenesis:guide"),
					result.get(BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("patchouli:book"))));
		}
	}
}
