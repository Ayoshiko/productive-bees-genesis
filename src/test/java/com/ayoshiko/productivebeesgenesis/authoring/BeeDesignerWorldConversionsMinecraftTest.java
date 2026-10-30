package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.common.recipe.BlockConversionRecipe;
import cy.jdkdigital.productivebees.common.recipe.ItemConversionRecipe;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerWorldConversionsMinecraftTest {
	private static final String BEE = "test:conversion";
	private static final String OTHER = "test:conversion_other";
	private static Map<ResourceLocation, CompoundTag> prior;

	@BeforeAll
	static void bees() {
		prior = new HashMap<>(BeeReloadListener.INSTANCE.getData());
		Map<ResourceLocation, CompoundTag> data = new HashMap<>(prior);
		for (String id : Set.of(BEE, OTHER)) {
			data.put(ResourceLocation.parse(id), BeeCreator.create(ResourceLocation.parse(id), BeeDesignerDocument.create(id).getAsJsonObject("definition")));
		}
		BeeReloadListener.INSTANCE.setData(data);
	}

	@AfterAll
	static void restore() {
		BeeReloadListener.INSTANCE.setData(prior);
	}

	@Test
	void bothConversionTemplatesUseRealPbCodecsAndPreserveUnknownData() {
		var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		for (String type : Set.of("productivebees:item_conversion", "productivebees:block_conversion")) {
			JsonObject document = BeeDesignerDocument.create(BEE);
			String id = BeeDesignerDocument.addRecipe(document, type);
			JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(id);
			recipe.addProperty("external_note", "preserved");
			BeeDesignerWorldConversions.validate(recipe, BEE);
			Recipe<?> decoded = Recipe.CODEC.parse(registries.createSerializationContext(JsonOps.INSTANCE), recipe).getOrThrow();
			assertTrue(type.endsWith("item_conversion") ? decoded instanceof ItemConversionRecipe : decoded instanceof BlockConversionRecipe);
			assertTrue(BeeDesignerReader.belongs(recipe, BEE));
			assertFalse(BeeDesignerReader.belongs(recipe, OTHER));
			var files = BeeDesignerCompiler.files(Map.of(BEE, document.toString()));
			String path = "data/test/recipe/" + ResourceLocation.parse(id).getPath() + ".json";
			assertEquals(recipe, JsonParser.parseString(files.get(path)));
		}
	}

	@Test
	void sharedRecipeIsProtectedWhileCopyAndRetirementKeepOtherOwners() {
		JsonObject first = BeeDesignerDocument.create(BEE);
		String id = BeeDesignerDocument.addRecipe(first, "productivebees:item_conversion");
		JsonObject recipe = first.getAsJsonObject("recipes").getAsJsonObject(id);
		recipe.getAsJsonArray("bees").add(OTHER);
		JsonObject second = BeeDesignerDocument.create(OTHER);
		second.getAsJsonObject("recipes").add(id, recipe.deepCopy());
		assertDoesNotThrow(() -> BeeDesignerWorldConversions.validateChanges(first, first.deepCopy()));
		JsonObject changed = first.deepCopy();
		changed.getAsJsonObject("recipes").getAsJsonObject(id).addProperty("chance", 0.5);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerWorldConversions.validateChanges(first, changed));
		JsonObject removed = first.deepCopy();
		removed.getAsJsonObject("recipes").remove(id);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerWorldConversions.validateChanges(first, removed));
		JsonObject copied = BeeDesignerDocument.copyAs(first, "test:conversion_copy");
		JsonObject ownRecipe = copied.getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject();
		assertEquals(Set.of("test:conversion_copy"), BeeDesignerWorldConversions.owners(ownRecipe));
		assertDoesNotThrow(() -> BeeDesignerWorldConversions.validateChanges(null, copied));
		assertEquals(Set.of(BEE, OTHER), BeeDesignerWorldConversions.owners(recipe));
		var retired = BeeDesignerLifecycle.retire(first, 1, UUID.randomUUID());
		var files = BeeDesignerCompiler.files(Map.of(BEE, retired.toString(), OTHER, second.toString()));
		JsonObject active = JsonParser.parseString(files.get("data/test/recipe/" + ResourceLocation.parse(id).getPath() + ".json")).getAsJsonObject();
		assertEquals(Set.of(OTHER), BeeDesignerWorldConversions.owners(active));
	}

	@Test
	void invalidBlockStateIsRejectedWithoutChangingOriginal() {
		JsonObject document = BeeDesignerDocument.create(BEE);
		String id = BeeDesignerDocument.addRecipe(document, "productivebees:block_conversion");
		JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(id);
		recipe.getAsJsonObject("to").add("Properties", JsonParser.parseString("{\"missing_property\":\"true\"}"));
		String before = recipe.toString();
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerWorldConversions.validate(recipe, BEE));
		assertEquals(before, recipe.toString());
	}
}
