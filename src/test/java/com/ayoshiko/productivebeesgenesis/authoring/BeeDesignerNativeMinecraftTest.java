package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredient;
import cy.jdkdigital.productivebees.common.crafting.ingredient.BeeIngredientFactory;
import net.minecraft.world.entity.EntityType;
import cy.jdkdigital.productivebees.common.recipe.BeeSpawningRecipe;
import cy.jdkdigital.productivebees.init.ModEntities;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Holder;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerNativeMinecraftTest {
	private static Map<String, BeeIngredient> ingredients;
	private static Map<TagKey<EntityType<?>>, List<Holder<EntityType<?>>>> tags;

	@BeforeAll
	static void nativeIngredients() {
		tags = BuiltInRegistries.ENTITY_TYPE.getTags().collect(Collectors.toMap(pair -> pair.getFirst(), pair -> pair.getSecond().stream().toList()));
		Map<TagKey<EntityType<?>>, List<Holder<EntityType<?>>>> currentTags = new HashMap<>(tags);
		List<Holder<EntityType<?>>> inhabitants = new ArrayList<>();
		ModEntities.HIVE_BEES.getEntries().forEach(holder -> inhabitants.add(BuiltInRegistries.ENTITY_TYPE.getHolderOrThrow(BuiltInRegistries.ENTITY_TYPE.getResourceKey(holder.get()).orElseThrow())));
		ModEntities.SOLITARY_BEES.getEntries().forEach(holder -> inhabitants.add(BuiltInRegistries.ENTITY_TYPE.getHolderOrThrow(BuiltInRegistries.ENTITY_TYPE.getResourceKey(holder.get()).orElseThrow())));
		inhabitants.add(BuiltInRegistries.ENTITY_TYPE.getHolderOrThrow(BuiltInRegistries.ENTITY_TYPE.getResourceKey(EntityType.BEE).orElseThrow()));
		currentTags.put(EntityTypeTags.BEEHIVE_INHABITORS, inhabitants);
		BuiltInRegistries.ENTITY_TYPE.bindTags(currentTags);
		ingredients = new HashMap<>(BeeIngredientFactory.getOrCreateList());
		BeeIngredientFactory.addBee("minecraft:bee", new BeeIngredient(EntityType.BEE));
		BeeIngredientFactory.addBee(ModEntities.BLUE_BANDED_BEE.getId().toString(), new BeeIngredient(ModEntities.BLUE_BANDED_BEE.get()));
		BeeIngredientFactory.addBee(ModEntities.CREEPER_BEE.getId().toString(), new BeeIngredient(ModEntities.CREEPER_BEE.get()));
	}

	@AfterAll
	static void restoreIngredients() {
		Map<String, BeeIngredient> current = BeeIngredientFactory.getOrCreateList();
		current.clear();
		current.putAll(ingredients);
		BuiltInRegistries.ENTITY_TYPE.bindTags(tags);
	}

	private static MinecraftServer server(List<RecipeHolder<?>> recipes) {
		MinecraftServer server = mock(MinecraftServer.class);
		RecipeManager manager = mock(RecipeManager.class);
		ResourceManager resources = mock(ResourceManager.class);
		when(server.isSameThread()).thenReturn(true);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		when(server.getRecipeManager()).thenReturn(manager);
		when(server.getResourceManager()).thenReturn(resources);
		when(manager.getRecipes()).thenReturn(recipes);
		when(manager.byKey(any())).thenAnswer(call -> recipes.stream().filter(holder -> holder.id().equals(call.getArgument(0))).findFirst());
		when(resources.getResource(any())).thenReturn(Optional.empty());
		return server;
	}

	private static JsonObject request(int count) {
		JsonObject request = new JsonObject();
		request.addProperty("token", UUID.randomUUID().toString());
		request.addProperty("count", count);
		request.addProperty("drafts", true);
		return request;
	}

	@Test
	void catalogIncludesEveryRegisteredNativeBeeWithoutJsonDefinition() {
		var ids = BeeDesignerEggs.bees();
		List<ResourceLocation> nativeIds = new ArrayList<>();
		ModEntities.HIVE_BEES.getEntries().forEach(holder -> nativeIds.add(holder.getId()));
		ModEntities.SOLITARY_BEES.getEntries().forEach(holder -> nativeIds.add(holder.getId()));
		nativeIds.remove(ModEntities.CONFIGURABLE_BEE.getId());
		nativeIds.add(ResourceLocation.parse("minecraft:bee"));
		for (var id : nativeIds) {
			assertTrue(BeeDesignerNativeBees.contains(id), id.toString());
			assertTrue(ids.contains(id.toString()), id.toString());
			assertEquals(id, BeeDesignerEggs.bee(BeeDesignerEggs.egg(id.toString())));
			JsonObject query = new JsonObject();
			query.addProperty("search", id.toString());
			assertEquals(List.of(id.toString()), BeeDesignerCatalog.bees(query).getAsJsonArray("ids").asList().stream().map(value -> value.getAsString()).toList());
		}
		assertFalse(BeeDesignerNativeBees.contains(ModEntities.CONFIGURABLE_BEE.getId()));
		JsonObject query = new JsonObject();
		query.addProperty("search", "蓝");
		JsonArray names = new JsonArray();
		names.add(ModEntities.BLUE_BANDED_BEE.getId().toString());
		query.add("names", names);
		assertEquals(ModEntities.BLUE_BANDED_BEE.getId().toString(), BeeDesignerCatalog.bees(query).getAsJsonArray("ids").get(0).getAsString());
	}


	@Test
	void visibleCatalogIsBoundedAndDistinguishesRegisteredBeesFromLocalDrafts() {
		JsonObject query = new JsonObject();
		JsonArray ids = new JsonArray();
		ids.add(ModEntities.BLUE_BANDED_BEE.getId().toString());
		ids.add("test:not_registered");
		ids.add(ModEntities.BLUE_BANDED_BEE.getId().toString());
		query.add("entries", ids);
		JsonObject response = BeeDesignerCatalog.bees(query);
		assertTrue(response.get("metadata").getAsBoolean());
		assertEquals(1, response.getAsJsonArray("ids").size());
		assertEquals("pb", response.getAsJsonObject("origins").getAsJsonObject(ModEntities.BLUE_BANDED_BEE.getId().toString()).get("kind").getAsString());
		JsonArray preferred = new JsonArray();
		preferred.add("test:not_registered");
		query.add("preferred", preferred);
		response = BeeDesignerCatalog.bees(query);
		assertEquals(2, response.getAsJsonArray("ids").size());
		assertEquals("unknown", response.getAsJsonObject("origins").getAsJsonObject("test:not_registered").get("kind").getAsString());
		JsonArray oversized = new JsonArray();
		for (int index = 0; index < 33; index++) {
			oversized.add(ModEntities.BLUE_BANDED_BEE.getId().toString());
		}
		query.add("entries", oversized);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCatalog.bees(query));
	}

	@Test
	void readerKeepsNativeSpawningConversionAndProductionIdentities() {
		MinecraftServer empty = server(List.of());
		JsonObject conversion = JsonParser.parseString("{\"type\":\"productivebees:bee_conversion\",\"source\":\"minecraft:bee\",\"result\":\"productivebees:creeper_bee\",\"item\":{\"item\":\"minecraft:tnt\"}}").getAsJsonObject();
		JsonObject production = JsonParser.parseString("{\"type\":\"productivebees:advanced_beehive\",\"ingredient\":\"productivebees:creeper_bee\",\"results\":[{\"item\":{\"item\":\"minecraft:gunpowder\"}}]}").getAsJsonObject();
		var ops = empty.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		var spawning = new BeeSpawningRecipe(Ingredient.of(Items.ACACIA_LOG), Ingredient.EMPTY,
				List.of(() -> new BeeIngredient(ModEntities.BLUE_BANDED_BEE.get())), HolderSet.direct(List.of()));
		var holders = List.<RecipeHolder<?>>of(
				new RecipeHolder<>(ResourceLocation.parse("test:native_conversion"), Recipe.CODEC.parse(ops, conversion).getOrThrow()),
				new RecipeHolder<>(ResourceLocation.parse("test:native_production"), Recipe.CODEC.parse(ops, production).getOrThrow()),
				new RecipeHolder<>(ResourceLocation.parse("test:native_spawning"), spawning));
		MinecraftServer server = server(holders);
		var before = BeeReloadListener.INSTANCE.getData();
		var definitions = BeeDesignerSources.definitions;
		try {
			BeeDesignerSources.definitions = Map.of();
			JsonObject blue = BeeDesignerReader.readSources(BeeDesignerReader.snapshot(server, ModEntities.BLUE_BANDED_BEE.getId()));
			JsonObject creeper = BeeDesignerReader.readSources(BeeDesignerReader.snapshot(server, ModEntities.CREEPER_BEE.getId()));
			assertTrue(BeeDesignerNativeBees.recipesOnly(blue));
			assertTrue(blue.getAsJsonObject("definition").isEmpty());
			assertEquals(Set.of("test:native_spawning"), blue.getAsJsonObject("recipes").keySet());
			assertEquals(Set.of("test:native_conversion", "test:native_production"), creeper.getAsJsonObject("recipes").keySet());
			assertEquals("productivebees:creeper_bee", creeper.getAsJsonObject("recipes").getAsJsonObject("test:native_conversion").get("result").getAsString());
			assertTrue(BeeDesignerReader.unchanged(server, creeper));
			BeeDesignerCompiler.validate(creeper, server);
			var files = BeeDesignerCompiler.files(Map.of(creeper.get("id").getAsString(), creeper.toString()));
			assertFalse(files.keySet().stream().anyMatch(path -> path.matches("data/[^/]+/productivebees/.*")));
			assertEquals(2, files.keySet().stream().filter(path -> path.contains("/recipe/")).count());
			assertEquals("pb", BeeDesignerOrigins.describe(ModEntities.BLUE_BANDED_BEE.getId()).get("kind").getAsString());
			assertSame(before, BeeReloadListener.INSTANCE.getData());
		} finally {
			BeeDesignerSources.definitions = definitions;
			BeeDesignerRecipeIndex.clear(server);
		}
	}

	@Test
	void nativeScopeRejectsForgedAttributesDefinitionsAndCopies() {
		JsonObject nativeBee = BeeDesignerNativeBees.document(ModEntities.CREEPER_BEE.getId());
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(nativeBee, server(List.of())));
		for (String kind : List.of("definition", "names", "marker", "resource")) {
			JsonObject forged = nativeBee.deepCopy();
			switch (kind) {
				case "definition" -> forged.getAsJsonObject("definition").addProperty("attack", 100);
				case "names" -> forged.getAsJsonObject("names").addProperty("zh_cn", "替换的蜂种");
				case "marker" -> forged.remove(BeeDesignerNativeBees.RECIPES_ONLY);
				case "resource" -> BeeDesignerDocument.addRecipe(forged, "productivebeesgenesis:resource_production");
			}
			assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of(forged.get("id").getAsString(), forged.toString())), kind);
		}
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerDocument.copyAs(nativeBee, "test:copied_creeper"));
		var migration = BeeDesignerMigration.plan(Map.of(nativeBee.get("id").getAsString(), nativeBee.toString()),
				BeeDesignerEggs.bees(), List.of(), List.of(), BeeDesignerMigration.Policy.COPY_ALL);
		assertEquals(Set.of(nativeBee.get("id").getAsString()), migration.documents().keySet());
	}

	@Test
	void draftBatchKeepsBothNewBeesAndTheirCrossReference() {
		JsonObject first = BeeDesignerDocument.create("test:batch_a");
		JsonObject second = BeeDesignerDocument.create("test:batch_b");
		String recipe = BeeDesignerDocument.addRecipe(second, "productivebees:bee_conversion");
		second.getAsJsonObject("recipes").getAsJsonObject(recipe).addProperty("source", first.get("id").getAsString());
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(request(2));
		batch.add(first, null);
		batch.add(second, null);
		var managed = BeeDesignerSources.managed;
		try {
			BeeDesignerSources.managed = Map.of();
			var combined = batch.validate(server(List.of()));
			assertEquals(Set.of("test:batch_a", "test:batch_b"), combined.keySet());
			var index = JsonParser.parseString(BeeDesignerCompiler.files(combined).get("data/productivebeesgenesis/bee_designer/index.json")).getAsJsonObject();
			assertEquals("test:batch_a", index.getAsJsonObject("test:batch_b").getAsJsonObject("recipes").getAsJsonObject(recipe).get("source").getAsString());
			assertTrue(BeeDesignerSources.managed.isEmpty());
			assertEquals("new", first.get("baseline").getAsString());
		} finally {
			BeeDesignerSources.managed = managed;
		}
	}

	@Test
	void staleDraftStopsTheBatchWithoutDiscardingAcceptedDrafts() {
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(request(2));
		JsonObject first = BeeDesignerDocument.create("test:retained_draft");
		batch.add(first, null);
		JsonObject current = BeeDesignerNativeBees.document(ModEntities.BLUE_BANDED_BEE.getId());
		current.addProperty("baseline", BeeDesignerDocument.fingerprint(current));
		JsonObject stale = current.deepCopy();
		stale.addProperty("baseline", "old-server-revision");
		String before = stale.toString();
		assertTrue(assertThrows(IllegalArgumentException.class, () -> batch.add(stale, current)).getMessage().startsWith("designer.error.draft_conflict|"));
		assertEquals(1, batch.size());
		assertEquals(before, stale.toString());
		assertEquals(first, batch.document("test:retained_draft"));
		assertThrows(IllegalArgumentException.class, () -> batch.validate(server(List.of())));
	}

	@Test
	void unfinishedDraftPreventsEveryWorldWrite() {
		JsonObject draft = BeeDesignerDocument.create("test:unfinished_draft");
		draft.add("_editor_recovery", new JsonArray());
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(request(1));
		batch.add(draft, null);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> batch.validate(server(List.of()))).getMessage().contains("collection_unfinished"));
		assertEquals(draft, batch.document("test:unfinished_draft"));
	}
}
