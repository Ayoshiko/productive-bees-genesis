package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes.Host;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class BeeDesignerMachineMinecraftTest {
	private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	private static final String EGG = "productivebees:spawn_egg_configurable_bee[entity_data={id:\"productivebees:configurable_bee\",type:\"test:machine\"}]";

	@Test
	void changesMachineWithoutLosingMaterialsAndRejectsUnsafeConversion() {
		JsonObject original = BeeDesignerMachineRecipes.create(Host.MI, "modern_industrialization:assembler", "", "test:machine");
		original.addProperty("duration", 240);
		original.addProperty("eu", 100000);
		var input = BeeDesignerMachineRecipes.entries(original).getFirst();
		input.value().addProperty("amount", 12);
		input.node().addProperty("probability", 0.25);
		String before = original.toString();
		JsonObject changed = BeeDesignerMachineRecipes.migrate(original, Host.MI, "modern_industrialization:centrifuge", "", "test:machine", REGISTRIES);
		JsonObject expected = original.deepCopy();
		expected.addProperty("type", "modern_industrialization:centrifuge");
		assertEquals(expected, changed);
		assertEquals(before, original.toString());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.migrate(original, Host.MBD2, "mbd2:bee_craft", "", "test:machine", REGISTRIES));
		assertEquals(before, original.toString());
		JsonObject mbd = BeeDesignerMachineRecipes.create(Host.MBD2, "mbd2:bee_craft", "", "test:machine");
		JsonObject noEnergy = original.deepCopy();
		noEnergy.addProperty("eu", 0);
		JsonObject mmr = BeeDesignerMachineRecipes.migrate(noEnergy, Host.MMR, "modular_machinery_reborn:machine_recipe", "bbb:tbi", "test:machine", REGISTRIES);
		assertEquals(240, mmr.get("time").getAsInt());
		assertEquals(12, BeeDesignerMachineRecipes.entries(mmr).getFirst().value().get("count").getAsInt());
		assertEquals(0.25, BeeDesignerMachineRecipes.entries(mmr).getFirst().node().get("chance").getAsDouble());
		assertEquals(0, noEnergy.get("eu").getAsInt());
		assertTrue(BeeDesignerMachineRecipes.belongs(mmr, "test:machine"));
		var mbdInput = BeeDesignerMachineRecipes.entries(mbd).getFirst();
		mbdInput.value().addProperty("count", 9);
		mbdInput.node().addProperty("chance", 0.4);
		JsonObject migrated = BeeDesignerMachineRecipes.migrate(mbd, Host.MI, "modern_industrialization:assembler", "", "test:machine", REGISTRIES);
		assertEquals(9, BeeDesignerMachineRecipes.entries(migrated).getFirst().value().get("amount").getAsInt());
		assertEquals(0.4, BeeDesignerMachineRecipes.entries(migrated).getFirst().node().get("probability").getAsDouble());
		assertTrue(BeeDesignerMachineRecipes.belongs(migrated, "test:machine"));
	}

	@Test
	void nativeMaterialsPreserveComponentsAmountsAndInventoryAcrossHosts() {
		ItemStack inventory = new ItemStack(Items.HONEY_BOTTLE, 7);
		inventory.set(DataComponents.CUSTOM_NAME, Component.literal("specific contents"));
		for (Host host : Host.values()) {
			String type = host == Host.MMR ? host.mod + ":machine_recipe" : host.mod + ":bee_craft";
			JsonObject recipe = BeeDesignerMachineRecipes.create(host, type, "bbb:tbi", "test:machine");
			JsonObject material = BeeDesignerMachineRecipes.material(BeeDesignerSelections.item(inventory, REGISTRIES), REGISTRIES);
			BeeDesignerMachineRecipes.append(recipe, "item", true, material);
			var entry = BeeDesignerMachineRecipes.entries(recipe).stream().filter(value -> value.input() && value.kind().equals("item")).toList().getLast();
			entry.value().addProperty(entry.amountKey(), 12);
			assertTrue(ItemStack.isSameItemSameComponents(inventory, BeeDesignerMachineRecipes.decodeItem(entry, REGISTRIES)), host.name());
			assertEquals(12, entry.value().get(entry.amountKey()).getAsInt());
			assertEquals(7, inventory.getCount());
			var output = BeeDesignerMachineRecipes.entries(recipe).stream().filter(value -> !value.input() && value.kind().equals("item")).findFirst().orElseThrow();
			ItemStack preview = BeeDesignerMachineRecipes.decodeItem(output, REGISTRIES);
			assertTrue(ItemStack.isSameItemSameComponents(BeeDesignerEggs.egg("test:machine"), preview), host.name());
			assertEquals(ResourceLocation.parse("test:machine"), BeeDesignerEggs.bee(preview), host.name());
			assertTrue(BeeDesignerMachineRecipes.belongs(recipe, "test:machine"));
			assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
		}
	}

	@Test
	void machineAmountBoundariesMatchNativeCapabilitiesWithoutTruncation() {
		for (Host host : Host.values()) {
			String type = host == Host.MMR ? host.mod + ":machine_recipe" : host.mod + ":bee_craft";
			JsonObject recipe = BeeDesignerMachineRecipes.create(host, type, "bbb:tbi", "test:machine");
			var input = BeeDesignerMachineRecipes.entries(recipe).getFirst();
			assertEquals(Integer.MAX_VALUE, input.maximumAmount());
			input.value().addProperty(input.amountKey(), Integer.MAX_VALUE);
			assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
			input.value().addProperty(input.amountKey(), (long) Integer.MAX_VALUE + 1);
			assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
			assertEquals((long) Integer.MAX_VALUE + 1, input.value().get(input.amountKey()).getAsLong());
			input.value().addProperty(input.amountKey(), 1);
			BeeDesignerMachineRecipes.append(recipe, "fluid", true, BeeDesignerMachineRecipes.material(
					BeeDesignerSelections.fluid(new FluidStack(Fluids.WATER, 1000), REGISTRIES), REGISTRIES));
			var fluid = BeeDesignerMachineRecipes.entries(recipe).stream()
					.filter(entry -> entry.input() && entry.kind().equals("fluid")).findFirst().orElseThrow();
			long maximum = host == Host.MI ? Long.MAX_VALUE : Integer.MAX_VALUE;
			assertEquals(maximum, fluid.maximumAmount());
			fluid.value().addProperty(fluid.amountKey(), maximum);
			assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
			fluid.value().addProperty(fluid.amountKey(), java.math.BigInteger.valueOf(maximum).add(java.math.BigInteger.ONE));
			assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
		}
	}

	@Test
	void mbd2SupportsMixedMaterialCapabilitiesAndKeepsContentMetadata() {
		JsonObject recipe = BeeDesignerMachineRecipes.create(Host.MBD2, "mbd2:mekbb_craft", "mbd2:mekbb_craft", "test:machine");
		BeeDesignerMachineRecipes.append(recipe, "fluid", true, BeeDesignerMachineRecipes.material(
				BeeDesignerSelections.fluid(new FluidStack(Fluids.WATER, 1000), REGISTRIES), REGISTRIES));
		JsonObject chemical = JsonParser.parseString("{\"kind\":\"chemical\",\"id\":\"mekanism:hydrogen\"}").getAsJsonObject();
		BeeDesignerMachineRecipes.append(recipe, "chemical", false, BeeDesignerMachineRecipes.material(chemical, REGISTRIES));
		var entry = BeeDesignerMachineRecipes.entries(recipe).stream().filter(value -> value.kind().equals("chemical")).findFirst().orElseThrow();
		entry.node().addProperty("perTick", true);
		entry.node().addProperty("chance", 0.25);
		entry.node().addProperty("slotName", "retained slot");
		entry.node().addProperty("custom_metadata", "preserved");
		assertTrue(recipe.getAsJsonObject("outputs").has("mek_chemical"));
		assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
		entry.node().addProperty("chance", 1.1);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
		assertEquals("preserved", entry.node().get("custom_metadata").getAsString());
	}

	@Test
	void parserMapsMiParametersToEuAndDurationAndExpandsOnlyLiteralHelpers() {
		String source = "ServerEvents.recipes(event => { const add = (a, out) => event.recipes.modern_industrialization.bbb_tbi(30, 100).itemIn(a).itemOut(out);"
				+ "add('2x minecraft:honey_bottle', '" + EGG + "'); });";
		var candidate = BeeDesignerMachineScripts.parse(source).getFirst();
		assertTrue(candidate.parsed(), candidate.error());
		assertEquals(30, candidate.recipe().get("eu").getAsInt());
		assertEquals(100, candidate.recipe().get("duration").getAsInt());
		assertEquals(2, candidate.recipe().getAsJsonArray("item_inputs").get(0).getAsJsonObject().get("amount").getAsInt());
		assertTrue(BeeDesignerMachineRecipes.belongs(candidate.recipe(), "test:machine"));
	}

	@Test
	void mmrCoordinatesRemainPositionsAndProgressDataRoundTrips() {
		String source = "ServerEvents.recipes(event => { const time = 100; const machine_id = 'bbb:tbi';"
				+ "event.recipes.modular_machinery_reborn.machine_recipe(machine_id, time)"
				+ ".requireItem('3x minecraft:honey_bottle',10,10).produceItem('" + EGG + "',130,10)"
				+ ".requireEnergy(10000,160,10).width(180).height(70).progressData(ProgressData.create().x(100).y(10)); });";
		var candidate = BeeDesignerMachineScripts.parse(source).getFirst();
		assertTrue(candidate.parsed(), candidate.error());
		var entries = BeeDesignerMachineRecipes.entries(candidate.recipe());
		assertEquals(3, entries.get(0).value().get("count").getAsInt());
		assertEquals(10, entries.get(0).node().getAsJsonObject("position").get("x").getAsInt());
		assertEquals(1, entries.get(1).value().get("count").getAsInt());
		assertEquals(130, entries.get(1).node().getAsJsonObject("position").get("x").getAsInt());
		assertEquals(10000, entries.get(2).value().get("amount").getAsInt());
		assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(candidate.recipe(), REGISTRIES));
		entries.get(1).node().getAsJsonObject("position").addProperty("x", 180);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(candidate.recipe(), REGISTRIES));
	}

	@Test
	void invalidNutritionalPasteAmountIsRetainedAndCannotPassValidation() {
		String source = "ServerEvents.recipes(event => { event.recipes.mbd2.mekbb_craft().duration(15).inputFE(40000)"
				+ ".inputFluids('#c:honey 4000').inputFluids('mekanism:nutritional_paste 000')"
				+ ".inputItems('minecraft:honey_bottle').outputItems('" + EGG + "'); });";
		var candidate = BeeDesignerMachineScripts.parse(source).getFirst();
		assertTrue(candidate.parsed(), candidate.error());
		var fluid = BeeDesignerMachineRecipes.entries(candidate.recipe()).stream().filter(value -> value.kind().equals("fluid")).toList().getLast();
		assertEquals(0, fluid.value().get("amount").getAsInt());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(candidate.recipe(), REGISTRIES));
		assertTrue(source.substring(candidate.start(), candidate.end()).contains(" 000"));
	}

	@Test
	void expressionsUnknownCallsAndConditionalBodiesAreNeverPromotedToRecipes() {
		for (String body : List.of(
				"event.recipes.mbd2.bee_craft().inputItems(Java.type('untrusted').run()).outputItems('" + EGG + "');",
				"event.recipes.mbd2.bee_craft().unknownCall(1).outputItems('" + EGG + "');",
				"if (false) { event.recipes.mbd2.bee_craft().inputItems('minecraft:honey_bottle').outputItems('" + EGG + "'); }",
				"if (false) event.recipes.mbd2.bee_craft().inputItems('minecraft:honey_bottle').outputItems('" + EGG + "');")) {
			var candidates = BeeDesignerMachineScripts.parse("ServerEvents.recipes(event => {" + body + "});");
			assertFalse(candidates.isEmpty());
			assertTrue(candidates.stream().noneMatch(BeeDesignerMachineScripts.Candidate::parsed));
			assertTrue(candidates.stream().allMatch(value -> value.recipe() == null && !value.error().isEmpty()));
		}
	}

	@Test
	void nativeScriptExportKeepsStableIdsUnknownFieldsAndExactJson() {
		JsonObject document = BeeDesignerDocument.create("test:machine");
		for (Host host : Host.values()) {
			String type = host == Host.MMR ? host.mod + ":machine_recipe" : host.mod + ":bee_craft";
			JsonObject recipe = BeeDesignerMachineRecipes.create(host, type, "bbb:tbi", "test:machine");
			recipe.add("unknown_data", JsonParser.parseString("{\"preserve\":null,\"array\":[1,true,\"source\"]}"));
			document.getAsJsonObject("recipes").add("test:" + host.name().toLowerCase(java.util.Locale.ROOT), recipe);
		}
		var restored = BeeDesignerMachineScripts.parse(BeeDesignerMachineScripts.export(document));
		assertEquals(3, restored.size());
		for (var candidate : restored) {
			assertTrue(candidate.parsed(), candidate.error());
			JsonObject expected = document.getAsJsonObject("recipes").getAsJsonObject(candidate.id()).deepCopy();
			BeeDesignerMachineRecipes.recipeIdentity(expected, candidate.id());
			assertEquals(expected, candidate.recipe());
		}
	}

	@Test
	void fullSourceArchiveIsSeparateFromNetworkDocumentAndExternalEditsConflict(@TempDir Path temporary) throws Exception {
		Path original = temporary.resolve("original.js");
		String source = "//" + "source ".repeat(6000) + "\nServerEvents.recipes(event => {event.recipes.mbd2.bee_craft()"
				+ ".inputItems('minecraft:honey_bottle').outputItems('" + EGG + "');});";
		Files.writeString(original, source, StandardCharsets.UTF_8);
		Path directory = temporary.resolve("designer");
		var imported = BeeDesignerMachineSources.read(original, directory);
		JsonObject document = BeeDesignerDocument.create("test:machine");
		BeeDesignerMachineSources.verifyUnchanged(imported);
		BeeDesignerMachineSources.merge(document, imported, imported.candidates().getFirst(), "21.1.2");
		assertTrue(document.toString().getBytes(StandardCharsets.UTF_8).length < BeeDesignerProtocol.MAX_BYTES);
		assertEquals(source, Files.readString(directory.resolve("machine-sources/" + imported.sha256() + ".js"), StandardCharsets.UTF_8));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineSources.merge(document, imported, imported.candidates().getFirst(), "21.1.2"));
		Path script = BeeDesignerMachineSources.export(directory, document);
		assertTrue(Files.readString(script).contains("event.custom("));
		Files.writeString(original, source + "\n// external change", StandardCharsets.UTF_8);
		assertThrows(java.io.IOException.class, () -> BeeDesignerMachineSources.verifyUnchanged(imported));
	}

	@Test
	void invalidBeeIdentityAndFractionalQuantitiesCannotPassMachineValidation() {
		JsonObject recipe = BeeDesignerMachineRecipes.create(Host.MI, "modern_industrialization:bee_machine_recipe", "modern_industrialization:bee_machine_recipe", "test:machine");
		var output = BeeDesignerMachineRecipes.entries(recipe).stream().filter(value -> !value.input()).findFirst().orElseThrow();
		output.value().addProperty("amount", 1.5);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
		output.value().addProperty("amount", 1);
		var entity = output.node().getAsJsonObject("components").getAsJsonObject("minecraft:entity_data");
		entity.add("types", entity.remove("type"));
		assertFalse(BeeDesignerMachineRecipes.belongs(recipe, "test:machine"));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(recipe, REGISTRIES));
	}

	@Test
	void copyingBeeRetargetsOnlyMachineOutputsAndRemapsSourceIds() {
		JsonObject document = BeeDesignerDocument.create("test:machine");
		String id = "test:source";
		JsonObject recipe = BeeDesignerMachineRecipes.create(Host.MBD2, "mbd2:bee_craft", "mbd2:bee_craft", "test:machine");
		BeeDesignerMachineRecipes.append(recipe, "item", true, BeeDesignerMachineRecipes.itemMaterial(BeeDesignerEggs.output("test:machine")));
		document.getAsJsonObject("recipes").add(id, recipe);
		BeeDesignerDocument.object(document, "machine_sources").add(id, JsonParser.parseString("{\"source_sha256\":\"preserved\"}"));
		JsonObject copy = BeeDesignerDocument.copyAs(document, "test:copy");
		String copiedId = copy.getAsJsonObject("recipes").keySet().iterator().next();
		JsonObject copied = copy.getAsJsonObject("recipes").getAsJsonObject(copiedId);
		assertTrue(BeeDesignerMachineRecipes.belongs(copied, "test:copy"));
		var input = BeeDesignerMachineRecipes.entries(copied).stream().filter(value -> value.input() && value.kind().equals("item")).toList().getLast();
		assertEquals("test:machine", BeeDesignerEggs.bee(BeeDesignerMachineRecipes.itemIdentity(input)).toString());
		assertEquals("preserved", copy.getAsJsonObject("machine_sources").getAsJsonObject(copiedId).get("source_sha256").getAsString());
		assertTrue(BeeDesignerMachineRecipes.belongs(recipe, "test:machine"));
	}

	@Test
	void replacingMaterialRetainsQuantityProbabilityPositionAndHostMetadata() {
		for (Host host : Host.values()) {
			String type = host == Host.MMR ? host.mod + ":machine_recipe" : host.mod + ":bee_craft";
			JsonObject recipe = BeeDesignerMachineRecipes.create(host, type, "bbb:tbi", "test:machine");
			var entry = BeeDesignerMachineRecipes.entries(recipe).stream().filter(value -> value.input()).findFirst().orElseThrow();
			entry.value().addProperty(entry.amountKey(), 13);
			entry.node().addProperty(entry.chanceKey(), 0.4);
			entry.node().addProperty("custom_field", "keep");
			JsonObject position = entry.node().has("position") ? entry.node().getAsJsonObject("position").deepCopy() : null;
			ItemStack replacement = new ItemStack(Items.COPPER_INGOT);
			replacement.set(DataComponents.CUSTOM_NAME, Component.literal("retained identity"));
			BeeDesignerMachineRecipes.replaceMaterial(entry, BeeDesignerMachineRecipes.material(BeeDesignerSelections.item(replacement, REGISTRIES), REGISTRIES));
			assertEquals(13, entry.value().get(entry.amountKey()).getAsInt());
			assertEquals(0.4, entry.node().get(entry.chanceKey()).getAsDouble());
			assertEquals("keep", entry.node().get("custom_field").getAsString());
			assertTrue(ItemStack.isSameItemSameComponents(replacement, BeeDesignerMachineRecipes.decodeItem(entry, REGISTRIES)));
			if (position != null) {
				assertEquals(position, entry.node().getAsJsonObject("position"));
			}
		}
	}

	@Test
	void templatesAndRegexCannotInjectExtraRecipeCandidates() {
		String source = "const text = `event.recipes.mbd2.bee_craft().inputItems('minecraft:stone')`;"
				+ "const regex = /event.recipes.mbd2.bee_craft()/;";
		assertTrue(BeeDesignerMachineScripts.parse(source).isEmpty());
		var coordinate = BeeDesignerMachineScripts.parse("ServerEvents.recipes(event => {event.recipes.modular_machinery_reborn.machine_recipe('bbb:tbi',100)"
				+ ".requireItem('minecraft:honey_bottle',10.5,10).produceItem('" + EGG + "',130,10).width(180).height(70);});").getFirst();
		assertTrue(coordinate.parsed(), coordinate.error());
		assertEquals(10.5, BeeDesignerMachineRecipes.entries(coordinate.recipe()).getFirst().node().getAsJsonObject("position").get("x").getAsDouble());
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(coordinate.recipe(), REGISTRIES));
	}

	@Test
	void mmrCodecNestedSizedIngredientKeepsBeeIdentity() {
		JsonObject restored = JsonParser.parseString("{\"height\":70,\"width\":180,\"requirements\":["
				+ "{\"type\":\"modular_machinery_reborn:item\",\"position\":{\"x\":10,\"y\":10},\"uses_data_components\":true,\"sized_ingredient\":{\"ingredient\":{\"item\":{\"id\":\"minecraft:honey_bottle\",\"count\":1}}},\"mode\":\"INPUT\"},"
				+ "{\"type\":\"modular_machinery_reborn:item\",\"position\":{\"x\":130,\"y\":10},\"uses_data_components\":true,\"sized_ingredient\":{\"ingredient\":{\"item\":{\"id\":\"minecraft:bee_spawn_egg\",\"count\":1}}},\"mode\":\"OUTPUT\"}],"
				+ "\"machine\":\"designer_probe:bee_machine\",\"time\":20,\"type\":\"modular_machinery_reborn:machine_recipe\"}").getAsJsonObject();
		var output = BeeDesignerMachineRecipes.entries(restored).stream().filter(value -> !value.input()).findFirst().orElseThrow();
		assertEquals("minecraft:bee_spawn_egg", BeeDesignerDocument.text(BeeDesignerMachineRecipes.itemIdentity(output), "id", ""));
		assertEquals("minecraft:bee", BeeDesignerEggs.bee(BeeDesignerMachineRecipes.itemIdentity(output)).toString());
		assertTrue(BeeDesignerMachineRecipes.belongs(restored, "minecraft:bee"));
		assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(restored, REGISTRIES));
		output.value().addProperty("count", 3);
		BeeDesignerMachineRecipes.retargetOutputs(restored, "minecraft:bee", "test:machine");
		assertTrue(BeeDesignerMachineRecipes.belongs(restored, "test:machine"));
		assertEquals(3, output.value().get("count").getAsInt());
		assertEquals(130, output.node().getAsJsonObject("position").get("x").getAsInt());
		assertEquals("minecraft:honey_bottle", BeeDesignerDocument.text(BeeDesignerMachineRecipes.itemIdentity(
				BeeDesignerMachineRecipes.entries(restored).getFirst()), "id", ""));
		restored.add("progress_data", JsonParser.parseString("{\"position\":{\"x\":100,\"y\":10},\"empty_texture\":\"test:empty\"}"));
		restored.addProperty("render_progress", false);
		assertEquals("progress_data", BeeDesignerMachineRecipes.fieldName(restored, "progressData"));
		assertEquals("render_progress", BeeDesignerMachineRecipes.fieldName(restored, "renderProgress"));
		assertEquals("empty_texture", BeeDesignerMachineRecipes.fieldName(restored.getAsJsonObject("progress_data"), "emptyTexture"));
		assertDoesNotThrow(() -> BeeDesignerMachineRecipes.validateStructure(restored, REGISTRIES));
		restored.getAsJsonObject("progress_data").getAsJsonObject("position").addProperty("x", 180);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRecipes.validateStructure(restored, REGISTRIES));
	}

	@Test
	void readingMachineSourceRetainsOriginalArraysAliasesAndUnknownFields() {
		JsonObject original = BeeDesignerMachineRecipes.create(Host.MMR, "modular_machinery_reborn:machine_recipe", "test:machine", "minecraft:bee");
		original.getAsJsonArray("requirements").get(1).getAsJsonObject().addProperty("custom_material", "preserved");
		original.addProperty("custom_recipe", "preserved");
		JsonObject encoded = original.deepCopy();
		JsonObject output = encoded.getAsJsonArray("requirements").get(1).getAsJsonObject();
		output.remove("custom_material");
		output.add("sized_ingredient", output.remove("sizedIngredient"));
		JsonObject document = BeeDesignerDocument.create("test:machine");
		document.getAsJsonObject("recipes").add("test:source", encoded);
		BeeDesignerDocument.object(document, "machine_sources").add("test:source", new JsonObject());
		PackResources pack = mock(PackResources.class);
		when(pack.packId()).thenReturn("file/source-pack");
		byte[] bytes = original.toString().getBytes(StandardCharsets.UTF_8);
		Resource resource = new Resource(pack, () -> new ByteArrayInputStream(bytes));
		var snapshot = new BeeDesignerReader.Snapshot(document, Map.of("test:source", resource), 0);
		JsonObject restored = BeeDesignerReader.readSources(snapshot);
		assertEquals(original, restored.getAsJsonObject("recipes").getAsJsonObject("test:source"));
		assertEquals("file/source-pack", restored.getAsJsonObject("machine_sources").getAsJsonObject("test:source").get("source_pack").getAsString());
		assertEquals(encoded, document.getAsJsonObject("recipes").getAsJsonObject("test:source"));
	}

	@Test
	void missingHostsRejectApplicationWithoutChangingRecipe() {
		for (Host host : Host.values()) {
			assertFalse(BeeDesignerMachineRuntime.problem(host).isEmpty());
			String type = host == Host.MMR ? host.mod + ":machine_recipe" : host.mod + ":bee_craft";
			JsonObject recipe = BeeDesignerMachineRecipes.create(host, type, "test:machine", "minecraft:bee");
			JsonObject previous = recipe.deepCopy();
			assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRuntime.validate(recipe, null));
			assertEquals(previous, recipe);
		}
	}

	@Test
	void hostRoundTripCannotChangeAmountComponentsOrEnergy() {
		JsonObject recipe = BeeDesignerMachineRecipes.create(Host.MI, "modern_industrialization:bee_craft", "test:machine", "minecraft:bee");
		assertDoesNotThrow(() -> BeeDesignerMachineRuntime.validateRoundTrip(recipe, recipe.deepCopy(), REGISTRIES));
		JsonObject changed = recipe.deepCopy();
		changed.getAsJsonArray("item_outputs").get(0).getAsJsonObject().addProperty("amount", 2);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRuntime.validateRoundTrip(recipe, changed, REGISTRIES));
		JsonObject named = recipe.deepCopy();
		var output = BeeDesignerMachineRecipes.entries(named).stream().filter(value -> !value.input()).findFirst().orElseThrow();
		ItemStack egg = new ItemStack(Items.BEE_SPAWN_EGG);
		egg.set(DataComponents.CUSTOM_NAME, Component.literal("Must survive host codec"));
		BeeDesignerMachineRecipes.replaceMaterial(output, BeeDesignerMachineRecipes.material(BeeDesignerSelections.item(egg, REGISTRIES), REGISTRIES));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRuntime.validateRoundTrip(named, recipe, REGISTRIES));
		JsonObject energy = recipe.deepCopy();
		energy.addProperty("eu", 2);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerMachineRuntime.validateRoundTrip(recipe, energy, REGISTRIES));
	}
}
