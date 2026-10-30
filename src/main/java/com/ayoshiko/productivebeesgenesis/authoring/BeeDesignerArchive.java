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
		if (path.getFileName().toString().endsWith(".zip")) {
			root = readZip(path);
		} else if (path.getFileName().toString().endsWith(".json")) {
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
			if (root.size() > 128) {
				throw new IOException("Project contains more than 128 bees");
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
		// 总工程可大于单蜂网络包；在解析前逐字符检查深度，避免深嵌套输入递归。
		BeeDesignerProtocol.checkDepth(raw);
		return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
	}

	private static void add(Map<String, String> result, JsonObject document) {
		BeeDesignerCompiler.validateStructure(document);
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

	public static Path export(Path directory, Map<String, String> documents, JsonObject credits) throws IOException {
		Map<String, String> files = new LinkedHashMap<>(BeeDesignerCompiler.files(BeeDesignerCollection.sources(documents, credits)));
		files.put("data/productivebeesgenesis/bee_designer/credits.json", creditsFile(documents, credits));
		return BeeDesignerStore.write(directory, files, true).source();
	}

	private static String creditsFile(Map<String, String> documents, JsonObject credits) {
		return BeeDesignerCollection.files(documents, credits).get("bee-collection.json");
	}

	public static Map<String, String> kubeJsFiles(JsonObject document) {
		JsonObject source = document.deepCopy();
		if (!source.has("definition_path")) {
			source.add("definition_path", source.get("id"));
		}
		return kubeJsFiles(Map.of(source.get("id").getAsString(), source.toString()), BeeDesignerCredits.empty());
	}

	public static Map<String, String> kubeJsFiles(Map<String, String> documents, JsonObject credits) {
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
			String[] parts = path.split("/", 3);
			if (path.startsWith("data/") && parts.length == 3 && parts[2].startsWith("recipe/")) {
				String id = parts[1] + ":" + parts[2].substring("recipe/".length(), parts[2].length() - 5);
				String quoted = new JsonPrimitive(id).toString();
				script.append("  event.remove({id: ").append(quoted).append("});\n");
				if (activeRecipes.contains(id) && !entry.getValue().contains("\"neoforge:false\"")) {
					String json = new JsonPrimitive(entry.getValue()).toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
					script.append("  event.custom(JSON.parse(").append(json).append(")).id(").append(quoted).append(");\n");
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
		result.put("kubejs/server_scripts/bee_designer/" + scriptPath + ".js", script.toString());
		result.put("kubejs/data/productivebeesgenesis/bee_designer/credits.json", creditsFile(documents, credits));
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
		Map<String, String> files = kubeJsFiles(documents, credits);
		Path output = directory.toAbsolutePath().normalize().resolve("kubejs-exports");
		Path current = output;
		while (current != null) {
			if (Files.isSymbolicLink(current)) {
				throw new IOException("Linked export directory");
			}
			current = current.getParent();
		}
		Files.createDirectories(output);
		Path archive = output.resolve("bee-designer-kubejs-" + UUID.randomUUID() + ".zip");
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
