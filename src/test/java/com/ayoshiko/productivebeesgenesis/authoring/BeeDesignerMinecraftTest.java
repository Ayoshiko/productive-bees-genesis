package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeRecipe;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerColorValue;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.common.crafting.DataComponentIngredient;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import cy.jdkdigital.productivebees.common.recipe.AdvancedBeehiveRecipe;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import net.neoforged.fml.config.ConfigTracker;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import cy.jdkdigital.productivebees.util.BeeCreator;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import net.minecraft.server.MinecraftServer;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerMinecraftTest {
	@Test
	void exportedNameMatchesPbForIdsContainingBeeSuffixAndRejectsCollisions() {
		JsonObject document = BeeDesignerDocument.create("test:test_bee");
		document.getAsJsonObject("names").addProperty("zh_cn", "名称验收蜂");
		String pbName = cy.jdkdigital.productivebees.common.entity.bee.ProductiveBee.getBeeName(ResourceLocation.parse("test:test_bee"));
		String key = "entity.productivebees." + pbName + "_bee";
		assertEquals(key, BeeDesignerDocument.translationKey("test:test_bee"));
		var files = BeeDesignerCompiler.files(Map.of("test:test_bee", document.toString()));
		assertEquals("名称验收蜂", JsonParser.parseString(files.get("assets/productivebees/lang/zh_cn.json"))
				.getAsJsonObject().get(key).getAsString());
		JsonObject duplicate = BeeDesignerDocument.create("other:test");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of(
				"test:test_bee", document.toString(), "other:test", duplicate.toString())));
	}

	@Test
	void myriadSupportsModelAndTraitsWithSameAttackDeclarationAsBeeBee() {
		JsonObject snapshot = BeeDesignerMyriad.snapshot();
		JsonObject values = snapshot.getAsJsonObject("values");
		values.addProperty("model", "productivebees:geo/entity/beebee.geo.json");
		values.addProperty("beeTexture", "productivebees:textures/entity/bee/beebee/bee");
		values.addProperty("attack", 2.147483647E9);
		values.addProperty("selfheal", true);
		values.addProperty("postPollination", "bonemeal");
		var validated = BeeDesignerMyriad.validate(snapshot);
		assertEquals(2.147483647E9, validated.get("attack"));
		assertEquals(true, validated.get("selfheal"));
		assertEquals("productivebees:geo/entity/beebee.geo.json", validated.get("model"));
		assertEquals("bonemeal", validated.get("postPollination"));
		values.addProperty("attack", 2.147483648E9);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMyriad.validate(snapshot));
	}
	@Test
	void kubeJsBundlePreservesDefinitionsNamesComponentsAndStableRecipes(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("test:kubejs");
		original.addProperty("definition_path", "test:recipe/kubejs");
		String recipeId = BeeDesignerDocument.addRecipe(original, "minecraft:crafting_shapeless");
		JsonObject recipe = original.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		recipe.addProperty("__proto__", "kept as data");
		recipe.addProperty("description", "line\u2028separator");
		String before = original.toString();
		Map<String, String> files = BeeDesignerArchive.kubeJsFiles(original);
		assertTrue(files.containsKey("kubejs/data/test/productivebees/recipe/kubejs.json"));
		assertTrue(files.containsKey("kubejs/assets/productivebees/lang/zh_cn.json"));
		String script = files.get("kubejs/server_scripts/bee_designer/test/kubejs.js");
		assertTrue(script.contains("event.custom(JSON.parse("));
		assertTrue(script.contains(recipeId));
		assertFalse(script.contains("\u2028"));
		assertFalse(files.keySet().stream().anyMatch(path -> path.startsWith("kubejs/data/test/recipe/")));
		assertEquals(before, original.toString());
		Path archive = BeeDesignerArchive.exportKubeJs(directory, original);
		assertEquals(original, JsonParser.parseString(BeeDesignerArchive.read(archive).get("test:kubejs")));
	}
	@Test
	void exportsAdditionalLocalesAndRejectsUnsafeLanguagePaths() {
		JsonObject document = BeeDesignerDocument.create("test:localized");
		document.addProperty("definition_path", "test:localized");
		document.getAsJsonObject("names").addProperty("ja_jp", "ミツバチ");
		document.getAsJsonObject("names").addProperty("de_de", "Biene");
		document.getAsJsonObject("names").addProperty("tok", "pipi");
		var files = BeeDesignerCompiler.files(Map.of("test:localized", document.toString()));
		assertEquals("ミツバチ", JsonParser.parseString(files.get("assets/productivebees/lang/ja_jp.json"))
				.getAsJsonObject().get("entity.productivebees.localized_bee").getAsString());
		assertTrue(files.containsKey("assets/productivebees/lang/de_de.json"));
		assertTrue(files.containsKey("assets/productivebees/lang/tok.json"));
		document.getAsJsonObject("names").addProperty("../bad", "unsafe");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of("test:localized", document.toString())));
		document.getAsJsonObject("names").remove("../bad");
		document.getAsJsonObject("names").addProperty("ja_jp", "%s");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of("test:localized", document.toString())));
	}

	@Test
	void colorInputSupportsRgbHsvAndRejectsInvalidChannels() {
		assertEquals(0x3366FF, DesignerColorValue.parse("#36f"));
		assertEquals(0x3366FF, DesignerColorValue.parse("0x3366FF"));
		assertEquals(0x3366FF, DesignerColorValue.parse("rgb(51, 102, 255)"));
		assertEquals(0xFF0000, DesignerColorValue.parse("hsv(360, 100%, 100%)"));
		assertEquals(0x00FF00, DesignerColorValue.parse("hsv(120,100,100)"));
		assertEquals(0x000000, DesignerColorValue.parse("hsv(240,100%,0%)"));
		for (int color : new int[]{0x000000, 0xFFFFFF, 0x3366FF, 0xE8BC39}) {
			assertEquals(color, DesignerColorValue.parse(DesignerColorValue.format(color, "RGB")));
			assertEquals(color, DesignerColorValue.parse(DesignerColorValue.format(color, "HEX")));
			assertEquals(color, DesignerColorValue.parse(DesignerColorValue.format(color, "HSV")));
		}
		for (String invalid : List.of("#12345", "#12345678", "rgb(256,0,0)", "rgb(1.5,2,3)", "hsv(0,101%,1%)",
				"hsv(NaN,0,0)", "hsv(Infinity,0,0)", "rgb(1,2,3,4)", "rgb(-1,0,0)")) {
			assertThrows(IllegalArgumentException.class, () -> DesignerColorValue.parse(invalid), invalid);
		}
	}

	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	private static Map<ResourceLocation, net.minecraft.nbt.CompoundTag> previousBees;

	@BeforeAll
	static void loadConfigs() {
		ConfigTracker.INSTANCE.loadDefaultServerConfigs();
		previousBees = new HashMap<>(BeeReloadListener.INSTANCE.getData());
		var bees = new HashMap<>(previousBees);
		var id = ResourceLocation.parse("test:mixed");
		bees.put(id, BeeCreator.create(id, BeeDesignerDocument.create(id.toString()).getAsJsonObject("definition")));
		BeeReloadListener.INSTANCE.setData(bees);
	}

	@AfterAll
	static void unloadConfigs() {
		ConfigTracker.INSTANCE.unloadConfigs(net.neoforged.fml.config.ModConfig.Type.SERVER);
		BeeReloadListener.INSTANCE.setData(previousBees);
	}

	@Test
	void generatedFilesPreserveDefinitionPathUnknownFieldsAndExistingRecipeIds() {
		JsonObject document = BeeDesignerDocument.create("test:designer");
		document.addProperty("definition_path", "test:metals/designer");
		document.getAsJsonObject("definition").addProperty("third_party_field", "preserved");
		String recipe = BeeDesignerDocument.addRecipe(document, "productivebeesgenesis:resource_production");
		document.getAsJsonObject("recipes").add("test:original_recipe", document.getAsJsonObject("recipes").remove(recipe));
		var files = BeeDesignerCompiler.files(Map.of("test:designer", document.toString()));
		assertTrue(files.containsKey("data/test/productivebees/metals/designer.json"));
		assertFalse(files.containsKey("data/test/productivebees/designer.json"));
		assertTrue(files.get("data/test/productivebees/metals/designer.json").contains("third_party_field"));
		assertTrue(files.containsKey("data/test/recipe/original_recipe.json"));
		assertFalse(files.keySet().stream().anyMatch(path -> path.contains("bee_designer/designer/")));
	}

	@Test
	void removedRecipesAreDisabledBySameIdWithoutRemovingBeeIdentity() {
		JsonObject document = BeeDesignerDocument.create("test:designer");
		document.addProperty("definition_path", "test:designer");
		document.add("disabled_recipes", JsonParser.parseString("[\"test:old_output\"]"));
		var files = BeeDesignerCompiler.files(Map.of("test:designer", document.toString()));
		assertTrue(files.containsKey("data/test/productivebees/designer.json"));
		assertEquals("neoforge:false", JsonParser.parseString(files.get("data/test/recipe/old_output.json"))
				.getAsJsonObject().getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject().get("type").getAsString());
	}

	@Test
	void materialSelectionRetainsFullComponentsAndStrictlyRejectsDifferentContents() {
		ItemStack chosen = new ItemStack(Items.HONEY_BOTTLE, 12);
		chosen.set(DataComponents.CUSTOM_NAME, Component.literal("full component identity"));
		Ingredient material = DataComponentIngredient.of(true, chosen.copyWithCount(1));
		var json = Ingredient.CODEC.encodeStart(REGISTRIES.createSerializationContext(JsonOps.INSTANCE), material).getOrThrow();
		Ingredient restored = Ingredient.CODEC.parse(REGISTRIES.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
		assertTrue(restored.test(chosen));
		assertFalse(restored.test(new ItemStack(Items.HONEY_BOTTLE)));
		ItemStack other = chosen.copy();
		other.set(DataComponents.CUSTOM_NAME, Component.literal("different contents"));
		assertFalse(restored.test(other));
		assertEquals(12, chosen.getCount());
	}

	@Test
	void generatedCombCarriesActualBeeIdentityThroughIngredientCodec() {
		var json = BeeDesignerDocument.comb("test:designer");
		Ingredient comb = Ingredient.CODEC.parse(REGISTRIES.createSerializationContext(JsonOps.INSTANCE), json).getOrThrow();
		ItemStack output = comb.getItems()[0];
		assertEquals("test:designer", output.get(com.ayoshiko.productivebeesgenesis.util.PbDataComponents.beeType()).toString());
		ItemStack wrongBee = output.copy();
		wrongBee.set(com.ayoshiko.productivebeesgenesis.util.PbDataComponents.beeType(), net.minecraft.resources.ResourceLocation.parse("test:other"));
		assertFalse(comb.test(wrongBee));
	}

	@Test
	void networkBudgetUsesUtf8BytesRatherThanCharacterCount() {
		String withinBudget = "a".repeat(BeeDesignerProtocol.MAX_BYTES);
		assertDoesNotThrow(() -> BeeDesignerProtocol.checkSize(withinBudget));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerProtocol.checkSize(withinBudget + "a"));
		String multiByte = "\u4e2d".repeat(10000);
		assertEquals(30000, multiByte.getBytes(StandardCharsets.UTF_8).length);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerProtocol.checkSize(multiByte));
	}

	@Test
	void pathsCannotEscapeManagedPackAndDuplicateOutputOwnershipIsRejected() {
		JsonObject document = BeeDesignerDocument.create("test:designer");
		document.addProperty("definition_path", "test:../designer");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of("test:designer", document.toString())));
		JsonObject first = BeeDesignerDocument.create("test:first");
		first.addProperty("definition_path", "test:first");
		String recipe = BeeDesignerDocument.addRecipe(first, "productivebeesgenesis:resource_production");
		JsonObject second = BeeDesignerDocument.create("test:second");
		second.addProperty("definition_path", "test:second");
		second.getAsJsonObject("recipes").add(recipe, first.getAsJsonObject("recipes").get(recipe).deepCopy());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of("test:first", first.toString(), "test:second", second.toString())));
	}

	@Test
	void mixedProductionCompilesRealItemsFluidAndMultipleResourcesWithoutTouchingInventory() {
		JsonObject document = BeeDesignerDocument.create("test:mixed");
		ItemStack original = new ItemStack(Items.HONEY_BOTTLE, 9);
		original.set(DataComponents.CUSTOM_NAME, Component.literal("chosen contents"));
		JsonArray items = new JsonArray();
		items.add(BeeDesignerSelections.itemOutput(BeeDesignerSelections.item(original, REGISTRIES), REGISTRIES, 2, 5, 0.75));
		items.add(BeeDesignerSelections.itemOutput(BeeDesignerSelections.item(new ItemStack(Items.COPPER_INGOT), REGISTRIES), REGISTRIES, 1, 1, 1));
		JsonObject fluid = BeeDesignerSelections.fluidOutput(BeeDesignerSelections.fluid(new FluidStack(Fluids.WATER, 1000), REGISTRIES),
				REGISTRIES, 250).getAsJsonObject();
		JsonArray resources = JsonParser.parseString("[{\"kind\":\"energy\",\"energy_type\":\"FE\",\"amount\":\"1200\"},"
				+ "{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\",\"amount\":\"400\"}]").getAsJsonArray();
		BeeDesignerProduction.replace(document, "main", true, items, fluid, resources, 60);
		var recipes = document.getAsJsonObject("recipes");
		assertEquals(3, recipes.size());
		var ops = REGISTRIES.createSerializationContext(JsonOps.INSTANCE);
		var hive = (AdvancedBeehiveRecipe) Recipe.CODEC.parse(ops, recipes.get("test:bee_designer/mixed/production/main/comb")).getOrThrow();
		assertEquals(ResourceLocation.parse("test:mixed"), hive.getRecipeOutputs().keySet().iterator().next().get(PbDataComponents.beeType()));
		var ordinary = (CentrifugeRecipe) Recipe.CODEC.parse(ops, recipes.get("test:bee_designer/mixed/production/main/items")).getOrThrow();
		assertEquals(250, ordinary.getFluidOutputs().getAmount());
		assertEquals(Fluids.WATER, ordinary.getFluidOutputs().getFluid());
		assertTrue(ordinary.getRecipeOutputs().keySet().stream().anyMatch(stack -> ItemStack.isSameItemSameComponents(stack, original)));
		assertEquals(2, ordinary.getRecipeOutputs().size());
		var expanded = (ResourceCentrifugeRecipe) Recipe.CODEC.parse(ops, recipes.get("test:bee_designer/mixed/production/main/resources")).getOrThrow();
		assertEquals(2, expanded.outputs().size());
		assertTrue(expanded.includePbOutputs());
		assertEquals(1200, expanded.outputs().getFirst().amount());
		assertEquals(400, expanded.outputs().get(1).amount());
		for (var recipe : recipes.asMap().values()) {
			assertDoesNotThrow(() -> BeeDesignerCompiler.validateAmounts(recipe.getAsJsonObject(), REGISTRIES));
		}
		assertEquals(9, original.getCount());
		assertEquals("chosen contents", original.getHoverName().getString());
	}

	@Test
	void productionEditsKeepStableIdsAndRetireRemovedRoutes() {
		JsonObject document = BeeDesignerDocument.create("test:stable");
		JsonArray items = new JsonArray();
		items.add(BeeDesignerDocument.itemOutput(JsonParser.parseString("{\"item\":\"minecraft:copper_ingot\"}").getAsJsonObject()));
		JsonArray resources = JsonParser.parseString("[{\"kind\":\"energy\",\"energy_type\":\"FE\",\"amount\":\"10\"}]").getAsJsonArray();
		BeeDesignerProduction.replace(document, "main", true, items, null, resources, 20);
		var initial = Set.copyOf(document.getAsJsonObject("recipes").keySet());
		BeeDesignerProduction.replace(document, "main", true, items, null, resources, 40);
		assertEquals(initial, document.getAsJsonObject("recipes").keySet());
		BeeDesignerProduction.replace(document, "main", false, new JsonArray(), null, resources, 40);
		assertEquals(1, document.getAsJsonObject("recipes").size());
		assertEquals(2, document.getAsJsonArray("disabled_recipes").size());
		BeeDesignerProduction.remove(document, "main");
		assertTrue(document.getAsJsonObject("recipes").isEmpty());
		assertEquals(3, document.getAsJsonArray("disabled_recipes").size());
		assertTrue(document.getAsJsonObject("production_nodes").isEmpty());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerProduction.replace(document, "invalid", false, items,
				new JsonObject(), resources, 20));
	}

	@Test
	void selectionsRejectLossyComponentsAndDoNotMutateFlowerUntilConversionSucceeds() {
		ItemStack chosen = new ItemStack(Items.HONEY_BOTTLE, 7);
		chosen.set(DataComponents.CUSTOM_NAME, Component.literal("specific contents"));
		JsonObject selection = BeeDesignerSelections.item(chosen, REGISTRIES);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerSelections.ingredient(selection, REGISTRIES, false));
		Ingredient exact = Ingredient.CODEC.parse(REGISTRIES.createSerializationContext(JsonOps.INSTANCE),
				BeeDesignerSelections.ingredient(selection, REGISTRIES, true)).getOrThrow();
		assertTrue(exact.test(chosen));
		assertFalse(exact.test(new ItemStack(Items.HONEY_BOTTLE)));
		JsonObject definition = BeeDesignerDocument.create("test:selection").getAsJsonObject("definition");
		JsonObject before = definition.deepCopy();
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerSelections.flower(definition, selection, false, REGISTRIES));
		assertEquals(before, definition);
		FluidStack fluid = new FluidStack(Fluids.WATER, 1000);
		fluid.set(DataComponents.CUSTOM_NAME, Component.literal("specific fluid"));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerSelections.fluidOutput(BeeDesignerSelections.fluid(fluid, REGISTRIES), REGISTRIES, 100));
		assertEquals(7, chosen.getCount());
		assertEquals(1000, fluid.getAmount());
	}

	@Test
	void malformedSchemaNamesOutputRangesAndDeepPayloadsAreRejected() {
		JsonObject document = BeeDesignerDocument.create("test:shape");
		document.addProperty("definition_path", "test:shape");
		document.addProperty("schema_version", "1");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateStructure(document));
		document.addProperty("schema_version", 1);
		JsonObject duplicate = BeeDesignerDocument.create("other:shape");
		duplicate.addProperty("definition_path", "other:shape");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of("test:shape", document.toString(), "other:shape", duplicate.toString())));
		JsonObject recipe = JsonParser.parseString("{\"type\":\"productivebees:advanced_beehive\",\"results\":[{\"item\":{\"item\":\"minecraft:copper_ingot\"},\"min\":5,\"max\":2,\"chance\":1}]}").getAsJsonObject();
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateAmounts(recipe, REGISTRIES));
		recipe.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("max", 5);
		recipe.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("chance", 1.5);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateAmounts(recipe, REGISTRIES));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerProtocol.parse("{\"nested\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"));
		assertDoesNotThrow(() -> BeeDesignerProtocol.parse("{\"text\":\"[[[\\\"quoted\\\"\"}"));
		recipe.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("chance", 1);
		recipe.getAsJsonArray("results").get(0).getAsJsonObject().addProperty("min", "2");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateAmounts(recipe, REGISTRIES));
		JsonObject breeding = JsonParser.parseString("{\"type\":\"productivebees:bee_breeding\",\"parentDeathChance\":1.1}").getAsJsonObject();
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateAmounts(breeding, REGISTRIES));
	}

	@Test
	void revisionLifecycleKeepsOldPackUntilActivationAndQuarantinesFailure(@TempDir Path world) throws IOException {
		Map<String, String> files = Map.of("pack.mcmeta", "{}", "data/test/recipe/example.json", "{}");
		var first = BeeDesignerStore.write(world, files, false);
		assertFalse(Files.exists(first.installed()));
		BeeDesignerStore.install(first);
		assertEquals("pending", BeeDesignerStore.inspect(world).get("phase").getAsString());
		BeeDesignerStore.complete(first, List.of());
		var rejected = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(rejected);
		BeeDesignerStore.reject(rejected, false, "revoked before reload");
		assertTrue(Files.exists(first.installed()));
		assertFalse(Files.exists(rejected.installed()));
		assertTrue(Files.exists(world.resolve("productivebeesgenesis/bee_designer/quarantine").resolve(rejected.source().getFileName())));
		assertEquals("rejected", BeeDesignerStore.inspect(world).get("phase").getAsString());
		var second = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(second);
		BeeDesignerStore.complete(second, List.of(first.packId(), "file/other.zip"));
		assertFalse(Files.exists(first.installed()));
		assertTrue(Files.exists(first.source()));
		assertTrue(Files.exists(second.installed()));
		assertEquals("committed", BeeDesignerStore.inspect(world).get("phase").getAsString());
	}

	@Test
	void storeRejectsCorruptRevisionsUnsafePathsAndPreservesUnknownJournal(@TempDir Path world) throws IOException {
		var files = Map.of("pack.mcmeta", "{}");
		var corrupt = BeeDesignerStore.write(world, files, false);
		Files.writeString(corrupt.source(), "corrupt", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> BeeDesignerStore.install(corrupt));
		assertFalse(Files.exists(corrupt.installed()));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerStore.write(world,
				Map.of("pack.mcmeta", "{}", "data/test/../escape.json", "{}"), false));
		var pending = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(pending);
		assertEquals("pending", BeeDesignerStore.inspect(world).get("phase").getAsString());
		BeeDesignerStore.reject(pending, true, "reload result unknown");
		assertEquals("uncertain", BeeDesignerStore.inspect(world).get("phase").getAsString());
		assertTrue(Files.exists(pending.source()));
		var exported = BeeDesignerStore.write(world, files, true);
		assertThrows(IOException.class, () -> BeeDesignerStore.install(exported));
		try (ZipFile zip = new ZipFile(exported.source().resolveSibling(exported.source().getFileName().toString().replace(".zip", "-names.zip")).toFile())) {
			assertNotNull(zip.getEntry("pack.mcmeta"));
			assertNull(zip.getEntry("data/test/recipe/example.json"));
		}
		assertEquals("uncertain", BeeDesignerStore.inspect(world).get("phase").getAsString());
	}

	@Test
	void copyingBeeRemapsOwnedIdentityAndStableNodesButPreservesUnknownFields() {
		JsonObject original = BeeDesignerDocument.create("test:original");
		original.getAsJsonObject("definition").addProperty("third_party", "test:original");
		JsonArray items = new JsonArray();
		items.add(BeeDesignerDocument.itemOutput(BeeDesignerDocument.comb("test:original")));
		BeeDesignerProduction.replace(original, "main", true, items, null, new JsonArray(), 80);
		String breeding = BeeDesignerDocument.addRecipe(original, "productivebees:bee_breeding");
		original.getAsJsonObject("recipes").getAsJsonObject(breeding).addProperty("comment", "test:original");
		BeeDesignerDocument.addRecipe(original, "minecraft:crafting_shaped");
		String before = original.toString();
		JsonObject copy = BeeDesignerDocument.copyAs(original, "other:copied");
		assertEquals(before, original.toString());
		assertEquals("new", copy.get("baseline").getAsString());
		assertEquals("test:original", copy.getAsJsonObject("definition").get("third_party").getAsString());
		assertTrue(copy.getAsJsonObject("recipes").keySet().stream().allMatch(id -> id.startsWith("other:bee_designer/copied/")));
		for (var value : copy.getAsJsonObject("recipes").asMap().values()) {
			JsonObject recipe = value.getAsJsonObject();
			assertTrue(BeeDesignerReader.belongs(recipe, "other:copied"));
			if (recipe.has("comment")) {
				assertEquals("test:original", recipe.get("comment").getAsString());
			}
		}
		BeeDesignerProduction.replace(copy, "main", false, items, null, new JsonArray(), 80);
		assertEquals(1, copy.getAsJsonObject("production_nodes").getAsJsonObject("main").getAsJsonArray("recipe_ids").size());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerDocument.copyAs(original, "test:original"));
	}

	@Test
	void exportedProjectsRoundTripAndZipImportRejectsTraversalAndExpandedOverflow(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("test:archive");
		original.getAsJsonObject("definition").addProperty("unknown_property", 42);
		BeeDesignerDocument.addRecipe(original, "productivebeesgenesis:resource_production");
		Path exported = BeeDesignerArchive.export(directory, original);
		assertEquals(original, JsonParser.parseString(BeeDesignerArchive.read(exported).get("test:archive")));
		Path unsafe = directory.resolve("unsafe.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(unsafe))) {
			zip.putNextEntry(new ZipEntry("../outside.json"));
			zip.write("{}".getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		assertThrows(IOException.class, () -> BeeDesignerArchive.read(unsafe));
		assertFalse(Files.exists(directory.getParent().resolve("outside.json")));
		Path oversized = directory.resolve("oversized.zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(oversized))) {
			zip.putNextEntry(new ZipEntry("data/test/oversized.json"));
			zip.write(new byte[BeeDesignerStore.MAX_PACK_BYTES + 1]);
			zip.closeEntry();
		}
		assertThrows(IOException.class, () -> BeeDesignerArchive.read(oversized));
	}

	@Test
	void diagnosticsIdentifyInvalidRecipeAndFingerprintIgnoresObjectOrder() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(REGISTRIES.freeze());
		JsonObject document = BeeDesignerDocument.create("test:mixed");
		String recipe = BeeDesignerDocument.addRecipe(document, "productivebees:advanced_beehive");
		document.getAsJsonObject("recipes").getAsJsonObject(recipe).getAsJsonArray("results").get(0).getAsJsonObject().addProperty("min", -1);
		JsonObject result = BeeDesignerDiagnostics.check(document, server);
		assertFalse(result.get("valid").getAsBoolean());
		assertTrue(result.getAsJsonArray("issues").asList().stream().anyMatch(value ->
				value.getAsJsonObject().get("path").getAsString().equals("recipes/" + recipe)));
		JsonObject reordered = new JsonObject();
		document.entrySet().stream().sorted(Map.Entry.<String, com.google.gson.JsonElement>comparingByKey().reversed())
				.forEach(entry -> reordered.add(entry.getKey(), entry.getValue()));
		assertEquals(BeeDesignerDocument.fingerprint(document), BeeDesignerDocument.fingerprint(reordered));
	}

	@Test
	void invalidDefinitionIsReportedOnceAndCorrectedAttackLimitCanPass() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(REGISTRIES.freeze());
		JsonObject document = BeeDesignerDocument.create("test:diagnostic");
		BeeDesignerDocument.addRecipe(document, "minecraft:crafting_shapeless");
		BeeDesignerDocument.addRecipe(document, "productivebees:advanced_beehive");
		document.getAsJsonObject("definition").addProperty("size", 0);
		JsonObject invalid = BeeDesignerDiagnostics.check(document, server);
		var errors = invalid.getAsJsonArray("issues").asList().stream().map(value -> value.getAsJsonObject())
				.filter(issue -> issue.get("severity").getAsString().equals("error")).toList();
		assertEquals(1, errors.size());
		assertEquals("definition/size", errors.getFirst().get("path").getAsString());
		document.getAsJsonObject("definition").addProperty("size", 1);
		document.getAsJsonObject("definition").addProperty("attack", Integer.MAX_VALUE);
		assertTrue(BeeDesignerDiagnostics.check(document, server).get("valid").getAsBoolean());
		document.getAsJsonObject("definition").addProperty("attack", (long) Integer.MAX_VALUE + 1);
		assertFalse(BeeDesignerDiagnostics.check(document, server).get("valid").getAsBoolean());
	}

	@Test
	void recipeDiagnosticsPreserveSlashIdentityAndPointAtInvalidProbability() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(REGISTRIES.freeze());
		JsonObject document = BeeDesignerDocument.create("test:probability");
		String id = BeeDesignerDocument.addRecipe(document, "productivebees:bee_fishing");
		document.getAsJsonObject("recipes").getAsJsonObject(id).addProperty("chance", 2);
		JsonObject result = BeeDesignerDiagnostics.check(document, server);
		assertTrue(result.getAsJsonArray("issues").asList().stream().anyMatch(value ->
				value.getAsJsonObject().get("path").getAsString().equals("recipes/" + id + "/chance")));
	}

	@Test
	void modelDefinitionAndPendingDraftStateSurviveSaveAndExport(@TempDir Path directory) throws IOException {
		JsonObject document = BeeDesignerDocument.create("test:model_draft");
		document.getAsJsonObject("definition").addProperty("model", "productivebees:geo/entity/beebee.geo.json");
		document.getAsJsonObject("definition").addProperty("renderer", "default");
		BeeDesignerDrafts.save(directory, document);
		var entry = BeeDesignerDrafts.list(directory).getFirst();
		assertTrue(entry.pending());
		assertTrue(entry.modified() > 0);
		assertEquals(document, BeeDesignerDrafts.read(directory, entry.id()));
		var files = BeeDesignerCompiler.files(Map.of(entry.id(), document.toString()));
		assertTrue(files.values().stream().anyMatch(value -> value.contains("productivebees:geo/entity/beebee.geo.json")));
		document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
		BeeDesignerDrafts.save(directory, document);
		assertFalse(BeeDesignerDrafts.list(directory).getFirst().pending());
		document.getAsJsonObject("definition").addProperty("attack", 30);
		BeeDesignerDrafts.save(directory, document);
		assertTrue(BeeDesignerDrafts.list(directory).getFirst().pending());
		JsonArray recovery = new JsonArray();
		JsonObject pending = new JsonObject();
		pending.addProperty("kind", "json");
		pending.addProperty("text", "{unfinished");
		recovery.add(pending);
		document.add("_editor_recovery", recovery);
		assertTrue(BeeDesignerArchive.kubeJsFiles(document).values().stream().noneMatch(value -> value.contains("{unfinished")));
	}

	@Test
	void recoveryAuditsChecksumsAndRetainsHistoryWhileRestoringConfirmedRevision(@TempDir Path world) throws IOException {
		JsonObject document = BeeDesignerDocument.create("test:recovery");
		var files = BeeDesignerCompiler.files(Map.of("test:recovery", document.toString()));
		var first = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(first);
		BeeDesignerStore.complete(first, List.of());
		var pending = BeeDesignerStore.write(world, files, false);
		BeeDesignerStore.install(pending);
		BeeDesignerStore.reject(pending, true, "verification interrupted after loading");
		var audit = BeeDesignerStore.audit(world, List.of(pending.packId()));
		assertEquals(document, JsonParser.parseString(audit.documents().get("test:recovery")));
		assertFalse(Files.exists(pending.installed()));
		BeeDesignerStore.reconcile(world, audit);
		assertTrue(Files.exists(pending.installed()));
		assertFalse(Files.exists(first.installed()));
		assertTrue(Files.exists(first.source()));
		assertEquals("committed", BeeDesignerStore.inspect(world).get("phase").getAsString());
		assertEquals(document.toString(), BeeDesignerStore.readRevision(world, first.source().getFileName().toString()).get("test:recovery"));
		assertThrows(IOException.class, () -> BeeDesignerStore.readRevision(world, "../outside.zip"));
		var unchanged = BeeDesignerStore.audit(world, List.of(pending.packId()));
		Files.writeString(pending.installed(), "different bytes", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> BeeDesignerStore.reconcile(world, unchanged));
		assertEquals("committed", BeeDesignerStore.inspect(world).get("phase").getAsString());
	}

	@Test
	void acquisitionOnlyProjectGetsProductionDiagnostic() {
		MinecraftServer server = mock(MinecraftServer.class);
		when(server.registryAccess()).thenReturn(REGISTRIES.freeze());
		JsonObject document = BeeDesignerDocument.create("test:mixed");
		BeeDesignerDocument.addRecipe(document, "minecraft:crafting_shaped");
		JsonObject result = BeeDesignerDiagnostics.check(document, server);
		assertTrue(result.getAsJsonArray("issues").asList().stream().anyMatch(value ->
				value.getAsJsonObject().get("message").getAsString().equals("designer.diagnostic.no_production")));
	}

	@Test
	void localDraftsKeepEachBeeAndFullComponentsWithoutReplacingCorruptData(@TempDir Path directory) throws IOException {
		JsonObject first = BeeDesignerDocument.create("test:first_draft");
		first.getAsJsonObject("definition").addProperty("third_party_field", "完整保留");
		String recipeId = BeeDesignerDocument.addRecipe(first, "minecraft:crafting_shapeless");
		JsonObject recipe = first.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		JsonObject result = recipe.getAsJsonObject("result");
		result.addProperty("count", 3);
		result.getAsJsonObject("components").addProperty("minecraft:custom_name", "\"Draft egg\"");
		BeeDesignerDrafts.save(directory, first);
		JsonObject second = BeeDesignerDocument.create("other:second_draft");
		BeeDesignerDrafts.save(directory, second);
		assertEquals(2, BeeDesignerDrafts.list(directory).size());
		assertEquals(first, BeeDesignerDrafts.read(directory, "test:first_draft"));
		assertEquals(second, BeeDesignerDrafts.read(directory, "other:second_draft"));
		Path firstFile = directory.resolve("drafts/test/first_draft.json");
		Files.writeString(firstFile, "损坏但必须保留的原文", StandardCharsets.UTF_8);
		IllegalStateException failure = assertThrows(IllegalStateException.class, () -> BeeDesignerDrafts.save(directory, first));
		assertTrue(failure.getMessage().startsWith("Not a JSON Object:"));
		assertEquals("损坏但必须保留的原文", Files.readString(firstFile, StandardCharsets.UTF_8));
		assertFalse(BeeDesignerDrafts.list(directory).stream().filter(entry -> entry.id().equals("test:first_draft")).findFirst().orElseThrow().problem().isBlank());
		Path archived = BeeDesignerDrafts.archive(directory, "test:first_draft");
		assertEquals("损坏但必须保留的原文", Files.readString(archived, StandardCharsets.UTF_8));
		assertFalse(Files.exists(firstFile));
		assertEquals(1, BeeDesignerDrafts.list(directory).size());
		assertThrows(IOException.class, () -> BeeDesignerDrafts.read(directory, "test:../outside"));
		assertEquals(second, BeeDesignerDrafts.read(directory, "other:second_draft"));
	}

	@Test
	void eggIdentityRoundTripsConfigurableAndNativeBeesAndRejectsMisleadingComponents() {
		JsonObject configured = BeeDesignerEggs.output("test:mixed");
		configured.addProperty("count", 4);
		configured.getAsJsonObject("components").addProperty("minecraft:custom_name", "\"Recipe egg\"");
		ItemStack stack = BeeDesignerEggs.validate(configured, REGISTRIES);
		assertEquals(ResourceLocation.parse("test:mixed"), BeeDesignerEggs.bee(stack));
		assertEquals(4, stack.getCount());
		assertEquals("Recipe egg", stack.getHoverName().getString());
		var nativeIds = BeeDesignerEggs.bees().stream().filter(id -> !id.equals("minecraft:bee"))
				.filter(id -> BuiltInRegistries.ENTITY_TYPE.containsKey(ResourceLocation.parse(id))).toList();
		assertFalse(nativeIds.isEmpty());
		for (String nativeId : nativeIds) {
			JsonObject nativeEgg = BeeDesignerEggs.output(nativeId);
			assertEquals(ResourceLocation.parse(nativeId), BeeDesignerEggs.bee(nativeEgg));
			assertEquals(ResourceLocation.parse(nativeId), BeeDesignerEggs.bee(BeeDesignerEggs.validate(nativeEgg, REGISTRIES)));
		}
		String nativeId = nativeIds.getFirst();
		JsonObject retargeted = BeeDesignerEggs.retarget(configured, nativeId);
		assertEquals(4, retargeted.get("count").getAsInt());
		assertEquals("Recipe egg", BeeDesignerEggs.validate(retargeted, REGISTRIES).getHoverName().getString());
		JsonObject invalid = configured.deepCopy();
		JsonObject entity = invalid.getAsJsonObject("components").getAsJsonObject("minecraft:entity_data");
		entity.add("types", entity.remove("type"));
		assertNull(BeeDesignerEggs.bee(invalid));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerEggs.validate(invalid, REGISTRIES));
		JsonObject wrongEntity = retargeted.deepCopy();
		wrongEntity.getAsJsonObject("components").getAsJsonObject("minecraft:entity_data").addProperty("id", "minecraft:pig");
		assertNull(BeeDesignerEggs.bee(wrongEntity));
		JsonObject fractional = configured.deepCopy();
		fractional.addProperty("count", 1.5);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerEggs.validate(fractional, REGISTRIES));
		JsonObject legacy = configured.deepCopy();
		legacy.getAsJsonObject("components").addProperty("minecraft:entity_data", "{id:\"productivebees:configurable_bee\",type:\"test:mixed\",Age:-24000}");
		assertEquals(ResourceLocation.parse("test:mixed"), BeeDesignerEggs.bee(BeeDesignerEggs.validate(legacy, REGISTRIES)));
		assertTrue(BeeDesignerEggs.normalize(legacy).getAsJsonObject("components").getAsJsonObject("minecraft:entity_data").has("Age"));
	}
}
