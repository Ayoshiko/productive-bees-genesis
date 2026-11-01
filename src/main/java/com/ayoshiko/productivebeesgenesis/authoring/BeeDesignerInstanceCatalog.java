package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** 离线定义与实际注册快照分别标记；读取其它实例不执行其脚本或触碰世界。 */
public final class BeeDesignerInstanceCatalog {
	public record Catalog(Path instance, Map<String, String> documents, Map<String, String> origins,
			List<String> registered, List<String> problems, boolean runtimeSnapshot, String capturedAt) {
		public Catalog {
			documents = Map.copyOf(documents);
			origins = Map.copyOf(origins);
			registered = List.copyOf(registered);
			problems = List.copyOf(problems);
		}
	}

	private static final String CACHE = "bee-designer/registered-projects.json";
	private static final int MAX_RESOURCES = 32768;
	private static final long MAX_SCAN_BYTES = 64L * 1024 * 1024;

	private BeeDesignerInstanceCatalog() {
	}

	public static List<Path> instances(Path directory) throws IOException {
		Path root = directory.toAbsolutePath().normalize();
		checkPath(root);
		try (var paths = Files.list(root)) {
			List<Path> instances = paths.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> Files.isDirectory(path.resolve("mods"), LinkOption.NOFOLLOW_LINKS)
							|| Files.isDirectory(path.resolve("kubejs"), LinkOption.NOFOLLOW_LINKS))
					.sorted().limit(129).toList();
			if (instances.size() > 128) {
				throw new IOException("designer.error.instance_budget");
			}
			return instances;
		}
	}

	public static Catalog read(Path instance) throws IOException {
		Path root = instance.toAbsolutePath().normalize();
		checkPath(root);
		Map<String, String> resources = new LinkedHashMap<>();
		Map<String, String> packs = new LinkedHashMap<>();
		List<String> problems = new ArrayList<>();
		long[] bytes = {0};
		Path mods = root.resolve("mods");
		if (Files.isDirectory(mods, LinkOption.NOFOLLOW_LINKS)) {
			checkPath(mods);
			try (var paths = Files.list(mods)) {
				List<Path> jars = paths.filter(file -> file.getFileName().toString().endsWith(".jar")).sorted().limit(1025).toList();
				if (jars.size() > 1024) {
					throw new IOException("designer.error.instance_budget");
				}
				for (Path path : jars) {
					readZip(path, "mod/" + modId(path), resources, packs, bytes);
				}
			}
		}
		readTree(root.resolve("kubejs/data"), "kubejs", resources, packs, bytes);
		for (String folder : List.of("datapacks", "moonlight-global-datapacks", "global_packs/required_data",
				"openloader/data", "paxi/datapacks")) {
			Path directory = root.resolve(folder);
			if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			checkPath(directory);
			try (var paths = Files.list(directory)) {
				List<Path> candidates = paths.sorted().limit(129).toList();
				if (candidates.size() > 128) {
					throw new IOException("designer.error.instance_budget");
				}
				for (Path pack : candidates) {
					String source = "file/" + folder + "/" + pack.getFileName();
					if (Files.isDirectory(pack, LinkOption.NOFOLLOW_LINKS)) {
						readTree(pack.resolve("data"), source, resources, packs, bytes);
					} else if (pack.getFileName().toString().endsWith(".zip")) {
						readZip(pack, source, resources, packs, bytes);
					}
				}
			}
		}
		Map<String, JsonObject> documents = new LinkedHashMap<>();
		Set<String> ambiguous = new HashSet<>();
		Map<String, String> origins = new LinkedHashMap<>();
		for (var entry : resources.entrySet()) {
			String[] path = entry.getKey().split("/", 4);
			if (path.length != 4 || !path[0].equals("data") || !path[2].equals("productivebees")) {
				continue;
			}
			String relative = path[3].substring(0, path[3].length() - 5);
			String id = path[1] + ":" + relative.substring(relative.lastIndexOf('/') + 1);
			try {
				JsonObject document = BeeDesignerDocument.create(id);
				document.addProperty("definition_path", path[1] + ":" + relative);
				document.add("definition", JsonParser.parseString(entry.getValue()).getAsJsonObject());
				String source = packs.get(entry.getKey());
				JsonObject origin = origin(source);
				document.add("_editor_origin", origin);
				if (documents.putIfAbsent(id, document) != null) {
					problems.add("designer.error.definition_ambiguous|" + id);
					ambiguous.add(id);
				}
				origins.put(id, origin.toString());
			} catch (RuntimeException invalid) {
				problems.add(entry.getKey() + ": " + invalid.getMessage());
			}
			if (documents.size() > BeeDesignerCollection.MAX_BEES) {
				throw new IOException("designer.error.collection_count");
			}
		}
		ambiguous.forEach(documents::remove);
		for (var entry : resources.entrySet()) {
			String[] path = entry.getKey().split("/", 4);
			if (path.length != 4 || !path[0].equals("data") || !path[2].equals("recipes")) {
				continue;
			}
			try {
				JsonObject recipe = com.ayoshiko.productivebeesgenesis.port.ForgeJsonRecipes.toPortable(JsonParser.parseString(entry.getValue()).getAsJsonObject());
				if (!recipe.has("type") || !BeeDesignerCompiler.RECIPE_TYPES.contains(recipe.get("type").getAsString())
						&& BeeDesignerMachineRecipes.host(recipe) == null) {
					continue;
				}
				String id = path[1] + ":" + path[3].substring(0, path[3].length() - 5);
				for (var document : documents.entrySet()) {
					if (BeeDesignerReader.belongs(recipe, document.getKey())) {
						document.getValue().getAsJsonObject("recipes").add(id, recipe.deepCopy());
					}
				}
			} catch (RuntimeException invalid) {
				problems.add(entry.getKey() + ": " + invalid.getMessage());
			}
		}
		addNames(resources, documents);
		Map<String, String> result = new LinkedHashMap<>();
		for (var entry : documents.entrySet()) {
			try {
				BeeDesignerCompiler.validateStructure(entry.getValue());
				BeeDesignerProtocol.checkSize(entry.getValue().toString());
				result.put(entry.getKey(), entry.getValue().toString());
			} catch (RuntimeException invalid) {
				problems.add(entry.getKey() + ": " + invalid.getMessage());
			}
		}
		List<String> registered = new ArrayList<>();
		boolean runtime = false;
		String capturedAt = "";
		Path cache = root.resolve(CACHE);
		if (Files.isRegularFile(cache, LinkOption.NOFOLLOW_LINKS)) {
			checkPath(cache);
			JsonObject snapshot = readJson(cache, 8 * 1024 * 1024);
			if (!snapshot.has("format") || !snapshot.get("format").getAsString().equals("productivebeesgenesis:registered_catalog")) {
				throw new IOException("designer.error.instance_snapshot");
			}
			capturedAt = BeeDesignerDocument.text(snapshot, "captured_at", "");
			if (snapshot.getAsJsonArray("registered").size() > BeeDesignerCollection.MAX_BEES
					|| snapshot.getAsJsonObject("projects").size() > BeeDesignerCollection.MAX_BEES) {
				throw new IOException("designer.error.instance_budget");
			}
			for (var value : snapshot.getAsJsonArray("registered")) {
				registered.add(new ResourceLocation(value.getAsString()).toString());
			}
			for (var entry : snapshot.getAsJsonObject("projects").entrySet()) {
				JsonObject document = entry.getValue().getAsJsonObject();
				BeeDesignerCompiler.validateStructure(document);
				BeeDesignerProtocol.checkSize(document.toString());
				if (!entry.getKey().equals(document.get("id").getAsString()) || !registered.contains(entry.getKey())) {
					throw new IOException("designer.error.instance_snapshot");
				}
				JsonObject merged = document.deepCopy();
				String offline = result.get(entry.getKey());
				if (offline != null) {
					JsonObject names = JsonParser.parseString(offline).getAsJsonObject().getAsJsonObject("names");
					names.entrySet().forEach(name -> {
						if (!merged.getAsJsonObject("names").has(name.getKey())) {
							merged.getAsJsonObject("names").add(name.getKey(), name.getValue().deepCopy());
						}
					});
				}
				result.put(entry.getKey(), merged.toString());
				if (document.has("_editor_origin")) {
					origins.put(entry.getKey(), document.get("_editor_origin").toString());
				}
			}
			runtime = true;
		}
		if (result.size() > BeeDesignerCollection.MAX_BEES || new HashSet<>(registered).size() != registered.size()) {
			throw new IOException("designer.error.instance_budget");
		}
		return new Catalog(root, result, origins, registered, problems, runtime, capturedAt);
	}

	private static JsonObject origin(String source) {
		JsonObject origin = new JsonObject();
		String kind;
		if (source.equals("mod/productivebees")) {
			kind = "pb";
		} else if (source.equals("mod/productivebeesgenesis")) {
			kind = "genesis";
		} else {
			kind = source.startsWith("mod/") ? "mod" : "pack";
		}
		origin.addProperty("kind", kind);
		origin.addProperty("source_pack", source);
		origin.addProperty("edited", false);
		return origin;
	}

	private static void addNames(Map<String, String> resources, Map<String, JsonObject> documents) {
		for (var entry : resources.entrySet()) {
			String[] path = entry.getKey().split("/", 4);
			if (path.length != 4 || !path[0].equals("assets") || !path[2].equals("lang")) {
				continue;
			}
			JsonObject names = JsonParser.parseString(entry.getValue()).getAsJsonObject();
			String locale = path[3].substring(0, path[3].length() - 5);
			documents.forEach((id, document) -> {
				String key = BeeDesignerDocument.translationKey(id);
				if (names.has(key) && names.get(key).isJsonPrimitive() && names.getAsJsonPrimitive(key).isString()) {
					document.getAsJsonObject("names").add(locale, names.get(key).deepCopy());
				}
			});
		}
	}

	private static boolean relevant(String path) {
		return path.matches("data/[a-z0-9_.-]+/(productivebees|recipe)/.+\\.json")
				|| path.matches("assets/[a-z0-9_.-]+/lang/[a-z]{2,3}_[a-z0-9]{2,8}\\.json");
	}

	private static void readTree(Path data, String source, Map<String, String> resources, Map<String, String> packs, long[] bytes) throws IOException {
		if (!Files.isDirectory(data, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		checkPath(data);
		try (var paths = Files.walk(data)) {
			List<Path> files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.limit(MAX_RESOURCES + 1L).sorted().toList();
			if (files.size() > MAX_RESOURCES) {
				throw new IOException("designer.error.instance_budget");
			}
			for (Path file : files) {
				String path = "data/" + data.relativize(file).toString().replace('\\', '/');
				if (relevant(path)) {
					checkPath(file);
					add(path, readResource(file), source, resources, packs, bytes);
				}
			}
		}
		Path assets = data.resolveSibling("assets");
		if (Files.isDirectory(assets, LinkOption.NOFOLLOW_LINKS)) {
			checkPath(assets);
			try (var paths = Files.walk(assets, 3)) {
				List<Path> files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
						.limit(MAX_RESOURCES + 1L).sorted().toList();
				if (files.size() > MAX_RESOURCES) {
					throw new IOException("designer.error.instance_budget");
				}
				for (Path file : files) {
					String path = "assets/" + assets.relativize(file).toString().replace('\\', '/');
					if (relevant(path)) {
						checkPath(file);
						add(path, readResource(file), source, resources, packs, bytes);
					}
				}
			}
		}
	}

	private static byte[] readResource(Path file) throws IOException {
		if (Files.size(file) > 2 * 1024 * 1024) {
			throw new IOException("designer.error.instance_budget");
		}
		return Files.readAllBytes(file);
	}

	private static void readZip(Path file, String source, Map<String, String> resources, Map<String, String> packs, long[] bytes) throws IOException {
		checkPath(file);
		try (ZipFile zip = new ZipFile(file.toFile())) {
			if (source.startsWith("mod/") && zip.stream().noneMatch(entry -> entry.getName().matches("data/[a-z0-9_.-]+/productivebees/.+\\.json"))) {
				return;
			}
			var entries = zip.entries();
			while (entries.hasMoreElements()) {
				var entry = entries.nextElement();
				if (!entry.isDirectory() && relevant(entry.getName())) {
					try (var input = zip.getInputStream(entry)) {
						byte[] data = input.readNBytes(2 * 1024 * 1024 + 1);
						add(entry.getName(), data, source, resources, packs, bytes);
					}
				}
			}
		}
	}

	private static void add(String path, byte[] data, String source, Map<String, String> resources, Map<String, String> packs, long[] bytes) throws IOException {
		bytes[0] += data.length;
		if (data.length > 2 * 1024 * 1024 || bytes[0] > MAX_SCAN_BYTES || resources.size() >= MAX_RESOURCES) {
			throw new IOException("designer.error.instance_budget");
		}
		String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(data)).toString();
		if (path.contains("/lang/")) {
			JsonObject names = JsonParser.parseString(text).getAsJsonObject();
			JsonObject beeNames = new JsonObject();
			names.entrySet().stream().filter(entry -> entry.getKey().startsWith("entity.productivebees."))
					.forEach(entry -> beeNames.add(entry.getKey(), entry.getValue()));
			if ((beeNames.size()==0)) {
				return;
			}
			text = beeNames.toString();
		} else if (path.contains("/recipes/")) {
			JsonObject recipe = JsonParser.parseString(text).getAsJsonObject();
			String type = BeeDesignerDocument.text(recipe, "type", "");
			if (!BeeDesignerCompiler.RECIPE_TYPES.contains(type) && BeeDesignerMachineRecipes.host(recipe) == null) {
				return;
			}
			if (!type.startsWith("productivebees") && !text.contains("productivebees:") && !text.contains("minecraft:bee")) {
				return;
			}
		}
		resources.put(path, text);
		packs.put(path, source);
	}

	private static String modId(Path file) throws IOException {
		checkPath(file);
		try (ZipFile zip = new ZipFile(file.toFile())) {
			var entry = zip.getEntry("META-INF/mods.toml");
			if (entry == null) {
				entry = zip.getEntry("META-INF/mods.toml");
			}
			if (entry != null) {
				try (var input = zip.getInputStream(entry)) {
					String text = new String(input.readNBytes(65537), StandardCharsets.UTF_8);
					var matcher = java.util.regex.Pattern.compile("(?m)^\\s*modId\\s*=\\s*[\"']([a-z0-9_.-]+)[\"']").matcher(text);
					if (matcher.find()) {
						return matcher.group(1);
					}
				}
			}
		}
		return "unknown";
	}

	public static void saveRuntime(Path instance, Map<String, String> projects, List<String> registered, long generation) throws IOException {
		Path path = instance.toAbsolutePath().normalize().resolve(CACHE);
		checkPath(path);
		JsonObject snapshot = new JsonObject();
		snapshot.addProperty("format", "productivebeesgenesis:registered_catalog");
		snapshot.addProperty("generation", generation);
		snapshot.addProperty("captured_at", java.time.Instant.now().toString());
		JsonArray ids = new JsonArray();
		registered.forEach(ids::add);
		snapshot.add("registered", ids);
		JsonObject documents = new JsonObject();
		for (var entry : projects.entrySet()) {
			JsonObject document = BeeDesignerProtocol.parse(entry.getValue());
			if (!entry.getKey().equals(document.get("id").getAsString())) {
				throw new IOException("designer.error.instance_snapshot");
			}
			documents.add(entry.getKey(), document);
		}
		snapshot.add("projects", documents);
		String text = snapshot.toString();
		if (text.getBytes(StandardCharsets.UTF_8).length > 8 * 1024 * 1024 || registered.size() > BeeDesignerCollection.MAX_BEES
				|| projects.size() > BeeDesignerCollection.MAX_BEES || new HashSet<>(registered).size() != registered.size()
				|| !new HashSet<>(registered).containsAll(projects.keySet())) {
			throw new IOException("designer.error.instance_budget");
		}
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + "." + java.util.UUID.randomUUID() + ".pending");
		Files.writeString(temporary, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		Files.move(temporary, path, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	private static JsonObject readJson(Path path, long maximum) throws IOException {
		if (Files.size(path) > maximum) {
			throw new IOException("designer.error.instance_budget");
		}
		return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
	}

	private static void checkPath(Path path) throws IOException {
		for (Path parent = path; parent != null; parent = parent.getParent()) {
			if (Files.isSymbolicLink(parent)) {
				throw new IOException("designer.error.file_folder");
			}
		}
	}

}
