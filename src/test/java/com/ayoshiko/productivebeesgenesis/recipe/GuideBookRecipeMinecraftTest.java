package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import cy.jdkdigital.productivebees.common.item.BeeCage;
import io.netty.buffer.Unpooled;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.neoforged.neoforge.network.connection.ConnectionType;
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
			assertTrue(recipe.getRemainingItems(input).stream().allMatch(ItemStack::isEmpty));
			var namedCage = cageStack.copy();
			namedCage.set(DataComponents.CUSTOM_NAME, Component.literal("空笼"));
			var unrelatedData = new CompoundTag();
			unrelatedData.putInt("decoration", 7);
			namedCage.set(DataComponents.CUSTOM_DATA, CustomData.of(unrelatedData));
			assertTrue(recipe.matches(inputWith(namedCage), null));

			var filledCage = namedCage.copy();
			var beeData = unrelatedData.copy();
			beeData.putString("entity", "minecraft:bee");
			beeData.putInt("Age", -24000);
			filledCage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData));
			assertTrue(BeeCage.isFilled(filledCage));
			assertTrue(Ingredient.of(cageStack.getItem()).test(filledCage), "The former item-only ingredient accepted it");
			var filledInput = inputWith(filledCage);
			assertTrue(recipe.getRemainingItems(filledInput).stream().allMatch(ItemStack::isEmpty),
					"PB 13.14.0 supplies no crafting remainder for either cage");
			assertFalse(recipe.matches(filledInput, null));
			assertEquals(beeData, filledCage.get(DataComponents.CUSTOM_DATA).copyTag());
			assertEquals(1, filledCage.getCount());

			var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), provider, ConnectionType.NEOFORGE);
			try {
				Recipe.STREAM_CODEC.encode(buffer, recipe);
				var decoded = assertInstanceOf(ShapelessRecipe.class, Recipe.STREAM_CODEC.decode(buffer));
				assertTrue(decoded.matches(inputWith(namedCage), null));
				assertFalse(decoded.matches(filledInput, null));
			} finally {
				buffer.release();
			}

			var result = recipe.assemble(input, provider);
			assertEquals(ResourceLocation.parse("patchouli:guide_book"), BuiltInRegistries.ITEM.getKey(result.getItem()));
			assertEquals(ResourceLocation.parse("productivebeesgenesis:guide"),
					result.get(BuiltInRegistries.DATA_COMPONENT_TYPE.get(ResourceLocation.parse("patchouli:book"))));
		}
	}

	private static CraftingInput inputWith(ItemStack cage) {
		return CraftingInput.of(3, 1, List.of(cage, new ItemStack(Items.HONEYCOMB), new ItemStack(Items.BOOK)));
	}
}
