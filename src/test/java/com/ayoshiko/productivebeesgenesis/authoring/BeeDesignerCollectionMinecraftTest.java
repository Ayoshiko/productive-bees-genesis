package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerCollectionMinecraftTest {

	@Test
	void portableCollectionPreservesSourcesNamesAndWholeProjects(@TempDir Path directory) throws IOException {
		JsonObject first = BeeDesignerDocument.create("pack:gold");
		first.getAsJsonObject("names").addProperty("zh_cn", "金蜂");
		first.getAsJsonObject("names").addProperty("fr_fr", "Abeille d’or");
		first.getAsJsonObject("definition").addProperty("model", "other:geo/custom.geo.json");
		JsonObject origin = new JsonObject();
		origin.addProperty("kind", "pack");
		origin.addProperty("source_pack", "file/original-pack.zip");
		first.add("_editor_origin", origin);
		first.addProperty("custom_extension", "kept");
		JsonObject second = BeeDesignerDocument.create("pack:iron");
		String before = first.toString();
		JsonObject credits = source("Original pack");
		Path zip = BeeDesignerCollection.export(directory, Map.of("pack:gold", first.toString(), "pack:iron", second.toString()), credits);
		Map<String, String> loaded = BeeDesignerArchive.read(zip);
		assertEquals(2, loaded.size());
		JsonObject restored = JsonParser.parseString(loaded.get("pack:gold")).getAsJsonObject();
		assertEquals(origin, restored.get("_editor_origin"));
		assertEquals("kept", restored.get("custom_extension").getAsString());
		assertEquals(first.get("definition"), restored.get("definition"));
		assertEquals(first.get("names"), restored.get("names"));
		assertEquals(credits, restored.getAsJsonArray(BeeDesignerCredits.FIELD).get(0));
		assertEquals(before, first.toString());
		try (ZipFile archive = new ZipFile(zip.toFile())) {
			assertNull(archive.getEntry("pack.mcmeta"));
			assertNotNull(archive.getEntry("bee-collection.json"));
			assertTrue(new String(archive.getInputStream(archive.getEntry("CREDITS.txt")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("Original pack"));
		}
	}

	@Test
	void exportingAgainKeepsOriginalAttributionWithoutDuplicates() {
		JsonObject project = BeeDesignerDocument.create("pack:bee");
		JsonObject first = BeeDesignerCredits.attach(project, source("First pack"));
		JsonObject same = BeeDesignerCredits.attach(first, source("First pack"));
		assertEquals(1, same.getAsJsonArray(BeeDesignerCredits.FIELD).size());
		JsonObject moved = BeeDesignerCredits.attach(same, source("Second pack"));
		assertEquals(2, moved.getAsJsonArray(BeeDesignerCredits.FIELD).size());
		assertEquals("First pack", moved.getAsJsonArray(BeeDesignerCredits.FIELD).get(0).getAsJsonObject().get("pack").getAsString());
		assertFalse(project.has(BeeDesignerCredits.FIELD));
	}

	@Test
	void multiBeeKubeJsExportUsesOneStableScriptAndKeepsBothRecipes(@TempDir Path directory) throws IOException {
		JsonObject first = BeeDesignerDocument.create("pack:gold");
		JsonObject second = BeeDesignerDocument.create("pack:iron");
		String recipe1 = BeeDesignerDocument.addRecipe(first, "minecraft:crafting_shapeless");
		String recipe2 = BeeDesignerDocument.addRecipe(second, "minecraft:crafting_shapeless");
		Map<String, String> documents = Map.of("pack:gold", first.toString(), "pack:iron", second.toString());
		Map<String, String> files = BeeDesignerArchive.kubeJsFiles(documents, source("Modpack"));
		String scriptPath = files.keySet().stream().filter(path -> path.startsWith("kubejs/server_scripts/")).findFirst().orElseThrow();
		String script = files.get(scriptPath);
		assertTrue(script.contains(recipe1));
		assertTrue(script.contains(recipe2));
		assertEquals(2, script.split("event.custom", -1).length - 1);
		assertTrue(BeeDesignerArchive.kubeJsFiles(documents, source("Renamed pack")).containsKey(scriptPath));
		JsonObject third = BeeDesignerDocument.create("pack:copper");
		Map<String, String> other = BeeDesignerArchive.kubeJsFiles(Map.of("pack:gold", first.toString(), "pack:copper", third.toString()), source("Modpack"));
		assertFalse(other.containsKey(scriptPath));
		assertEquals(2, BeeDesignerArchive.read(BeeDesignerArchive.exportKubeJs(directory, documents, source("Modpack"))).size());
	}

	@Test
	void portableArchivesKeepIndependentProjectsButCompiledBundlesRejectCollisions() {
		JsonObject first = BeeDesignerDocument.create("one:gold");
		JsonObject second = BeeDesignerDocument.create("two:gold");
		Map<String, String> documents = Map.of("one:gold", first.toString(), "two:gold", second.toString());
		assertNotNull(BeeDesignerCollection.files(documents, source("Modpack")));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerArchive.kubeJsFiles(documents, source("Modpack")));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCollection.sources(Map.of("wrong:id", first.toString()), source("Modpack")));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCollection.sources(Map.of(), source("Modpack")));
		JsonObject myriad = BeeDesignerDocument.create(com.ayoshiko.productivebeesgenesis.util.PBConstants.MYRIADCREATIONS_TYPE_STRING);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCollection.sources(Map.of(myriad.get("id").getAsString(), myriad.toString()), source("Modpack")));
	}

	@Test
	void manifestUsesDeclaredMetadataWithoutGuessingLinks(@TempDir Path directory) throws IOException {
		Path manifest = directory.resolve("manifest.json");
		Files.writeString(manifest, "{\"manifestType\":\"minecraftModpack\",\"name\":\"Original pack\",\"version\":\"3.0\",\"author\":\"Team\",\"files\":[{\"projectID\":123}]}\n");
		JsonObject credits = BeeDesignerCredits.readManifest(manifest);
		assertEquals("Original pack", credits.get("pack").getAsString());
		assertEquals("Team", credits.get("author").getAsString());
		assertEquals("", credits.get("url").getAsString());
		assertEquals("", credits.get("permission").getAsString());
		credits.addProperty("url", "https://www.curseforge.com/minecraft/modpacks/example");
		assertDoesNotThrow(() -> BeeDesignerCredits.validate(credits));
		credits.addProperty("url", "file:///secret");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCredits.validate(credits));
		credits.addProperty("url", "https://user:password@example.com/path");
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerCredits.validate(credits));
	}

	private JsonObject source(String pack) {
		JsonObject source = BeeDesignerCredits.empty();
		source.addProperty("pack", pack);
		return source;
	}

	@Test
	void importingAcrossInstancesPreservesExistingDraftBeforeReplacement(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("pack:imported");
		original.getAsJsonObject("names").addProperty("zh_cn", "原整合包蜜蜂");
		BeeDesignerDocument.addRecipe(original, "productivebees:item_conversion");
		Path archive = BeeDesignerCollection.export(directory.resolve("source"), Map.of("pack:imported", original.toString()), source("Source pack"));
		JsonObject incoming = JsonParser.parseString(BeeDesignerArchive.read(archive).get("pack:imported")).getAsJsonObject();
		Path target = directory.resolve("target");
		JsonObject local = BeeDesignerDocument.create("pack:imported");
		local.getAsJsonObject("definition").addProperty("custom_local", "do not lose");
		BeeDesignerDrafts.save(target, local);
		Path preserved = BeeDesignerDrafts.preserveBeforeImport(target, incoming);
		assertNotNull(preserved);
		assertEquals(local, JsonParser.parseString(Files.readString(preserved)));
		BeeDesignerDrafts.save(target, incoming);
		assertEquals(incoming, BeeDesignerDrafts.read(target, "pack:imported"));
		assertEquals(local, BeeDesignerDrafts.archived(target, preserved.getFileName().toString()));
		assertNull(BeeDesignerDrafts.preserveBeforeImport(target, incoming));
	}

	@Test
	void importPreviewDistinguishesWorldDraftAndPbNameCollisions() {
		var same = BeeDesignerImportConflicts.inspect("pack:metal_bee", java.util.Set.of("pack:metal_bee"), java.util.Set.of("pack:metal_bee"));
		assertTrue(same.worldIdentity());
		assertTrue(same.localDraft());
		assertTrue(same.canKeepIdentity());
		var collision = BeeDesignerImportConflicts.inspect("pack:metal_bee", java.util.Set.of("other:metal"), java.util.Set.of());
		assertFalse(collision.canKeepIdentity());
		assertEquals(java.util.List.of("other:metal"), collision.nameCollisions());
	}

	@Test
	void copyingForAnotherPackRetargetsOnlyItsOwnBeeConditions() {
		JsonObject project = BeeDesignerDocument.create("pack:original");
		String id = BeeDesignerDocument.addRecipe(project, "minecraft:crafting_shapeless");
		var conditions = JsonParser.parseString("[{\"type\":\"productivebees:bee_exists\",\"bee\":\"pack:original\"},{\"type\":\"productivebees:bee_exists\",\"bee\":\"other:parent\"}]");
		project.getAsJsonObject("definition").add("conditions", conditions.deepCopy());
		project.getAsJsonObject("recipes").getAsJsonObject(id).add("neoforge:conditions", conditions.deepCopy());
		JsonObject copy = BeeDesignerDocument.copyAs(project, "pack:copied");
		var copied = copy.getAsJsonObject("definition").getAsJsonArray("conditions");
		assertEquals("pack:copied", copied.get(0).getAsJsonObject().get("bee").getAsString());
		assertEquals("other:parent", copied.get(1).getAsJsonObject().get("bee").getAsString());
		var recipe = copy.getAsJsonObject("recipes").entrySet().iterator().next().getValue().getAsJsonObject();
		assertEquals("pack:copied", recipe.getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject().get("bee").getAsString());
		assertEquals(conditions, project.getAsJsonObject("definition").get("conditions"));
	}
}
