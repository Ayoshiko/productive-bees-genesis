package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerMigrationMinecraftTest {
	@Test
	void conflictCopyKeepsWholeBreedingChainAndUnknownFields(@TempDir Path directory) throws IOException {
		JsonObject first = BeeDesignerDocument.create("migration:first");
		BeeDesignerDocument.addRecipe(first, "productivebees:advanced_beehive");
		first.addProperty("unknown_extension", "migration:first");
		JsonObject second = BeeDesignerDocument.create("migration:second");
		String recipeId = BeeDesignerDocument.addRecipe(second, "productivebees:bee_breeding");
		JsonObject recipe = second.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		recipe.addProperty("parent1", "migration:first");
		JsonArray conditions = new JsonArray();
		JsonObject condition = new JsonObject();
		condition.addProperty("type", "productivebees:bee_exists");
		condition.addProperty("bee", "migration:first");
		conditions.add(condition);
		recipe.add("neoforge:conditions", conditions);
		Map<String, String> sources = Map.of("migration:first", first.toString(), "migration:second", second.toString());
		var plan = BeeDesignerMigration.plan(sources, List.of("migration:first"), List.of(), List.of(), BeeDesignerMigration.Policy.COPY_CONFLICTS);
		String copied = plan.identities().get("migration:first");
		assertNotEquals("migration:first", copied);
		assertEquals("migration:second", plan.identities().get("migration:second"));
		JsonObject movedFirst = JsonParser.parseString(plan.documents().get(copied)).getAsJsonObject();
		assertEquals("migration:first", movedFirst.get("unknown_extension").getAsString());
		JsonObject movedSecond = JsonParser.parseString(plan.documents().get("migration:second")).getAsJsonObject();
		assertEquals(copied, movedSecond.getAsJsonObject("recipes").getAsJsonObject(recipeId).get("parent1").getAsString());
		assertEquals(copied, movedSecond.getAsJsonObject("recipes").getAsJsonObject(recipeId).getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject().get("bee").getAsString());
		JsonObject credits = BeeDesignerCredits.empty();
		credits.addProperty("pack", "Migration source");
		var loaded = BeeDesignerArchive.read(BeeDesignerCollection.export(directory, plan.documents(), credits));
		assertEquals(2, loaded.size());
		assertEquals(movedSecond.get("recipes"), JsonParser.parseString(loaded.get("migration:second")).getAsJsonObject().get("recipes"));
		assertEquals(first.toString(), sources.get("migration:first"));
	}

	@Test
	void sharedConversionsUseOneRecipeWithAllCopiedOwners() {
		JsonObject first = BeeDesignerDocument.create("migration:shared_a");
		JsonObject second = BeeDesignerDocument.create("migration:shared_b");
		String recipeId = BeeDesignerDocument.addRecipe(first, "productivebees:item_conversion");
		JsonObject recipe = first.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		JsonArray owners = new JsonArray();
		owners.add("migration:shared_a");
		owners.add("migration:shared_b");
		recipe.add("bees", owners);
		second.getAsJsonObject("recipes").add(recipeId, recipe.deepCopy());
		var plan = BeeDesignerMigration.plan(Map.of("migration:shared_a", first.toString(), "migration:shared_b", second.toString()),
				List.of(), List.of(), List.of(), BeeDesignerMigration.Policy.COPY_ALL);
		var documents = plan.documents().values().stream().map(JsonParser::parseString).map(value -> value.getAsJsonObject()).toList();
		assertEquals(documents.get(0).get("recipes"), documents.get(1).get("recipes"));
		JsonObject moved = documents.get(0).getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject();
		assertEquals(Set.copyOf(plan.identities().values()), BeeDesignerWorldConversions.owners(moved));
		Map<String, String> files = BeeDesignerCompiler.files(plan.documents());
		assertEquals(1, files.keySet().stream().filter(path -> path.contains("/recipe/")).count());
	}

	@Test
	void sharedRecipeIsRemappedBeforeMixedIdentityDecisionsAreApplied() {
		JsonObject first = BeeDesignerDocument.create("migration:mixed_a");
		JsonObject second = BeeDesignerDocument.create("migration:mixed_b");
		String recipeId = BeeDesignerDocument.addRecipe(first, "productivebees:item_conversion");
		JsonObject recipe = first.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		JsonArray owners = new JsonArray();
		owners.add("migration:mixed_a");
		owners.add("migration:mixed_b");
		recipe.add("bees", owners);
		second.getAsJsonObject("recipes").add(recipeId, recipe.deepCopy());
		var plan = BeeDesignerMigration.plan(Map.of("migration:mixed_a", first.toString(), "migration:mixed_b", second.toString()),
				List.of("migration:mixed_b"), List.of(), List.of(), BeeDesignerMigration.Policy.COPY_CONFLICTS);
		assertEquals("migration:mixed_a", plan.identities().get("migration:mixed_a"));
		assertNotEquals("migration:mixed_b", plan.identities().get("migration:mixed_b"));
		JsonObject kept = JsonParser.parseString(plan.documents().get("migration:mixed_a")).getAsJsonObject();
		JsonObject copied = JsonParser.parseString(plan.documents().get(plan.identities().get("migration:mixed_b"))).getAsJsonObject();
		assertEquals(kept.get("recipes"), copied.get("recipes"));
		assertFalse(kept.getAsJsonObject("recipes").has(recipeId));
		JsonObject migratedRecipe = kept.getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject();
		assertEquals(Set.copyOf(plan.identities().values()), BeeDesignerWorldConversions.owners(migratedRecipe));
		assertEquals(1, BeeDesignerCompiler.files(plan.documents()).keySet().stream().filter(path -> path.contains("/recipe/")).count());
	}

	@Test
	void newSharedRecipeRequiresIdenticalDeclarationByEveryOwner() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		RecipeManager manager = mock(RecipeManager.class);
		when(manager.byKey(any())).thenReturn(Optional.empty());
		when(server.getRecipeManager()).thenReturn(manager);
		JsonObject first = BeeDesignerDocument.create("migration:shared_new_a");
		JsonObject second = BeeDesignerDocument.create("migration:shared_new_b");
		String recipeId = BeeDesignerDocument.addRecipe(first, "productivebees:item_conversion");
		JsonObject recipe = first.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		JsonArray owners = new JsonArray();
		owners.add("migration:shared_new_a");
		owners.add("migration:shared_new_b");
		recipe.add("bees", owners);
		JsonObject query = new JsonObject();
		query.addProperty("token", UUID.randomUUID().toString());
		query.addProperty("count", 2);
		BeeDesignerImportBatch inconsistent = new BeeDesignerImportBatch(query);
		inconsistent.add(first, null);
		inconsistent.add(second, null);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> inconsistent.validate(server))
				.getMessage().startsWith("designer.error.shared_conversion|"));
		assertEquals(2, inconsistent.size());
		second.getAsJsonObject("recipes").add(recipeId, recipe.deepCopy());
		BeeDesignerImportBatch consistent = new BeeDesignerImportBatch(query);
		consistent.add(first, null);
		consistent.add(second, null);
		assertDoesNotThrow(() -> consistent.validate(server));
	}

	@Test
	void nameAndRecipeConflictsAreResolvedBeforeExport() {
		JsonObject first = BeeDesignerDocument.create("first:gold");
		JsonObject second = BeeDesignerDocument.create("second:gold_bee");
		String recipe = BeeDesignerDocument.addRecipe(first, "productivebees:advanced_beehive");
		var plan = BeeDesignerMigration.plan(Map.of("first:gold", first.toString(), "second:gold_bee", second.toString()),
				List.of(), List.of(), List.of(recipe), BeeDesignerMigration.Policy.COPY_CONFLICTS);
		assertNotEquals("first:gold", plan.identities().get("first:gold"));
		assertDoesNotThrow(() -> BeeDesignerCompiler.files(plan.documents()));
		assertEquals(2, plan.documents().size());
	}

	@Test
	void savingBatchPreservesCorruptOriginalBytes(@TempDir Path directory) throws IOException {
		Path old = directory.resolve("drafts/migration/retained.json");
		Files.createDirectories(old.getParent());
		String original = "旧草稿原文，不能丢失";
		Files.writeString(old, original);
		JsonObject incoming = BeeDesignerDocument.create("migration:retained");
		var plan = BeeDesignerMigration.plan(Map.of("migration:retained", incoming.toString()), List.of(), List.of("migration:retained"),
				List.of(), BeeDesignerMigration.Policy.KEEP_IDENTITIES);
		assertEquals(List.of("migration:retained"), BeeDesignerMigration.saveDrafts(directory, plan));
		assertEquals(1, BeeDesignerDrafts.archives(directory).size());
		String token = BeeDesignerDrafts.archives(directory).getFirst().token();
		assertEquals(original, Files.readString(directory.resolve("drafts-archive").resolve(token)));
		assertEquals("migration:retained", BeeDesignerDrafts.read(directory, "migration:retained").get("id").getAsString());
	}

	@Test
	void collectionAbovePreviousLimitRoundTripsEveryBee(@TempDir Path directory) throws IOException {
		Map<String, String> sources = new LinkedHashMap<>();
		for (int index = 0; index < 150; index++) {
			JsonObject bee = BeeDesignerDocument.create("migration:bee_" + index);
			sources.put(bee.get("id").getAsString(), bee.toString());
		}
		JsonObject credits = BeeDesignerCredits.empty();
		credits.addProperty("pack", "Complete collection");
		var restored = BeeDesignerArchive.read(BeeDesignerCollection.export(directory, sources, credits));
		assertEquals(sources.keySet(), restored.keySet());
		var plan = BeeDesignerMigration.plan(restored, List.of(), List.of(), List.of(), BeeDesignerMigration.Policy.COPY_CONFLICTS);
		assertEquals(150, plan.documents().size());
		assertEquals(150, BeeDesignerMigration.saveDrafts(directory.resolve("target"), plan).size());
	}

	@Test
	void batchValidationAcceptsReferencesToOtherNewBeesAndRejectsMissingAssets() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		RecipeManager manager = mock(RecipeManager.class);
		when(manager.byKey(any())).thenReturn(Optional.empty());
		when(server.getRecipeManager()).thenReturn(manager);
		JsonObject first = BeeDesignerDocument.create("migration:batch_first");
		BeeDesignerDocument.addRecipe(first, "productivebees:advanced_beehive");
		JsonObject second = BeeDesignerDocument.create("migration:batch_second");
		String id = BeeDesignerDocument.addRecipe(second, "productivebees:bee_conversion");
		second.getAsJsonObject("recipes").getAsJsonObject(id).addProperty("source", "migration:batch_first");
		JsonObject query = new JsonObject();
		query.addProperty("token", UUID.randomUUID().toString());
		query.addProperty("count", 2);
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(query);
		batch.add(first, null);
		batch.add(second, null);
		assertDoesNotThrow(() -> batch.validate(server));
		JsonObject invalid = BeeDesignerDocument.create("migration:missing_assets");
		invalid.getAsJsonObject("definition").addProperty("flowerItem", "missing_pack:required_flower");
		query.addProperty("count", 1);
		BeeDesignerImportBatch rejected = new BeeDesignerImportBatch(query);
		rejected.add(invalid, null);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> rejected.validate(server)).getMessage().contains("unknown item"));
		assertEquals(1, rejected.size());
	}

	@Test
	void preservedLegacyPbChanceKeepsRuntimeMeaningButEditsMustUseValidProbability() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		JsonObject source = BeeDesignerDocument.create("migration:legacy_chance");
		String id = BeeDesignerDocument.addRecipe(source, "productivebees:advanced_beehive");
		JsonObject recipe = source.getAsJsonObject("recipes").getAsJsonObject(id);
		recipe.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("chance", 30);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(source, server));
		BeeDesignerLegacyOutputs.capture(source);
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(source, server));
		var plan = BeeDesignerMigration.plan(Map.of("migration:legacy_chance", source.toString()), List.of("migration:legacy_chance"),
				List.of(), List.of(), BeeDesignerMigration.Policy.COPY_CONFLICTS);
		JsonObject copied = JsonParser.parseString(plan.documents().values().iterator().next()).getAsJsonObject();
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(copied, server));
		JsonObject moved = copied.getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject();
		assertEquals(30, moved.getAsJsonArray("results").get(0).getAsJsonObject().get("chance").getAsDouble());
		moved.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("min", 2);
		moved.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("max", 2);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(copied, server));
		moved.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("chance", 1);
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(copied, server));
	}

	@Test
	void unchangedLegacyPbRangeIsPreservedAndEditedInvalidRangeIsRejected() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		JsonObject source = BeeDesignerDocument.create("migration:legacy_range");
		String id = BeeDesignerDocument.addRecipe(source, "productivebees:advanced_beehive");
		JsonObject output = source.getAsJsonObject("recipes").getAsJsonObject(id).getAsJsonArray("results").get(0).getAsJsonObject();
		output.addProperty("min", 2);
		output.addProperty("max", 1);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(source, server));
		BeeDesignerLegacyOutputs.capture(source);
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(source, server));
		var plan = BeeDesignerMigration.plan(Map.of("migration:legacy_range", source.toString()), List.of(),
				List.of(), List.of(), BeeDesignerMigration.Policy.COPY_ALL);
		JsonObject copied = JsonParser.parseString(plan.documents().values().iterator().next()).getAsJsonObject();
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(copied, server));
		JsonObject migrated = copied.getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject()
				.getAsJsonArray("results").get(0).getAsJsonObject();
		assertEquals(2, migrated.get("min").getAsInt());
		assertEquals(1, migrated.get("max").getAsInt());
		migrated.addProperty("chance", 0.5);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(copied, server));
	}

	@Test
	void nonProducingPbBeeMayPreserveAirFlowerButMissingBlocksRemainRejected() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze());
		JsonObject source = BeeDesignerDocument.create("migration:no_flower");
		JsonObject definition = source.getAsJsonObject("definition");
		definition.remove("flowerItem");
		definition.addProperty("flowerBlock", "minecraft:air");
		definition.addProperty("createComb", false);
		assertDoesNotThrow(() -> BeeDesignerCompiler.validate(source, server));
		definition.addProperty("createComb", true);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(source, server));
		definition.addProperty("createComb", false);
		definition.addProperty("flowerBlock", "missing_pack:no_flower");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validate(source, server));
	}

	@Test
	void myriadBatchKeepsBuiltinRecipesAndValidatesOnlyItsAdditionalRecipeScope() {
		MinecraftServer server = mock(MinecraftServer.class);
		RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).freeze();
		when(server.registryAccess()).thenReturn(registries);
		when(server.isSameThread()).thenReturn(true);
		ResourceManager resources = mock(ResourceManager.class);
		when(resources.getResource(any())).thenReturn(Optional.empty());
		when(server.getResourceManager()).thenReturn(resources);
		RecipeManager manager = mock(RecipeManager.class);
		when(server.getRecipeManager()).thenReturn(manager);
		when(manager.byKey(any())).thenReturn(Optional.empty());
		JsonObject source = BeeDesignerDocument.create("productivebees:myriadcreations");
		String generated = BeeDesignerDocument.addRecipe(source, "productivebees:advanced_beehive");
		String builtin = "migration:myriad_builtin";
		source.getAsJsonObject("recipes").add(builtin, source.getAsJsonObject("recipes").remove(generated));
		var decoded = Recipe.CODEC.parse(
				registries.createSerializationContext(JsonOps.INSTANCE), source.getAsJsonObject("recipes").get(builtin)).getOrThrow();
		when(manager.getRecipes()).thenReturn(List.of(new RecipeHolder<>(
				ResourceLocation.parse(builtin), decoded)));
		var definitions = BeeDesignerSources.definitions;
		var managed = BeeDesignerSources.managed;
		var bees = new HashMap<>(BeeReloadListener.INSTANCE.getData());
		try {
			var id = ResourceLocation.parse("productivebees:myriadcreations");
			BeeDesignerSources.definitions = Map.of(id, source.get("definition").toString());
			BeeDesignerSources.managed = Map.of();
			var current = new HashMap<>(bees);
			current.put(id, BeeCreator.create(id, source.getAsJsonObject("definition")));
			BeeReloadListener.INSTANCE.setData(current);
			JsonObject original = source.deepCopy();
			BeeDesignerMyriad.prepareRecipes(original);
			assertTrue(BeeDesignerReader.unchanged(server, original));
			JsonObject imported = original.deepCopy();
			String extra = BeeDesignerDocument.addRecipe(imported, "productivebees:bee_conversion");
			imported.getAsJsonObject("recipes").getAsJsonObject(extra).addProperty("source", id.toString());
			JsonObject query = new JsonObject();
			query.addProperty("token", UUID.randomUUID().toString());
			query.addProperty("count", 1);
			BeeDesignerImportBatch batch = new BeeDesignerImportBatch(query);
			batch.add(imported, original);
			Map<String, String> documents = batch.validate(server);
			JsonObject accepted = JsonParser.parseString(documents.get(id.toString())).getAsJsonObject();
			assertTrue(accepted.getAsJsonObject("recipes").has(extra));
			assertTrue(BeeDesignerMyriad.customRecipe(accepted.getAsJsonObject("recipes").getAsJsonObject(extra)));
			assertFalse(accepted.getAsJsonObject("recipes").has(builtin));
			assertFalse(accepted.getAsJsonArray("disabled_recipes").contains(new JsonPrimitive(builtin)));
		} finally {
			BeeDesignerSources.definitions = definitions;
			BeeDesignerSources.managed = managed;
			BeeReloadListener.INSTANCE.setData(bees);
			BeeDesignerRecipeIndex.clear(server);
		}
	}

	@Test
	void batchCannotImportUnconfirmedDeletionAndKeepsDraft() {
		MinecraftServer server = mock(MinecraftServer.class);
		JsonObject source = BeeDesignerDocument.create("migration:retired_import");
		JsonObject deleted = BeeDesignerLifecycle.retire(source, 0, new UUID(0, 0));
		JsonObject request = new JsonObject();
		request.addProperty("token", UUID.randomUUID().toString());
		request.addProperty("count", 1);
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(request);
		batch.add(deleted, null);
		assertTrue(assertThrows(IllegalArgumentException.class, () -> batch.validate(server))
				.getMessage().equals("designer.error.delete_confirmation"));
		assertEquals(1, batch.size());
		assertTrue(BeeDesignerLifecycle.deleted(batch.document("migration:retired_import")));
	}

	@Test
	void compiledRecipePreservesIdentityAndContentWithUnificationMarker() {
		JsonObject source = BeeDesignerDocument.create("migration:preserved");
		String id = BeeDesignerDocument.addRecipe(source, "productivebees:advanced_beehive");
		JsonObject before = source.getAsJsonObject("recipes").getAsJsonObject(id).deepCopy();
		var files = BeeDesignerCompiler.files(Map.of("migration:preserved", source.toString()));
		ResourceLocation location = ResourceLocation.parse(id);
		JsonObject compiled = JsonParser.parseString(files.get("data/" + location.getNamespace()
				+ "/recipe/" + location.getPath() + ".json")).getAsJsonObject();
		assertTrue(compiled.remove("productivebeesgenesis:designer_recipe").getAsBoolean());
		assertEquals(before, compiled);
		assertEquals(before, source.getAsJsonObject("recipes").get(id));
	}

	@Test
	void duplicateOrChangedBatchCannotCommit() {
		JsonObject query = new JsonObject();
		query.addProperty("token", UUID.randomUUID().toString());
		query.addProperty("count", 2);
		BeeDesignerImportBatch batch = new BeeDesignerImportBatch(query);
		JsonObject first = BeeDesignerDocument.create("migration:duplicate");
		batch.add(first, null);
		assertThrows(IllegalArgumentException.class, () -> batch.add(first, null));
		JsonObject changed = query.deepCopy();
		changed.addProperty("token", UUID.randomUUID().toString());
		assertThrows(IllegalArgumentException.class, () -> batch.check(changed));
		assertEquals(1, batch.size());
	}

	@Test
	void instanceBrowserDistinguishesOfflineDefinitionsAndRuntimeRegistration(@TempDir Path directory) throws IOException {
		Path data = directory.resolve("source/kubejs/data/productivebees/productivebees/custom/offline.json");
		Files.createDirectories(data.getParent());
		Files.writeString(data, "{\"primaryColor\":\"#123456\",\"flowerItem\":\"minecraft:poppy\"}");
		var offline = BeeDesignerInstanceCatalog.read(directory.resolve("source"));
		assertFalse(offline.runtimeSnapshot());
		assertEquals(Set.of("productivebees:offline"), offline.documents().keySet());
		JsonObject project = BeeDesignerDocument.create("productivebees:offline");
		BeeDesignerInstanceCatalog.saveRuntime(directory.resolve("source"), Map.of("productivebees:offline", project.toString()),
				List.of("productivebees:offline"), 17);
		var runtime = BeeDesignerInstanceCatalog.read(directory.resolve("source"));
		assertTrue(runtime.runtimeSnapshot());
		assertEquals(List.of("productivebees:offline"), runtime.registered());
		assertEquals(project, JsonParser.parseString(runtime.documents().get("productivebees:offline")));
	}
}
