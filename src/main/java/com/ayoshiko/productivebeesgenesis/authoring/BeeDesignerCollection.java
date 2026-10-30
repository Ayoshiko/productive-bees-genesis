package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 便携收藏仅保管工程，不复制模组资产，也不会被 Minecraft 当作生效数据包。 */
public final class BeeDesignerCollection {

	public static final int MAX_BEES = 128;

	private BeeDesignerCollection() {
	}

	public static Map<String, String> sources(Map<String, String> documents, JsonObject credits) {
		if (documents.isEmpty() || documents.size() > MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		Map<String, String> result = new LinkedHashMap<>();
		for (var entry : documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			JsonObject source = BeeDesignerProtocol.parse(entry.getValue());
			if (!entry.getKey().equals(BeeDesignerDocument.text(source, "id", ""))) {
				throw new IllegalArgumentException("designer.error.collection_identity");
			}
			if (entry.getKey().equals(com.ayoshiko.productivebeesgenesis.util.PBConstants.MYRIADCREATIONS_TYPE_STRING)
					&& !BeeDesignerMyriad.recipesOnly(source)) {
				throw new IllegalArgumentException("designer.error.collection_myriad");
			}
			if (BeeDesignerMyriad.recipesOnly(source)) {
				BeeDesignerMyriad.validateRecipes(source, source);
			}
			if (!source.has("definition_path")) {
				source.add("definition_path", source.get("id").deepCopy());
			}
			BeeDesignerCompiler.validateStructure(source);
			result.put(entry.getKey(), BeeDesignerCredits.attach(source, credits).toString());
		}
		return Map.copyOf(result);
	}

	public static Map<String, String> files(Map<String, String> documents, JsonObject credits) {
		Map<String, String> sources = sources(documents, credits);
		JsonObject index = new JsonObject();
		JsonObject manifest = new JsonObject();
		manifest.addProperty("format", "productivebeesgenesis:bee_collection");
		manifest.addProperty("version", 1);
		manifest.addProperty("exported_at", Instant.now().toString());
		manifest.addProperty("bee_count", sources.size());
		manifest.add("export_source", credits.deepCopy());
		JsonArray bees = new JsonArray();
		StringBuilder text = new StringBuilder("Portable bee collection / 便携蜂种收藏包 / Collection portable d'abeilles\n\n");
		text.append("Respect the original authors' licenses. Attribution is not permission to redistribute.\n");
		text.append("请遵守原整合包及模组作者的许可；保留署名不代表已经取得转载授权。\n");
		text.append("Respectez les licences des auteurs ; une attribution ne vaut pas autorisation de redistribution.\n\n");
		text.append("Import this ZIP in the bee editor. It is not an installable datapack. Mods, models, textures and scripts are not bundled.\n\n");
		for (var entry : sources.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			JsonObject document = BeeDesignerProtocol.parse(entry.getValue());
			index.add(entry.getKey(), document);
			JsonObject bee = new JsonObject();
			bee.addProperty("id", entry.getKey());
			bee.add("names", document.get("names").deepCopy());
			if (document.has("_editor_origin")) {
				bee.add("original_resource", document.get("_editor_origin").deepCopy());
			}
			if (document.has(BeeDesignerCredits.FIELD)) {
				bee.add("credits", document.get(BeeDesignerCredits.FIELD).deepCopy());
			}
			JsonArray namespaces = new JsonArray();
			BeeDesignerCredits.namespaces(document).stream().sorted().forEach(namespaces::add);
			bee.add("content_namespaces", namespaces);
			bees.add(bee);
			text.append(entry.getKey()).append('\n');
			if (document.has("_editor_origin")) {
				text.append("Resource source: ").append(BeeDesignerDocument.text(document.getAsJsonObject("_editor_origin"), "source_pack", "unknown")).append('\n');
				String copied = BeeDesignerDocument.text(document.getAsJsonObject("_editor_origin"), "copied_source_pack", "");
				if (!copied.isBlank()) {
					text.append("Copied from: ").append(copied).append('\n');
				}
			}
			if (document.has(BeeDesignerCredits.FIELD)) {
				for (var source : document.getAsJsonArray(BeeDesignerCredits.FIELD)) {
					for (String field : BeeDesignerCredits.FIELDS) {
						String value = BeeDesignerDocument.text(source.getAsJsonObject(), field, "");
						if (!value.isBlank()) {
							text.append(field).append(": ").append(value).append('\n');
						}
					}
				}
			}
			text.append("Content namespaces: ").append(namespaces).append("\n\n");
		}
		manifest.add("bees", bees);
		Map<String, String> result = new LinkedHashMap<>();
		result.put("data/productivebeesgenesis/bee_designer/index.json", index.toString());
		result.put("bee-collection.json", new GsonBuilder().setPrettyPrinting().create().toJson(manifest));
		result.put("CREDITS.txt", text.toString());
		long bytes = result.values().stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
		if (bytes > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IllegalArgumentException("designer.error.export_budget");
		}
		return Map.copyOf(result);
	}

	public static Path export(Path workspace, Map<String, String> documents, JsonObject credits) throws IOException {
		Map<String, String> files = files(documents, credits);
		Path directory = workspace.toAbsolutePath().normalize().resolve("collections");
		for (Path parent = directory; parent != null; parent = parent.getParent()) {
			if (Files.isSymbolicLink(parent)) {
				throw new IOException("designer.error.file_folder");
			}
		}
		Files.createDirectories(directory);
		Path output = directory.resolve("bee-collection-" + UUID.randomUUID() + ".zip");
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output, StandardOpenOption.CREATE_NEW), StandardCharsets.UTF_8)) {
			for (var entry : files.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return output;
	}
}
