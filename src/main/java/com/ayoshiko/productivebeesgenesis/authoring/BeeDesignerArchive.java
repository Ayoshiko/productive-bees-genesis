package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipFile;

/** 只导入本工具的工程源；ZIP 全条目计数和展开预算不依赖压缩后的文件大小。 */
public final class BeeDesignerArchive {
	private BeeDesignerArchive() {
	}

	public static Map<String, String> read(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
			throw new IOException("Project must be a regular file");
		}
		JsonObject root;
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".zip")) {
			root = readZip(path);
		} else if (name.endsWith(".json")) {
			if (Files.size(path) > BeeDesignerStore.MAX_PACK_BYTES) {
				throw new IOException("Project exceeds the import budget");
			}
			root = parseIndex(Files.readString(path, StandardCharsets.UTF_8));
		} else {
			throw new IOException("Choose an exported data ZIP or project JSON");
		}
		Map<String, String> result = new LinkedHashMap<>();
		if (root.has("schema_version")) {
			add(result, root);
		} else {
			if (root.size() > BeeDesignerCollection.MAX_BEES) {
				throw new IOException("designer.error.collection_count");
			}
			for (var value : root.entrySet()) {
				JsonObject document = value.getValue().getAsJsonObject();
				if (!value.getKey().equals(document.get("id").getAsString())) {
					throw new IOException("Project index identity mismatch");
				}
				add(result, document);
			}
		}
		if (result.isEmpty()) {
			throw new IOException("No editable bee projects were found");
		}
		return Map.copyOf(result);
	}

	private static JsonObject readZip(Path path) throws IOException {
		JsonObject index = null;
		long bytes = 0;
		int count = 0;
		HashSet<String> paths = new HashSet<>();
		try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				String name = entry.getName();
				if (++count > BeeDesignerStore.MAX_FILES || name.startsWith("/") || name.contains("..") || name.contains(":")
						|| name.contains("\\") || !paths.add(name)) {
					throw new IOException("Unsafe or excessive ZIP entries");
				}
				try (InputStream input = zip.getInputStream(entry)) {
					int remaining = (int) (BeeDesignerStore.MAX_PACK_BYTES - bytes);
					byte[] data = input.readNBytes(remaining + 1);
					bytes += data.length;
					if (bytes > BeeDesignerStore.MAX_PACK_BYTES) {
						throw new IOException("ZIP exceeds the expanded import budget");
					}
					if (name.equals("data/productivebeesgenesis/bee_designer/index.json")
							|| name.equals("kubejs/data/productivebeesgenesis/bee_designer/index.json")) {
						index = parseIndex(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString());
					}
				}
			}
		}
		if (index == null) {
			throw new IOException("The ZIP does not contain Bee Designer project sources");
		}
		return index;
	}

	private static JsonObject parseIndex(String raw) {
		// 索引根对象额外占一层；解析后每蜂仍由 validateStructure 限制为 32 层和 28000 字节。
		BeeDesignerProtocol.checkDepth(raw, BeeDesignerProtocol.MAX_DEPTH + 1);
		return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
	}

	private static void add(Map<String, String> result, JsonObject document) {
		BeeDesignerCompiler.validateImportedStructure(document);
		BeeDesignerLegacyOutputs.capture(document);
		String id = document.get("id").getAsString();
		if (result.putIfAbsent(id, document.toString()) != null) {
			throw new IllegalArgumentException("Duplicate imported bee ID: " + id);
		}
	}

	public static Path export(Path directory, JsonObject document) throws IOException {
		Files.createDirectories(directory);
		var source = document.deepCopy();
		if (!source.has("definition_path")) {
			source.add("definition_path", source.get("id"));
		}
		Map<String, String> files = BeeDesignerCompiler.files(Map.of(source.get("id").getAsString(), source.toString()));
		return BeeDesignerStore.write(directory, files, true).source();
	}

	public static Path export(Path directory, JsonObject document, String name) throws IOException {
		JsonObject source = document.deepCopy();
		if (!source.has("definition_path")) {
			source.add("definition_path", source.get("id"));
		}
		Map<String, String> files = BeeDesignerCompiler.files(Map.of(source.get("id").getAsString(), source.toString()));
		return BeeDesignerStore.writeExport(directory, files, name).source();
	}

	public static Path export(Path directory, Map<String, String> documents, JsonObject credits) throws IOException {
		return export(directory, documents, credits, true);
	}

	public static Path export(Path directory, Map<String, String> documents, JsonObject credits, boolean includeProject) throws IOException {
		Map<String, String> files = dataFiles(documents, credits, includeProject);
		return (includeProject ? BeeDesignerStore.write(directory, files, true) : BeeDesignerStore.writeRelease(directory, files)).source();
	}

	public static Map<String, String> dataFiles(Map<String, String> documents, JsonObject credits, boolean includeProject) {
		Map<String, String> files = new LinkedHashMap<>(BeeDesignerCompiler.files(BeeDesignerCollection.sources(documents, credits)));
		if (!includeProject) {
			files.remove("data/productivebeesgenesis/bee_designer/index.json");
		}
		files.put("data/productivebeesgenesis/bee_designer/credits.json", creditsFile(documents, credits, includeProject));
		return Map.copyOf(files);
	}

	public static Path writeExport(Path directory, Map<String, String> files, String format, String name) throws IOException {
		if (format.equals("datapack") || format.equals("runtime_datapack")) {
			return BeeDesignerStore.writeExport(directory, files, name).source();
		}
		Path output = directory.toAbsolutePath().normalize().resolve(format.equals("portable") ? "collections" : "kubejs-exports");
		return writeArchive(output, files, name);
	}

	public static Map<String, String> exportFiles(Map<String, String> documents, JsonObject credits, String format) {
		Map<String, String> files = switch (format) {
			case "portable" -> BeeDesignerCollection.files(documents, credits);
			case "datapack" -> dataFiles(documents, credits, true);
			case "runtime_datapack" -> dataFiles(documents, credits, false);
			case "install_datapack", "install_world_datapack" -> installedDataFiles(documents, credits);
			case "kubejs" -> kubeJsFiles(documents, credits, true);
			case "runtime_kubejs", "install_kubejs" -> kubeJsFiles(documents, credits, false);
			default -> throw new IllegalArgumentException("designer.error.collection_format");
		};
		if (format.endsWith("datapack")) {
			BeeDesignerStore.validateFiles(files);
		}
		return Map.copyOf(files);
	}

	private static Map<String, String> installedDataFiles(Map<String, String> documents, JsonObject credits) {
		Map<String, String> files = new LinkedHashMap<>(dataFiles(documents, credits, false));
		for (var entry : documents.entrySet()) {
			ResourceLocation bee = ResourceLocation.parse(entry.getKey());
			files.put("data/productivebeesgenesis/bee_designer/credits/" + bee.getNamespace() + "/" + bee.getPath() + ".json",
					creditsFile(Map.of(entry.getKey(), entry.getValue()), credits, false));
		}
		return Map.copyOf(files);
	}

	private static boolean disabledRecipe(String raw) {
		JsonObject recipe = BeeDesignerProtocol.parse(raw);
		var conditions = recipe.get("neoforge:conditions");
		if (conditions == null || !conditions.isJsonArray()) {
			return false;
		}
		for (var condition : conditions.getAsJsonArray()) {
			if (condition.isJsonObject() && "neoforge:false".equals(
					BeeDesignerDocument.text(condition.getAsJsonObject(), "type", ""))) {
				return true;
			}
		}
		return false;
	}

	private static String creditsFile(Map<String, String> documents, JsonObject credits) {
		return BeeDesignerCollection.files(documents, credits).get("bee-collection.json");
	}

	private static String creditsFile(Map<String, String> documents, JsonObject credits, boolean includeProject) {
		String raw = creditsFile(documents, credits);
		if (includeProject) {
			return raw;
		}
		JsonObject manifest = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
		manifest.getAsJsonArray("bees").forEach(value -> value.getAsJsonObject().remove("original_resource"));
		return manifest.toString();
	}

	public static Map<String, String> kubeJsFiles(JsonObject document) {
		JsonObject source = document.deepCopy();
		if (!source.has("definition_path")) {
			source.add("definition_path", source.get("id"));
		}
		return kubeJsFiles(Map.of(source.get("id").getAsString(), source.toString()), BeeDesignerCredits.empty());
	}

	public static Map<String, String> kubeJsFiles(Map<String, String> documents, JsonObject credits) {
		return kubeJsFiles(documents, credits, true);
	}

	public static Map<String, String> kubeJsFiles(Map<String, String> documents, JsonObject credits, boolean includeProject) {
		Map<String, String> sources = BeeDesignerCollection.sources(documents, credits);
		Map<String, String> compiled = BeeDesignerCompiler.files(sources);
		HashSet<String> activeRecipes = new HashSet<>();
		for (String raw : sources.values()) {
			activeRecipes.addAll(BeeDesignerProtocol.parse(raw).getAsJsonObject("recipes").keySet());
		}
		Map<String, String> result = new LinkedHashMap<>();
		StringBuilder script = new StringBuilder("// Generated by Bee Designer. Install this bundle instead of the exported datapack.\nServerEvents.recipes(event => {\n");
		compiled.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
			String path = entry.getKey();
			if (!includeProject && path.equals("data/productivebeesgenesis/bee_designer/index.json")) {
				return;
			}
			String[] parts = path.split("/", 3);
			if (path.startsWith("data/") && parts.length == 3 && parts[2].startsWith("recipe/")) {
				String id = parts[1] + ":" + parts[2].substring("recipe/".length(), parts[2].length() - 5);
				String quoted = new JsonPrimitive(id).toString();
				StringBuilder recipeScript = includeProject ? script : new StringBuilder(
						"// Generated by Bee Designer.\nServerEvents.recipes(event => {\n");
				recipeScript.append("  event.remove({id: ").append(quoted).append("});\n");
				if (activeRecipes.contains(id) && !disabledRecipe(entry.getValue())) {
					recipeScript.append(BeeDesignerCraftingExport.script(BeeDesignerProtocol.parse(entry.getValue()), id));
				}
				if (!includeProject) {
					recipeScript.append("});\n");
					String recipePath = parts[2].substring("recipe/".length(), parts[2].length() - 5);
					result.put("kubejs/server_scripts/bee_designer/recipes/" + parts[1] + "/" + recipePath + ".js", recipeScript.toString());
				}
			} else if (path.startsWith("data/") || path.startsWith("assets/")) {
				result.put("kubejs/" + path, entry.getValue());
			}
		});
		script.append("});\n");
		String identities = sources.keySet().stream().sorted().collect(java.util.stream.Collectors.joining("\n"));
		String scriptPath = "collection-" + UUID.nameUUIDFromBytes(identities.getBytes(StandardCharsets.UTF_8));
		if (sources.size() == 1) {
			ResourceLocation bee = ResourceLocation.parse(sources.keySet().iterator().next());
			scriptPath = bee.getNamespace() + "/" + bee.getPath();
		}
		if (includeProject) {
			result.put("kubejs/server_scripts/bee_designer/" + scriptPath + ".js", script.toString());
		}
		result.put("kubejs/data/productivebeesgenesis/bee_designer/credits.json", creditsFile(documents, credits, includeProject));
		if (!includeProject) {
			for (var entry : documents.entrySet()) {
				ResourceLocation bee = ResourceLocation.parse(entry.getKey());
				result.put("kubejs/data/productivebeesgenesis/bee_designer/credits/" + bee.getNamespace() + "/" + bee.getPath() + ".json",
						creditsFile(Map.of(entry.getKey(), entry.getValue()), credits, false));
			}
		}
		long bytes = result.values().stream().mapToLong(value -> value.getBytes(StandardCharsets.UTF_8).length).sum();
		if (bytes > BeeDesignerStore.MAX_PACK_BYTES || result.size() > BeeDesignerStore.MAX_FILES) {
			throw new IllegalArgumentException("designer.error.export_budget");
		}
		return Map.copyOf(result);
	}

	public static Path exportKubeJs(Path directory, JsonObject document) throws IOException {
		return exportKubeJs(directory, Map.of(document.get("id").getAsString(), document.toString()), BeeDesignerCredits.empty());
	}

	public static Path exportKubeJs(Path directory, Map<String, String> documents, JsonObject credits) throws IOException {
		return exportKubeJs(directory, documents, credits, true);
	}

	public static Path exportKubeJs(Path directory, Map<String, String> documents, JsonObject credits, boolean includeProject) throws IOException {
		Map<String, String> files = kubeJsFiles(documents, credits, includeProject);
		Path output = directory.toAbsolutePath().normalize().resolve("kubejs-exports");
		return writeArchive(output, files, BeeDesignerExportName.suggest(documents.keySet(), includeProject ? "kubejs" : "runtime_kubejs"));
	}

	private static Path writeArchive(Path output, Map<String, String> files, String name) throws IOException {
		BeeDesignerExportName.validate(name);
		Path current = output;
		while (current != null) {
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Linked export directory");
			}
			current = current.getParent();
		}
		Files.createDirectories(output);
		Path archive = output.resolve(name + ".zip");
		if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("designer.error.export_exists");
		}
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW))) {
			for (var entry : files.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return archive;
	}
}
