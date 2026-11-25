package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerCollectionMinecraftTest {
	@Test
	void standardWorldInstallAndCompatibilityKeepOrdinaryPbContentIndependent(@TempDir Path directory) throws IOException {
		String id = "productivebeesgenesis:standalone_plain";
		JsonObject bee = BeeDesignerDocument.create(id);
		bee.getAsJsonObject("names").addProperty("zh_cn", "无需创世验证蜂");
		bee.getAsJsonObject("names").addProperty("en_us", "Independent PB Bee");
		String crafting = BeeDesignerDocument.addRecipe(bee, "minecraft:crafting_shapeless");
		JsonArray items = new JsonArray();
		items.add(BeeDesignerDocument.itemOutput(BeeDesignerDocument.comb(id)));
		BeeDesignerProduction.replace(bee, "ordinary", false, items, null, new JsonArray(), 20);
		BeeDesignerProduction.replace(bee, "centrifuge", true, items, null, new JsonArray(), 20);
		Map<String, String> documents = Map.of(id, bee.toString());
		assertFalse(BeeDesignerCompatibility.describe(documents).requiresGenesis());
		JsonObject extended = bee.deepCopy();
		String resource = BeeDesignerDocument.addRecipe(extended, "productivebeesgenesis:resource_production");
		assertTrue(BeeDesignerCompatibility.describe(Map.of(id, extended.toString())).requirements().stream()
				.anyMatch(requirement -> requirement.reason().equals("resources")));
		JsonObject referenced = bee.deepCopy();
		referenced.getAsJsonObject("definition").addProperty("breedingItem", "productivebeesgenesis:bee_designer");
		assertTrue(BeeDesignerCompatibility.describe(Map.of(id, referenced.toString())).requiresGenesis());
		Map<String, String> files = BeeDesignerArchive.exportFiles(documents, source("Independent PB Fixture"), "install_world_datapack");
		Path world = directory.resolve("saves/中文世界");
		Files.createDirectories(world);
		var plan = BeeDesignerInstallation.prepareWorldDatapack(directory, world, files);
		assertEquals(world.resolve("datapacks/bee-designer"), plan.target());
		assertFalse(Files.exists(plan.target()));
		BeeDesignerInstallation.install(plan);
		assertTrue(Files.exists(plan.target().resolve("data/productivebeesgenesis/productivebees/standalone_plain.json")));
		assertTrue(Files.exists(directory.resolve("resourcepacks/bee-designer/assets/productivebees/lang/zh_cn.json")));
		assertFalse(Files.exists(plan.target().resolve("assets")));
		assertFalse(Files.exists(plan.target().resolve("data/productivebeesgenesis/bee_designer/index.json")));
		var stale = BeeDesignerInstallation.prepareWorldDatapack(directory, world, files);
		Path language = directory.resolve("resourcepacks/bee-designer/assets/productivebees/lang/zh_cn.json");
		Files.writeString(language, "{\"external.keep\":\"外部修改\"}", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> BeeDesignerInstallation.install(stale));
		assertEquals("{\"external.keep\":\"外部修改\"}", Files.readString(language));
		JsonObject fixture = new JsonObject();
		fixture.addProperty("bee", id);
		fixture.addProperty("crafting", crafting);
		fixture.addProperty("production", "productivebeesgenesis:bee_designer/standalone_plain/production/ordinary/items");
		fixture.addProperty("centrifuge", "productivebeesgenesis:bee_designer/standalone_plain/production/centrifuge/items");
		fixture.add("files", new Gson().toJsonTree(files));
		System.out.println("STANDALONE_PB_FIXTURE " + fixture);
	}

	@Test
	void runtimeKubeJsInstallKeepsTranslationsAndRefusesChangedPreview(@TempDir Path directory) throws IOException {
		JsonObject bee = BeeDesignerDocument.create("installcheck:kube_gold");
		bee.getAsJsonObject("names").addProperty("zh_cn", "直接安装蜜蜂");
		bee.getAsJsonObject("names").addProperty("en_us", "Kube Install Bee");
		String recipe = BeeDesignerDocument.addRecipe(bee, "minecraft:crafting_shaped");
		Map<String, String> generated = BeeDesignerArchive.exportFiles(Map.of("installcheck:kube_gold", bee.toString()),
				source("Install Fixture"), "runtime_kubejs");
		String languagePath = "kubejs/assets/productivebees/lang/zh_cn.json";
		Path language = directory.resolve(languagePath);
		Files.createDirectories(language.getParent());
		String original = "{\"entity.thirdparty.keep\":\"外部蜜蜂译名\",\"" + BeeDesignerDocument.translationKey("installcheck:kube_gold") + "\":\"旧译名\"}\n";
		Files.writeString(language, original, StandardCharsets.UTF_8);
		var plan = BeeDesignerInstallation.prepareKubeJs(directory, generated);
		assertEquals(directory.toAbsolutePath().normalize(), plan.instance());
		assertEquals(directory.resolve("kubejs"), plan.target());
		assertFalse(Files.exists(plan.backupDirectory()));
		assertEquals(original, Files.readString(language, StandardCharsets.UTF_8));
		assertEquals(generated.keySet(), plan.files().keySet());
		assertEquals(plan.files().size(), plan.changes().size());
		assertEquals(1, plan.replaceCount());
		assertEquals(plan.files().size() - 1, plan.createCount());
		JsonObject merged = JsonParser.parseString(plan.files().get(languagePath)).getAsJsonObject();
		assertEquals("外部蜜蜂译名", merged.get("entity.thirdparty.keep").getAsString());
		assertEquals("直接安装蜜蜂", merged.get(BeeDesignerDocument.translationKey("installcheck:kube_gold")).getAsString());
		assertEquals(plan.target(), BeeDesignerInstallation.install(plan));
		for (var file : plan.files().entrySet()) {
			assertEquals(file.getValue(), Files.readString(directory.resolve(file.getKey()), StandardCharsets.UTF_8));
		}
		assertEquals(original, Files.readString(plan.backupDirectory().resolve("original").resolve(languagePath), StandardCharsets.UTF_8));
		assertEquals("installed", JsonParser.parseString(Files.readString(plan.backupDirectory().resolve("installation.json"),
				StandardCharsets.UTF_8)).getAsJsonObject().get("phase").getAsString());
		var repeat = BeeDesignerInstallation.prepareKubeJs(directory, generated);
		assertEquals(0, repeat.createCount());
		assertEquals(0, repeat.replaceCount());
		assertEquals(plan.files().size(), repeat.unchangedCount());
		assertEquals(plan.target(), BeeDesignerInstallation.install(repeat));
		assertFalse(Files.exists(repeat.backupDirectory()));
		var changed = new java.util.LinkedHashMap<>(generated);
		String script = generated.keySet().stream().filter(path -> path.startsWith("kubejs/server_scripts/")).findFirst().orElseThrow();
		changed.put(script, generated.get(script) + "\n// Next reviewed version\n");
		var stale = BeeDesignerInstallation.prepareKubeJs(directory, changed);
		JsonObject external = merged.deepCopy();
		external.addProperty("entity.thirdparty.latest", "较新的外部译名");
		String latest = external.toString();
		Files.writeString(language, latest, StandardCharsets.UTF_8);
		var conflict = assertThrows(IOException.class, () -> BeeDesignerInstallation.install(stale));
		assertTrue(conflict.getMessage().startsWith("designer.error.kubejs_install_changed"));
		assertFalse(Files.exists(stale.backupDirectory()));
		assertEquals(latest, Files.readString(language, StandardCharsets.UTF_8));
		assertEquals(plan.files().get(script), Files.readString(directory.resolve(script), StandardCharsets.UTF_8));
		assertThrows(IOException.class, () -> BeeDesignerInstallation.prepareKubeJs(directory, Map.of("kubejs/data/../../outside.json", "{}")));
		Path linkedInstance = Files.createDirectory(directory.resolve("linked-instance"));
		Path linkedLanguage = linkedInstance.resolve(languagePath);
		Files.createDirectories(linkedLanguage.getParent());
		Path externalFile = directory.resolve("external-language.json");
		Files.writeString(externalFile, original, StandardCharsets.UTF_8);
		Files.createLink(linkedLanguage, externalFile);
		assertThrows(IOException.class, () -> BeeDesignerInstallation.prepareKubeJs(linkedInstance, generated));
		assertEquals(original, Files.readString(externalFile, StandardCharsets.UTF_8));
		Path malformedInstance = Files.createDirectory(directory.resolve("malformed-instance"));
		Path malformedLanguage = malformedInstance.resolve(languagePath);
		Files.createDirectories(malformedLanguage.getParent());
		Files.writeString(malformedLanguage, "{", StandardCharsets.UTF_8);
		assertThrows(IOException.class, () -> BeeDesignerInstallation.prepareKubeJs(malformedInstance, generated));
		assertEquals("{", Files.readString(malformedLanguage, StandardCharsets.UTF_8));
		JsonObject datapack = BeeDesignerDocument.create("installcheck:datapack_gold");
		datapack.getAsJsonObject("names").addProperty("zh_cn", "数据包安装蜜蜂");
		datapack.getAsJsonObject("names").addProperty("en_us", "Datapack Install Bee");
		String datapackRecipe = BeeDesignerDocument.addRecipe(datapack, "minecraft:crafting_shaped");
		Map<String, String> datapackFiles = BeeDesignerArchive.exportFiles(Map.of("installcheck:datapack_gold", datapack.toString()),
				source("Install Fixture Datapack"), "runtime_datapack");
		JsonObject other = BeeDesignerDocument.create("installcheck:other");
		other.getAsJsonObject("names").addProperty("zh_cn", "保留的另一只蜜蜂");
		Map<String, String> nativeFiles = BeeDesignerArchive.exportFiles(Map.of("installcheck:datapack_gold", datapack.toString(),
				"installcheck:other", other.toString()), source("Native Fixture"), "install_datapack");
		var nativePlan = BeeDesignerInstallation.prepareDatapack(directory, nativeFiles);
		assertFalse(Files.exists(nativePlan.target()));
		BeeDesignerInstallation.install(nativePlan);
		Path otherDefinition = nativePlan.target().resolve("data/installcheck/productivebees/other.json");
		String retainedOther = Files.readString(otherDefinition);
		datapack.getAsJsonObject("names").addProperty("zh_cn", "单蜂更新后的名称");
		var single = BeeDesignerInstallation.prepareDatapack(directory, BeeDesignerArchive.exportFiles(
				Map.of("installcheck:datapack_gold", datapack.toString()), source("Native Update"), "install_datapack"));
		assertEquals(nativePlan.target(), single.target());
		BeeDesignerInstallation.install(single);
		assertEquals(retainedOther, Files.readString(otherDefinition));
		JsonObject nativeNames = JsonParser.parseString(Files.readString(single.target().resolve("assets/productivebees/lang/zh_cn.json"))).getAsJsonObject();
		assertEquals("保留的另一只蜜蜂", nativeNames.get(BeeDesignerDocument.translationKey("installcheck:other")).getAsString());
		assertEquals("单蜂更新后的名称", nativeNames.get(BeeDesignerDocument.translationKey("installcheck:datapack_gold")).getAsString());
		assertTrue(Files.exists(single.target().resolve("data/productivebeesgenesis/bee_designer/credits/installcheck/other.json")));
		assertFalse(Files.exists(single.target().resolve("data/productivebeesgenesis/bee_designer/index.json")));
		for (var type : PackType.values()) {
			var location = new PackLocationInfo("native-fixture", Component.literal("Native fixture"), PackSource.BUILT_IN, Optional.empty());
			var pack = Pack.readMetaAndCreate(location, new PathPackResources.PathResourcesSupplier(single.target()), type,
					new PackSelectionConfig(true, Pack.Position.TOP, true));
			assertNotNull(pack);
			assertEquals(PackCompatibility.COMPATIBLE, pack.getCompatibility());
			try (var resources = pack.open()) {
				String resource = type == PackType.SERVER_DATA ? "productivebees/datapack_gold.json" : "lang/zh_cn.json";
				String namespace = type == PackType.SERVER_DATA ? "installcheck" : "productivebees";
				assertNotNull(resources.getResource(type, ResourceLocation.fromNamespaceAndPath(namespace, resource)));
			}
		}
		JsonObject evidence = new JsonObject();
		evidence.addProperty("id", "installcheck:kube_gold");
		evidence.addProperty("recipe_id", recipe);
		evidence.add("files", new com.google.gson.Gson().toJsonTree(plan.files()));
		evidence.addProperty("datapack_id", "installcheck:datapack_gold");
		evidence.addProperty("datapack_recipe_id", datapackRecipe);
		evidence.add("datapack_files", new com.google.gson.Gson().toJsonTree(datapackFiles));
		System.out.println("KUBEJS_INSTALL_FIXTURE_EVIDENCE " + evidence);
	}

	@Test
	void namedExportsUsePreviewContentsAndRejectOverwrite(@TempDir Path directory) throws IOException {
		JsonObject bee = BeeDesignerDocument.create("pack:gold");
		BeeDesignerDocument.addRecipe(bee, "minecraft:crafting_shapeless");
		Map<String, String> documents = Map.of("pack:gold", bee.toString());
		String suggested = BeeDesignerExportName.suggest(documents.keySet(), "kubejs", LocalDateTime.of(2026, 11, 24, 12, 0));
		assertEquals("pbg-bees-pack-gold-kubejs-20261124-120000", suggested);
		assertEquals("pbg-bees-pack-nested-gold-kubejs-20261124-120000", BeeDesignerExportName.suggest(
				java.util.List.of("pack:nested/gold"), "kubejs", LocalDateTime.of(2026, 11, 24, 12, 0)));
		assertTrue(BeeDesignerExportName.suggest(java.util.List.of("pack:gold", "pack:iron"), "runtime_datapack",
				LocalDateTime.of(2026, 11, 24, 12, 0)).contains("2-bees-datapack-20261124-120000"));
		for (String invalid : java.util.List.of("", "../bee", "bee:name", "CON", "CON .txt", "LPT9.json", "trailing.", "a\nb\nc")) {
			assertThrows(IllegalArgumentException.class, () -> BeeDesignerExportName.validate(invalid));
		}
		for (String format : java.util.List.of("portable", "runtime_datapack", "runtime_kubejs")) {
			Map<String, String> preview = BeeDesignerArchive.exportFiles(documents, source("Pack"), format);
			String name = "我的金蜂配方-" + format;
			Path archive = BeeDesignerArchive.writeExport(directory, preview, format, name);
			assertEquals(name + ".zip", archive.getFileName().toString());
			try (ZipFile zip = new ZipFile(archive.toFile())) {
				assertEquals(preview.size(), zip.size());
				for (var file : preview.entrySet()) {
					assertEquals(file.getValue(), new String(zip.getInputStream(zip.getEntry(file.getKey())).readAllBytes(), StandardCharsets.UTF_8));
				}
			}
			byte[] before = Files.readAllBytes(archive);
			assertThrows(IOException.class, () -> BeeDesignerArchive.writeExport(directory, preview, format, name));
			assertArrayEquals(before, Files.readAllBytes(archive));
		}
	}

	@Test
	void craftingExportReusesKeysAndProducesReadableLosslessScriptEvidence() {
		JsonObject recipe = new JsonObject();
		recipe.addProperty("type", "minecraft:crafting_shaped");
		recipe.add("pattern", JsonParser.parseString("[\"ABC\"]"));
		recipe.add("key", JsonParser.parseString("{\"A\":{\"item\":\"minecraft:copper_ingot\"},\"B\":{\"tag\":\"c:ingots/gold\"},\"C\":{\"item\":\"minecraft:copper_ingot\"}}"));
		recipe.add("result", BeeDesignerEggs.output("pack:gold"));
		recipe.getAsJsonObject("result").addProperty("count", 1);
		recipe.addProperty("group", "authoring");
		recipe.addProperty("category", "misc");
		recipe.addProperty("show_notification", false);
		String original = recipe.toString();
		JsonObject compact = BeeDesignerCraftingExport.compact(recipe);
		assertEquals(JsonParser.parseString("[\"ABA\"]"), compact.get("pattern"));
		assertEquals(2, compact.getAsJsonObject("key").size());
		assertEquals(recipe.getAsJsonObject("key").get("A"), compact.getAsJsonObject("key").get("A"));
		assertEquals(original, recipe.toString());

		JsonObject complex = compact.deepCopy();
		complex.add("neoforge:conditions", JsonParser.parseString("[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"productivebees\"}]"));
		complex.add("__proto__", JsonParser.parseString("{\"must_remain_own_property\":true}"));
		complex.addProperty("author_extension", "literal $" + "{notExecutable} \u2028\u2029 " + Character.toString(96) + " slash\\end");
		JsonObject evidence = new JsonObject();
		evidence.addProperty("simple_script", BeeDesignerCraftingExport.script(recipe, "pack:simple"));
		evidence.add("simple_expected", compact);
		evidence.addProperty("complex_script", BeeDesignerCraftingExport.script(complex, "pack:complex"));
		evidence.add("complex_expected", complex);
		System.out.println("EXPORT_SCRIPT_EVIDENCE " + evidence);
	}

	@Test
	void releaseExportsKeepRuntimeNamesAndAliasesWithoutEditorSources(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("pack:old_bee");
		original.getAsJsonObject("names").addProperty("zh_cn", "发布测试蜂");
		BeeDesignerDocument.addRecipe(original, "minecraft:crafting_shapeless");
		original.addProperty("baseline", BeeDesignerDocument.fingerprint(original));
		JsonObject project = BeeDesignerIdentity.rename(original, "pack:renamed_bee");
		Map<String, String> documents = Map.of("pack:renamed_bee", project.toString());
		Map<String, String> scripts = BeeDesignerArchive.kubeJsFiles(documents, source("Release pack"), false);
		assertFalse(scripts.containsKey("kubejs/data/productivebeesgenesis/bee_designer/index.json"));
		assertTrue(scripts.containsKey("kubejs/data/pack/productivebees/old_bee.json"));
		assertTrue(scripts.containsKey("kubejs/data/pack/productivebees/renamed_bee.json"));
		assertTrue(scripts.get("kubejs/assets/productivebees/lang/zh_cn.json").contains("发布测试蜂"));
		assertEquals(1, scripts.keySet().stream().filter(path -> path.startsWith("kubejs/server_scripts/")).count());
		Path archive = BeeDesignerArchive.export(directory, documents, source("Release pack"), false);
		assertTrue(archive.getFileName().toString().startsWith("pbg-bees-release-"));
		assertFalse(BeeDesignerStore.managedPack("file/" + archive.getFileName()));
		try (ZipFile zip = new ZipFile(archive.toFile())) {
			assertNull(zip.getEntry("data/productivebeesgenesis/bee_designer/index.json"));
			assertNotNull(zip.getEntry("data/pack/productivebees/old_bee.json"));
			assertNotNull(zip.getEntry("data/pack/productivebees/renamed_bee.json"));
		}
		assertTrue(Files.exists(archive.resolveSibling(archive.getFileName().toString().replace(".zip", "-names.zip"))));
	}

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
	void oldIdentityConflictNamesBothCandidatesWhilePortableAndRemainingBeeStillExport() {
		JsonObject original = BeeDesignerDocument.create("custombees:resource_bee_copy");
		BeeDesignerDocument.addRecipe(original, "minecraft:crafting_shapeless");
		original.addProperty("baseline", BeeDesignerDocument.fingerprint(original));
		JsonObject first = BeeDesignerIdentity.rename(original, "custombees:i18nbee");
		JsonObject second = BeeDesignerIdentity.rename(original, "custombees:testi18bee");
		Map<String, String> conflicting = Map.of("custombees:i18nbee", first.toString(), "custombees:testi18bee", second.toString());
		assertNotNull(BeeDesignerArchive.exportFiles(conflicting, source("Pack"), "portable"));
		for (String format : java.util.List.of("datapack", "runtime_datapack", "kubejs", "runtime_kubejs")) {
			var failure = assertThrows(IllegalArgumentException.class, () -> BeeDesignerArchive.exportFiles(conflicting, source("Pack"), format));
			assertTrue(failure.getMessage().startsWith("designer.error.definition_collision|data/custombees/productivebees/resource_bee_copy.json|"));
			assertTrue(failure.getMessage().contains("custombees:i18nbee"));
			assertTrue(failure.getMessage().contains("custombees:testi18bee"));
			assertNotNull(BeeDesignerArchive.exportFiles(Map.of("custombees:i18nbee", first.toString()), source("Pack"), format));
		}
		assertTrue(first.has(BeeDesignerIdentity.ALIASES));
		assertTrue(second.has(BeeDesignerIdentity.ALIASES));
	}

	@Test
	void directInstallRecipesKeepStablePathsAcrossSelectionBatches() {
		JsonObject first = BeeDesignerDocument.create("pack:gold");
		JsonObject second = BeeDesignerDocument.create("pack:iron");
		String recipe = BeeDesignerDocument.addRecipe(first, "minecraft:crafting_shapeless");
		BeeDesignerDocument.addRecipe(second, "minecraft:crafting_shapeless");
		Map<String, String> alone = BeeDesignerArchive.exportFiles(Map.of("pack:gold", first.toString()), source("Pack"), "runtime_kubejs");
		Map<String, String> batch = BeeDesignerArchive.exportFiles(Map.of("pack:gold", first.toString(), "pack:iron", second.toString()), source("Pack"), "runtime_kubejs");
		ResourceLocation id = ResourceLocation.parse(recipe);
		String path = "kubejs/server_scripts/bee_designer/recipes/" + id.getNamespace() + "/" + id.getPath() + ".js";
		assertEquals(alone.get(path), batch.get(path));
		assertNotNull(alone.get(path));
		assertTrue(alone.get(path).contains(recipe));
		assertEquals(2, batch.keySet().stream().filter(value -> value.startsWith("kubejs/server_scripts/")).count());
		String credits = "kubejs/data/productivebeesgenesis/bee_designer/credits/pack/gold.json";
		assertTrue(alone.containsKey(credits));
		assertEquals(JsonParser.parseString(alone.get(credits)).getAsJsonObject().getAsJsonArray("bees"),
				JsonParser.parseString(batch.get(credits)).getAsJsonObject().getAsJsonArray("bees"));
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
