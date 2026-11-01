package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本机草稿按蜂种身份隔离；坏文件保留原文，未确认成功的写入不能关闭编辑器。 */
public final class BeeDesignerDrafts {
	public static final int MAX_DRAFTS = BeeDesignerCollection.MAX_BEES;

	private BeeDesignerDrafts() {
	}

	public record Entry(String id, Map<String, String> names, long modified, String problem, boolean pending, boolean deleted, boolean purged) {
		public Entry(String id, Map<String, String> names, long modified, String problem, boolean pending) {
			this(id, names, modified, problem, pending, false, false);
		}

		public Entry(String id, Map<String, String> names, long modified, String problem, boolean pending, boolean deleted) {
			this(id, names, modified, problem, pending, deleted, false);
		}
	}

	public record Archived(String token, Entry entry) {
	}

	public static void save(Path directory, JsonObject document) throws IOException {
		BeeDesignerCompiler.validateStructure(document);
		String id = document.get("id").getAsString();
		Path path = path(directory, id);
		if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			JsonObject previous = read(directory, id);
			if (BeeDesignerLifecycle.deleted(document) && !BeeDesignerLifecycle.deleted(previous)) {
				archive(directory, id);
			}
		} else if (paths(directory).size() >= MAX_DRAFTS) {
			throw new IOException("designer.error.draft_limit");
		}
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + "." + UUID.randomUUID() + ".pending");
		Files.writeString(temporary, document.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	public static JsonObject read(Path directory, String id) throws IOException {
		JsonObject document = readFile(path(directory, id));
		if (!document.get("id").getAsString().equals(id)) {
			throw new IOException("Draft identity differs from its file; original data is retained");
		}
		return document;
	}

	/** 导入不覆盖旧草稿；保管失败时保留原件并中止导入。坏原文也按字节归档。 */
	public static Path preserveBeforeImport(Path directory, JsonObject incoming) throws IOException {
		BeeDesignerCompiler.validateStructure(incoming);
		String id = incoming.get("id").getAsString();
		Path existing = path(directory, id);
		if (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		try {
			if (read(directory, id).equals(incoming)) {
				return null;
			}
		} catch (IOException | RuntimeException invalid) {
			// 无效旧草稿仍有作者原文，archive 按原字节保管，不解析或丢弃。
		}
		return archive(directory, id);
	}

	public static JsonObject latest(Path directory) throws IOException {
		List<Entry> entries = list(directory);
		if (!entries.isEmpty()) {
			return read(directory, entries.get(0).id);
		}
		Path legacy = directory.resolve("draft.json");
		checkPath(directory, legacy);
		return readFile(legacy);
	}

	public static List<Entry> list(Path directory) throws IOException {
		List<Entry> result = new ArrayList<>();
		for (Path file : paths(directory)) {
			Path relative = directory.resolve("drafts").relativize(file);
			String id = relative.getName(0) + ":" + file.getFileName().toString().replaceFirst("\\.json$", "");
			long modified = 0;
			try {
				checkPath(directory, file);
				modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
				JsonObject document = read(directory, id);
				Map<String, String> names = new LinkedHashMap<>();
				document.getAsJsonObject("names").entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsString()));
				boolean pending = document.has("_editor_recovery") || !BeeDesignerDocument.fingerprint(document).equals(BeeDesignerDocument.text(document, "baseline", "new"));
				result.add(new Entry(id, Map.copyOf(names), modified, "", pending, BeeDesignerLifecycle.deleted(document), BeeDesignerLifecycle.purged(document)));
			} catch (IOException | RuntimeException failure) {
				String problem = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
				result.add(new Entry(id, Map.of(), modified, problem, false));
			}
		}
		result.sort(Comparator.comparingLong(Entry::modified).reversed().thenComparing(Entry::id));
		return List.copyOf(result);
	}

	public static Path archive(Path directory, String id) throws IOException {
		Path source = path(directory, id);
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Draft is not a regular file");
		}
		if (archives(directory).size() >= 1024) {
			throw new IOException("designer.error.recycle_limit");
		}
		Path target = directory.resolve("drafts-archive").resolve(UUID.randomUUID() + ".json");
		checkPath(directory, target);
		Files.createDirectories(target.getParent());
		Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
		return target;
	}

	public static List<Archived> archives(Path directory) throws IOException {
		Path root = directory.resolve("drafts-archive");
		checkPath(directory, root);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}
		List<Archived> result = new ArrayList<>();
		try (var files = Files.list(root)) {
			for (Path file : files.filter(path -> path.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).limit(1025).toList()) {
				if (result.size() >= 1024) {
					throw new IOException("designer.error.recycle_limit");
				}
				checkPath(directory, file);
				long modified = Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toMillis();
				String token = file.getFileName().toString();
				try {
					JsonObject document = archived(directory, token);
					Map<String, String> names = new LinkedHashMap<>();
					document.getAsJsonObject("names").entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsString()));
					Entry entry = new Entry(document.get("id").getAsString(), Map.copyOf(names), modified, "", true, BeeDesignerLifecycle.deleted(document), BeeDesignerLifecycle.purged(document));
					result.add(new Archived(token, entry));
				} catch (IOException | RuntimeException failure) {
					String problem = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
					result.add(new Archived(token, new Entry(token, Map.of(), modified, problem, false)));
				}
			}
		}
		result.sort(Comparator.comparingLong((Archived archived) -> archived.entry.modified).reversed().thenComparing(Archived::token));
		return List.copyOf(result);
	}

	public static JsonObject archived(Path directory, String token) throws IOException {
		return readFile(archivePath(directory, token));
	}

	public static void deleteArchive(Path directory, String token, long modified) throws IOException {
		Path source = archivePath(directory, token);
		deleteFile(source, modified);
	}

	public static void discard(Path directory, String id, long modified) throws IOException {
		deleteFile(path(directory, id), modified);
	}

	private static void deleteFile(Path source, long modified) throws IOException {
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
				|| Files.getLastModifiedTime(source, LinkOption.NOFOLLOW_LINKS).toMillis() != modified) {
			throw new IOException("designer.error.recycle_changed");
		}
		Files.delete(source);
	}

	public static JsonObject restoreArchive(Path directory, String token) throws IOException {
		Path source = archivePath(directory, token);
		JsonObject document = archived(directory, token);
		Path target = path(directory, document.get("id").getAsString());
		if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("designer.error.draft_restore_conflict");
		}
		if (paths(directory).size() >= MAX_DRAFTS) {
			throw new IOException("designer.error.draft_limit");
		}
		Files.createDirectories(target.getParent());
		Files.move(source, target);
		return document;
	}

	private static Path archivePath(Path directory, String token) throws IOException {
		if (!token.matches("[a-f0-9-]{36}\\.json")) {
			throw new IOException("designer.error.recycle_identity");
		}
		UUID.fromString(token.substring(0, 36));
		Path path = directory.resolve("drafts-archive").resolve(token);
		checkPath(directory, path);
		return path;
	}

	private static List<Path> paths(Path directory) throws IOException {
		Path root = directory.resolve("drafts");
		checkPath(directory, root);
		if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			return List.of();
		}
		try (var paths = Files.walk(root, 2)) {
			List<Path> result = paths.filter(path -> root.relativize(path).getNameCount() == 2
					&& path.getFileName().toString().endsWith(".json")).limit(MAX_DRAFTS + 1).toList();
			if (result.size() > MAX_DRAFTS) {
				throw new IOException("designer.error.draft_limit");
			}
			return result;
		}
	}

	private static JsonObject readFile(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > BeeDesignerProtocol.MAX_BYTES) {
			throw new IOException("Draft is missing, linked or exceeds its size budget");
		}
		JsonObject document = BeeDesignerProtocol.parse(Files.readString(path, StandardCharsets.UTF_8));
		BeeDesignerCompiler.validateStructure(document);
		return document;
	}

	private static Path path(Path directory, String id) throws IOException {
		ResourceLocation bee = new ResourceLocation(id);
		if (!bee.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IOException("Invalid draft identity");
		}
		Path path = directory.resolve("drafts").resolve(bee.getNamespace()).resolve(bee.getPath() + ".json");
		checkPath(directory, path);
		return path;
	}

	private static void checkPath(Path directory, Path path) throws IOException {
		if (!path.toAbsolutePath().normalize().startsWith(directory.toAbsolutePath().normalize())) {
			throw new IOException("Draft path escapes its workspace");
		}
		for (Path cursor = path.toAbsolutePath().normalize(); cursor != null; cursor = cursor.getParent()) {
			if (Files.isSymbolicLink(cursor) || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
					&& Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
				throw new IOException("Linked draft path is not writable");
			}
		}
	}
}
