package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.CompositePackResourcesAccessor;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;

/** 来源来自实际获胜的资源包；首次编辑后保管原来源，避免覆盖后全部被误标成新蜂种。 */
public final class BeeDesignerOrigins {

	private BeeDesignerOrigins() {
	}

	public static JsonObject describe(ResourceLocation bee) {
		String managed = BeeDesignerSources.managed.get(bee.toString());
		JsonObject previous = managed == null ? null : JsonParser.parseString(managed).getAsJsonObject();
		JsonObject origin;
		if (previous != null && previous.has("_editor_origin") && previous.get("_editor_origin").isJsonObject()) {
			origin = previous.getAsJsonObject("_editor_origin").deepCopy();
		} else {
			origin = new JsonObject();
			String pack = "";
			if (BeeDesignerNativeBees.contains(bee)) {
				pack = "mod/" + bee.getNamespace();
			} else {
				try {
					pack = BeeDesignerSources.sourcePacks.getOrDefault(BeeDesignerReader.definitionPath(bee), "");
				} catch (IllegalArgumentException missing) {
					// 未应用的新草稿没有资源路径；来源尚未确定，不猜为 PB 原版。
				}
			}
			String kind;
			if (managed != null) {
				kind = "editor";
			} else if (pack.equals("mod/productivebees")) {
				kind = "pb";
			} else if (pack.equals("mod/productivebeesgenesis")) {
				kind = "genesis";
			} else if (pack.startsWith("mod/")) {
				kind = "mod";
			} else {
				kind = pack.isBlank() ? "unknown" : "pack";
			}
			origin.addProperty("kind", kind);
			origin.addProperty("source_pack", pack);
		}
		origin.addProperty("edited", managed != null);
		origin.addProperty("deleted", BeeDesignerLifecycle.deleted(bee));
		if (previous != null && BeeDesignerLifecycle.deleted(previous)) {
			origin.add("deleted_at", previous.getAsJsonObject(BeeDesignerLifecycle.FIELD).get("deleted_at").deepCopy());
		}
		return origin;
	}

	/** 页面中的 file 已经后台核对；压缩包保留真实归档文件和内部项，不解压世界资源。 */
	public record FileSource(String kind, String resource, String sourcePack, Path file,
			String archiveEntry, int line, String note) {
	}

	public record Candidate(FileSource source, String requiredKey) {
	}

	public record ScriptSource(Path file, String location) {
	}

	public record ScriptFiles(List<ScriptSource> scripts, boolean limited) {
	}

	public record Snapshot(List<Candidate> candidates, List<ScriptSource> scripts, Set<String> references,
			long generation, boolean limited) {
	}

	private static final int MAX_FILES = 192;
	static final int MAX_SCRIPT_FILES = 256;
	private static final int MAX_SCRIPT_BYTES = 2 * 1024 * 1024;
	private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;

	/** 服务器线程只捕获已知资源和 KubeJS 已收集脚本的元数据，不遍历实例或玩家世界。 */
	public static Snapshot snapshot(MinecraftServer server, JsonObject document, List<String> locales) {
		List<Candidate> candidates = new ArrayList<>();
		Set<String> references = new LinkedHashSet<>();
		String id = document.get("id").getAsString();
		references.add(id);
		references.add(BeeDesignerDocument.translationKey(id));
		ResourceLocation definition = ResourceLocation.parse(BeeDesignerDocument.text(document, "definition_path", id));
		if (document.has(BeeDesignerIdentity.MIGRATION)) {
			JsonObject migration = document.getAsJsonObject(BeeDesignerIdentity.MIGRATION);
			id = migration.get("from").getAsString();
			definition = ResourceLocation.parse(migration.get("definition_path").getAsString());
			references.add(id);
			references.add(BeeDesignerDocument.translationKey(id));
		}
		ResourceManager manager = server.getResourceManager();
		addResource(candidates, manager, definition.withPath("productivebees/" + definition.getPath() + ".json"),
				"definition", "", locales, references);
		if (document.has(BeeDesignerIdentity.ALIASES)) {
			for (var alias : document.getAsJsonObject(BeeDesignerIdentity.ALIASES).entrySet()) {
				ResourceLocation path = ResourceLocation.parse(alias.getKey());
				references.add(alias.getValue().getAsString());
				references.add(BeeDesignerDocument.translationKey(alias.getValue().getAsString()));
				addResource(candidates, manager, path.withPath("productivebees/" + path.getPath() + ".json"),
						"compatibility", "", locales, references);
			}
		}
		for (var recipe : document.getAsJsonObject("recipes").entrySet()) {
			ResourceLocation path = ResourceLocation.parse(recipe.getKey());
			references.add(recipe.getKey());
			addResource(candidates, manager, path.withPath("recipe/" + path.getPath() + ".json"),
					"recipe", "", List.of(), references);
		}
		if (BeeDesignerSources.managed.containsKey(id)) {
			addResource(candidates, manager, ResourceLocation.parse("productivebeesgenesis:bee_designer/index.json"),
					"editor_document", id, List.of(), references);
		}
		boolean kubejs = ModList.get().isLoaded("kubejs");
		ScriptFiles scripts = kubejs ? BeeDesignerKubeJsOrigins.scripts(server) : new ScriptFiles(List.of(), false);
		if (kubejs) {
			BeeDesignerKubeJsOrigins.markedScripts(document, scripts.scripts()).forEach(candidate -> add(candidates, candidate));
		}
		return new Snapshot(List.copyOf(candidates), scripts.scripts(), Set.copyOf(references),
				BeeDesignerSources.generation, scripts.limited());
	}

	/** 客户端仅枚举所选蜂的相关语言资源栈，后台再核对文件确实包含对应键。 */
	public static List<Candidate> languages(ResourceManager manager, JsonObject document, List<String> locales) {
		List<Candidate> candidates = new ArrayList<>();
		Set<String> namespaces = new LinkedHashSet<>(List.of("productivebees", "productivebeesgenesis",
				ResourceLocation.parse(document.get("id").getAsString()).getNamespace()));
		Set<String> keys = translationKeys(document);
		for (String namespace : namespaces) {
			for (String locale : locales) {
				ResourceLocation resource = ResourceLocation.fromNamespaceAndPath(namespace, "lang/" + locale + ".json");
				for (var source : manager.getResourceStack(resource)) {
					for (String key : keys) {
						add(candidates, new Candidate(fileSource(source.source(), PackType.CLIENT_RESOURCES, resource,
								"language"), key));
					}
				}
			}
		}
		return List.copyOf(candidates);
	}

	public static Snapshot remote(JsonObject document) {
		List<Candidate> candidates = new ArrayList<>();
		String id = document.get("id").getAsString();
		ResourceLocation definition = ResourceLocation.parse(BeeDesignerDocument.text(document, "definition_path", id));
		candidates.add(new Candidate(new FileSource("definition", definition.withPath("productivebees/"
				+ definition.getPath() + ".json").toString(), "", null, "", 0, "origin_remote"), ""));
		for (String recipe : document.getAsJsonObject("recipes").keySet()) {
			ResourceLocation path = ResourceLocation.parse(recipe);
			candidates.add(new Candidate(new FileSource("recipe", path.withPath("recipe/" + path.getPath()
					+ ".json").toString(), "", null, "", 0, "origin_remote"), ""));
		}
		return new Snapshot(List.copyOf(candidates), List.of(), Set.of(), -1, false);
	}

	private static Set<String> translationKeys(JsonObject document) {
		Set<String> keys = new LinkedHashSet<>();
		keys.add(BeeDesignerDocument.translationKey(document.get("id").getAsString()));
		if (document.has(BeeDesignerIdentity.MIGRATION)) {
			keys.add(BeeDesignerDocument.translationKey(document.getAsJsonObject(BeeDesignerIdentity.MIGRATION)
					.get("from").getAsString()));
		}
		if (document.has(BeeDesignerIdentity.ALIASES)) {
			document.getAsJsonObject(BeeDesignerIdentity.ALIASES).entrySet().forEach(entry ->
					keys.add(BeeDesignerDocument.translationKey(entry.getValue().getAsString())));
		}
		return keys;
	}

	private static void addResource(List<Candidate> candidates, ResourceManager manager, ResourceLocation resource,
			String kind, String requiredKey, List<String> locales, Set<String> references) {
		var current = manager.getResource(resource);
		if (current.isEmpty()) {
			add(candidates, new Candidate(new FileSource(kind, resource.toString(), "", null, "", 0,
					"origin_resource_missing"), requiredKey));
			return;
		}
		PackResources pack = leaf(current.get().source(), PackType.SERVER_DATA, resource);
		add(candidates, new Candidate(fileSource(pack, PackType.SERVER_DATA, resource, kind), requiredKey));
		if (locales.isEmpty()) {
			return;
		}
		for (String locale : locales) {
			ResourceLocation language = ResourceLocation.fromNamespaceAndPath("productivebees", "lang/" + locale + ".json");
			for (String key : references) {
				if (key.startsWith("entity.productivebees.")) {
					add(candidates, new Candidate(fileSource(pack, PackType.CLIENT_RESOURCES, language, "language"), key));
				}
			}
		}
	}

	private static void add(List<Candidate> candidates, Candidate candidate) {
		if (candidates.size() < MAX_FILES && !candidates.contains(candidate)) {
			candidates.add(candidate);
		}
	}

	private static PackResources leaf(PackResources pack, PackType type, ResourceLocation resource) {
		if (pack instanceof CompositePackResourcesAccessor composite) {
			for (PackResources child : composite.productivebeesgenesis$packResourcesStack()) {
				if (child.getResource(type, resource) != null) {
					return leaf(child, type, resource);
				}
			}
		}
		return pack;
	}

	private static FileSource fileSource(PackResources source, PackType type, ResourceLocation resource, String kind) {
		PackResources pack = leaf(source, type, resource);
		String relative = type.getDirectory() + "/" + resource.getNamespace() + "/" + resource.getPath();
		try {
			if (pack instanceof PathPackResources) {
				Path root = (Path) field(PathPackResources.class, pack, "root");
				if (root.getFileSystem().equals(FileSystems.getDefault())) {
					return new FileSource(kind, resource.toString(), pack.packId(), root.resolve(relative), "", 0, "");
				}
				return new FileSource(kind, resource.toString(), pack.packId(), null, "", 0, "origin_pack_internal");
			}
			if (pack instanceof FilePackResources) {
				Object access = field(FilePackResources.class, pack, "zipFileAccess");
				Path zip = ((File) field(access.getClass(), access, "file")).toPath();
				String prefix = (String) field(FilePackResources.class, pack, "prefix");
				String entry = prefix.isEmpty() ? relative : prefix + "/" + relative;
				return new FileSource(kind, resource.toString(), pack.packId(), zip, entry, 0, "");
			}
			if (ModList.get().isLoaded("kubejs")) {
				Path file = BeeDesignerKubeJsOrigins.resourceFile(pack, type, resource);
				if (file != null) {
					return new FileSource(kind, resource.toString(), pack.packId(), file, "", 0, "");
				}
				if (BeeDesignerKubeJsOrigins.generated(pack)) {
					return new FileSource(kind, resource.toString(), pack.packId(), null, "", 0, "origin_generated");
				}
			}
			return new FileSource(kind, resource.toString(), pack.packId(), null, "", 0, "origin_pack_internal");
		} catch (ReflectiveOperationException failure) {
			throw new IllegalStateException("Cannot locate resource pack " + pack.packId(), failure);
		}
	}

	private static Object field(Class<?> owner, Object target, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	/** 后台只读取封闭快照中的已知文件；一个任务复用归档句柄并有总字节预算。 */
	public static List<FileSource> locate(Snapshot snapshot, List<Candidate> clientLanguages) throws IOException {
		Map<String, FileSource> found = new LinkedHashMap<>();
		Map<Path, ZipFile> archives = new HashMap<>();
		Map<String, JsonObject> parsedFiles = new HashMap<>();
		List<Candidate> candidates = new ArrayList<>(snapshot.candidates());
		candidates.addAll(clientLanguages);
		int remaining = MAX_JSON_BYTES;
		Throwable firstFailure = null;
		try {
			for (Candidate candidate : candidates) {
				FileSource source = candidate.source();
				if (source.file() == null) {
					if (candidate.requiredKey().isEmpty()) {
						put(found, source);
					}
					continue;
				}
				Path file = source.file().toAbsolutePath().normalize();
				if (!Files.isRegularFile(file)) {
					if (candidate.requiredKey().isEmpty()) {
						put(found, unavailable(source, "origin_file_missing"));
					}
					continue;
				}
				// 链接只读其已解析的真实目标，页面明确展示目标路径，绝不改链接或写资源。
				file = file.toRealPath();
				InputStream input = null;
				if (!source.archiveEntry().isEmpty()) {
					ZipFile archive = archives.get(file);
					if (archive == null) {
						archive = new ZipFile(file.toFile());
						archives.put(file, archive);
					}
					var entry = archive.getEntry(source.archiveEntry());
					if (entry == null) {
						if (candidate.requiredKey().isEmpty()) {
							put(found, unavailable(source, "origin_file_missing"));
						}
						continue;
					}
					if (!candidate.requiredKey().isEmpty()) {
						input = archive.getInputStream(entry);
					}
				} else if (!candidate.requiredKey().isEmpty()) {
					input = Files.newInputStream(file);
				}
				if (input != null) {
					String location = file + "!" + source.archiveEntry();
					JsonObject json = parsedFiles.get(location);
					try (InputStream content = input) {
						if (json == null) {
							byte[] bytes = content.readNBytes(remaining + 1);
							if (bytes.length > remaining) {
								put(found, new FileSource("notice", "", "", null, "", 0, "origin_budget"));
								break;
							}
							remaining -= bytes.length;
							json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
							parsedFiles.put(location, json);
						}
					}
					if (!json.has(candidate.requiredKey())) {
						continue;
					}
				}
				put(found, new FileSource(source.kind(), source.resource(), source.sourcePack(), file,
						source.archiveEntry(), source.line(), source.note()));
			}
			int scriptRemaining = MAX_SCRIPT_BYTES;
			for (ScriptSource script : snapshot.scripts()) {
				if (!Files.isRegularFile(script.file())) {
					continue;
				}
				Path file = script.file().toRealPath();
				String[] lines;
				// KubeJS 成功执行后清空 ScriptFile.lines，只能沿其保留的真实 Path 在后台读取。
				try (InputStream content = Files.newInputStream(file)) {
					byte[] bytes = content.readNBytes(scriptRemaining + 1);
					if (bytes.length > scriptRemaining) {
						put(found, new FileSource("notice", "", "", null, "", 0, "origin_budget"));
						break;
					}
					scriptRemaining -= bytes.length;
					lines = new String(bytes, StandardCharsets.UTF_8).split("\\r\\n|\\r|\\n", -1);
				}
				int matched = -1;
				for (int line = 0; line < lines.length && matched < 0; line++) {
					for (String reference : snapshot.references()) {
						if (lines[line].contains(reference)) {
							matched = line;
							break;
						}
					}
				}
				if (matched >= 0) {
					put(found, new FileSource("kube_script", script.location(), "kubejs",
							file, "", matched + 1, "origin_script_reference"));
				}
			}
			if (snapshot.limited()) {
				put(found, new FileSource("notice", "", "", null, "", 0, "origin_budget"));
			}
			if (snapshot.candidates().size() == MAX_FILES || clientLanguages.size() == MAX_FILES) {
				put(found, new FileSource("notice", "", "", null, "", 0, "origin_budget"));
			}
			return List.copyOf(found.values());
		} catch (IOException | RuntimeException failure) {
			firstFailure = failure;
			throw failure;
		} finally {
			IOException closeFailure = null;
			for (ZipFile archive : archives.values()) {
				try {
					archive.close();
				} catch (IOException failure) {
					if (closeFailure == null) {
						closeFailure = failure;
					} else {
						closeFailure.addSuppressed(failure);
					}
				}
			}
			if (closeFailure != null) {
				if (firstFailure == null) {
					throw closeFailure;
				}
				firstFailure.addSuppressed(closeFailure);
			}
		}
	}

	private static FileSource unavailable(FileSource source, String note) {
		return new FileSource(source.kind(), source.resource(), source.sourcePack(), null, source.archiveEntry(),
				source.line(), note);
	}

	private static void put(Map<String, FileSource> found, FileSource source) {
		found.putIfAbsent(source.kind() + "\n" + source.resource() + "\n" + source.file() + "\n"
				+ source.archiveEntry(), source);
	}
}
