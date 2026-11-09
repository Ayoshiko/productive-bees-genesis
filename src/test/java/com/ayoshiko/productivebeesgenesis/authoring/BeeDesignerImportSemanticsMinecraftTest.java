package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
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
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerImportSemanticsMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final String CHAIN = "event.recipes.modern_industrialization.assembler(30,100).itemIn('minecraft:honey_bottle').itemOut('minecraft:stone')";

    @Test
    void gathersContributionsFromJarsWithoutBeeDefinitions(@TempDir Path directory) throws Exception {
        Path mods = Files.createDirectories(directory.resolve("mods"));
        JsonObject document = BeeDesignerDocument.create("test:catalog");
        String recipeId = BeeDesignerDocument.addRecipe(document, "productivebees:bee_breeding");
        JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(recipeId);
        String language = "{\"" + BeeDesignerDocument.translationKey("test:catalog") + "\":\"来自附属包\"}";
        zip(mods.resolve("a.JAR"), Map.of("data/test/productivebees/catalog.json", document.get("definition").toString()));
        zip(mods.resolve("b.jar"), Map.of("data/test/recipe/contribution.json", recipe.toString(),
                "assets/productivebees/lang/zh_cn.json", language));
        var catalog = BeeDesignerInstanceCatalog.read(directory);
        assertTrue(catalog.problems().isEmpty(), catalog.problems().toString());
        JsonObject imported = JsonParser.parseString(catalog.documents().get("test:catalog")).getAsJsonObject();
        assertEquals(recipe, imported.getAsJsonObject("recipes").get("test:contribution"));
        assertEquals("来自附属包", imported.getAsJsonObject("names").get("zh_cn").getAsString());
        assertFalse(catalog.runtimeSnapshot());
    }

    @Test
    void staticExpressionsStayVisibleAndUnparsed() {
        for (String body : List.of("false && " + CHAIN, "true || " + CHAIN,
                "false ? " + CHAIN + " : null", "true ? null : " + CHAIN,
                "false && (" + CHAIN + ")", "true || (false ? null : " + CHAIN + ")")) {
            var candidates = BeeDesignerMachineScripts.parse("ServerEvents.recipes(event => {" + body + ";});");
            assertFalse(candidates.isEmpty(), body);
            assertTrue(candidates.stream().noneMatch(BeeDesignerMachineScripts.Candidate::parsed), body);
            assertTrue(candidates.stream().allMatch(candidate -> candidate.recipe() == null && !candidate.error().isBlank()));
        }
        String source = "ServerEvents.recipes(event => {const add = () => " + CHAIN + ";false && add();});";
        assertFalse(BeeDesignerMachineScripts.parse(source).getFirst().parsed());
        assertTrue(BeeDesignerMachineScripts.parse("ServerEvents.recipes(event => {" + CHAIN + ";});").getFirst().parsed());
    }

    @Test
    void uppercaseImportsAndFragmentsRetainContent(@TempDir Path directory) throws Exception {
        JsonObject document = BeeDesignerDocument.create("test:uppercase");
        Path json = directory.resolve("project.JSON");
        Files.writeString(json, document.toString(), StandardCharsets.UTF_8);
        assertEquals(document, JsonParser.parseString(BeeDesignerArchive.read(json).get("test:uppercase")));
        Path archive = directory.resolve("project.ZIP");
        JsonObject index = new JsonObject();
        index.add("test:uppercase", document);
        zip(archive, Map.of("data/productivebeesgenesis/bee_designer/index.json", index.toString()));
        assertEquals(document, JsonParser.parseString(BeeDesignerArchive.read(archive).get("test:uppercase")));
        String raw = "ServerEvents.recipes(event => {" + CHAIN + ";});";
        Path script = directory.resolve("recipes.JS");
        Files.writeString(script, raw, StandardCharsets.UTF_8);
        var imported = BeeDesignerMachineSources.read(script, directory.resolve("local"));
        assertTrue(imported.candidates().getFirst().parsed());
        assertEquals(CHAIN, BeeDesignerMachineSources.fragment(directory.resolve("local"), imported, imported.candidates().getFirst()));
        assertEquals(raw, Files.readString(script));
    }

    @Test
    void exportsOrdinaryTextAndHonorsStructuredFalseCondition() {
        JsonObject document = BeeDesignerDocument.create("test:export");
        String id = BeeDesignerDocument.addRecipe(document, "productivebees:bee_breeding");
        JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(id);
        recipe.addProperty("description", "neoforge:false");
        String script = script(BeeDesignerArchive.kubeJsFiles(document));
        assertTrue(script.contains("event.custom(JSON.parse("));
        assertTrue(script.contains(id));
        recipe.add("neoforge:conditions", JsonParser.parseString("[{\"type\":\"neoforge:false\"}]"));
        assertFalse(script(BeeDesignerArchive.kubeJsFiles(document)).contains("event.custom("));
        assertEquals("neoforge:false", recipe.get("description").getAsString());
    }

    @Test
    void selectionPreservesAmountsAndRefusesComponentLoss() {
        JsonObject previous = JsonParser.parseString("{\"fluid\":\"minecraft:water\",\"amount\":750,\"extra\":{\"keep\":true}}").getAsJsonObject();
        String original = previous.toString();
        JsonObject selection = BeeDesignerSelections.fluid(new FluidStack(Fluids.LAVA, 1000), REGISTRIES);
        JsonObject replacement = BeeDesignerSelections.bottlerFluid(selection, previous, REGISTRIES);
        assertEquals("minecraft:lava", replacement.get("fluid").getAsString());
        assertEquals(750, replacement.get("amount").getAsInt());
        assertEquals(previous.get("extra"), replacement.get("extra"));
        assertEquals(original, previous.toString());
        assertFalse(selection.has("id"));
        for (String name : List.of("A", "B")) {
            ItemStack food = new ItemStack(Items.POPPY, 4);
            food.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            assertThrows(IllegalArgumentException.class, () -> BeeDesignerSelections.plainItemId(food));
            assertEquals(4, food.getCount());
            FluidStack fluid = new FluidStack(Fluids.WATER, 1000);
            fluid.set(DataComponents.CUSTOM_NAME, Component.literal(name));
            JsonObject typed = BeeDesignerSelections.fluid(fluid, REGISTRIES);
            assertThrows(IllegalArgumentException.class, () -> BeeDesignerSelections.bottlerFluid(typed, previous, REGISTRIES));
            assertEquals(original, previous.toString());
        }
        assertEquals("minecraft:poppy", BeeDesignerSelections.plainItemId(new ItemStack(Items.POPPY)));
    }

    @Test
    void rejectsKnownWrongTypesWithoutChangingUnknownFields() {
        for (String field : List.of("createComb", "size", "primaryColor", "attributes", "flowerResource")) {
            JsonObject document = BeeDesignerDocument.create("test:types");
            document.getAsJsonObject("definition").add(field, JsonParser.parseString(field.equals("attributes") || field.equals("flowerResource") ? "false" : "{}"));
            String original = document.toString();
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateImportedStructure(document));
            assertTrue(failure.getMessage().contains(field));
            assertEquals(original, document.toString());
        }
        JsonObject document = BeeDesignerDocument.create("test:types");
        document.getAsJsonObject("definition").add("thirdparty_extension", JsonParser.parseString("{\"arbitrary\":[null,true]}"));
        String original = document.toString();
        assertDoesNotThrow(() -> BeeDesignerCompiler.validateImportedStructure(document));
        assertEquals(original, document.toString());
    }

    @Test
    void unfinishedNumericDraftRemainsSaveable(@TempDir Path directory) throws Exception {
        JsonObject document = BeeDesignerDocument.create("test:unfinished");
        document.getAsJsonObject("definition").addProperty("size", "-");
        BeeDesignerDrafts.save(directory, document);
        assertEquals(document, BeeDesignerDrafts.read(directory, "test:unfinished"));
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.validateImportedStructure(document));
    }

    private static String script(Map<String, String> files) {
        return files.entrySet().stream().filter(entry -> entry.getKey().endsWith(".js")).findFirst().orElseThrow().getValue();
    }

    private static void zip(Path file, Map<String, String> entries) throws Exception {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(file))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
