package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 只处理封闭的文件快照，工作线程不访问服务器、世界或注册表。 */
final class BeeDesignerStore {
	static final String PREFIX = "pbg-bee-designer-";
	static final int MAX_PACK_BYTES = 8 * 1024 * 1024;
	static final int MAX_FILES = 8192;

	private BeeDesignerStore() {
	}

	record Revision(Path world, Path source, String digest, boolean export) {
		Path installed() {
			return world.resolve("datapacks").resolve(source.getFileName());
		}

		String packId() {
			return "file/" + source.getFileName();
		}
	}

	static boolean managedPack(String id) {
		return id.matches("file/" + PREFIX + "[a-f0-9-]{36}\\.zip");
	}

	static void validateFiles(Map<String, String> files) {
		if (files.size() > MAX_FILES || !files.containsKey("pack.mcmeta")) {
			throw new IllegalArgumentException("Invalid managed pack file count or missing metadata");
		}
		long total = 0;
		for (var entry : files.entrySet()) {
			String path = entry.getKey();
			if ((!path.equals("pack.mcmeta") && !path.matches("(?:data|assets)/[a-z0-9_.-]+/[a-z0-9_./-]+\\.json"))
					|| path.contains("..") || path.contains("//")) {
				throw new IllegalArgumentException("Unsafe generated path: " + path);
			}
			total += entry.getValue().getBytes(StandardCharsets.UTF_8).length;
			if (total > MAX_PACK_BYTES) {
				throw new IllegalArgumentException("Managed data pack exceeds its size budget");
			}
		}
	}

	static Revision write(Path world, Map<String, String> files, boolean export) throws IOException {
		validateFiles(files);
		world = world.toAbsolutePath().normalize();
		String revision = PREFIX + UUID.randomUUID();
		Path directory = root(world).resolve(export ? "exports" : "revisions");
		checkParents(world, directory);
		Files.createDirectories(directory);
		Path zip = directory.resolve(revision + ".zip");
		writeZip(zip, files);
		if (export) {
			Map<String, String> names = new LinkedHashMap<>();
			files.forEach((path, value) -> {
				if (path.startsWith("assets/")) {
					names.put(path, value);
				}
			});
			names.put("pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"Bee Designer names\"}}");
			writeZip(directory.resolve(revision + "-names.zip"), names);
		}
		return new Revision(world, zip, digest(zip), export);
	}

	static void install(Revision revision) throws IOException {
		if (revision.export) {
			throw new IOException("An export cannot be installed as a revision");
		}
		verify(revision.world, revision.source, revision.digest);
		Path installed = revision.installed();
		checkParents(revision.world, installed);
		Files.createDirectories(installed.getParent());
		record(revision, "pending", "Installation started; activation has not been confirmed");
		Path temporary = installed.resolveSibling(installed.getFileName() + ".pending");
		if (!Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
			Files.copy(revision.source, temporary);
		}
		verify(revision.world, temporary, revision.digest);
		// 非 ZIP 暂存文件不会被资源包扫描器识别；不降级成可暴露半包的复制。
		Files.move(temporary, installed, StandardCopyOption.ATOMIC_MOVE);
	}

	static void complete(Revision revision, List<String> previous) throws IOException {
		verify(revision.world, revision.installed(), revision.digest);
		Path archive = root(revision.world).resolve("archive");
		checkParents(revision.world, archive);
		Files.createDirectories(archive);
		for (String id : previous) {
			if (!managedPack(id) || id.equals(revision.packId())) {
				continue;
			}
			Path old = revision.installed().resolveSibling(id.substring("file/".length()));
			if (!Files.exists(old, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			Path retained = root(revision.world).resolve("revisions").resolve(old.getFileName());
			// 只归档有本工具源修订且摘要一致的文件，不能凭文件名前缀移动其它包。
			verify(revision.world, retained, digestChecked(revision.world, old));
			Path destination = archive.resolve(old.getFileName());
			checkParents(revision.world, destination);
			Files.move(old, destination, StandardCopyOption.ATOMIC_MOVE);
		}
		record(revision, "committed", "Activation and archive completed");
	}

	static void reject(Revision revision, boolean uncertain, String message) throws IOException {
		// 先写结果未知记录，即使文件被重载器占用、隔离失败，重连也不能误认为成功。
		record(revision, "uncertain", message);
		Path installed = revision.installed();
		if (Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
			verify(revision.world, installed, revision.digest);
			Path quarantine = root(revision.world).resolve("quarantine");
			checkParents(revision.world, quarantine);
			Files.createDirectories(quarantine);
			Files.move(installed, quarantine.resolve(installed.getFileName()), StandardCopyOption.ATOMIC_MOVE);
		}
		record(revision, uncertain ? "uncertain" : "rejected", message);
	}

	static JsonObject inspect(Path world) throws IOException {
		Path path = root(world).resolve("operation.json");
		checkParents(world, path);
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			JsonObject idle = new JsonObject();
			idle.addProperty("phase", "idle");
			return idle;
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > BeeDesignerProtocol.MAX_BYTES) {
			throw new IOException("Invalid authoring operation journal");
		}
		return BeeDesignerProtocol.parse(Files.readString(path, StandardCharsets.UTF_8));
	}

	static JsonObject history(Path world, int requested) throws IOException {
		Path directory = root(world).resolve("revisions");
		checkParents(world, directory);
		List<Path> revisions;
		if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			try (var stream = Files.list(directory)) {
				revisions = stream.filter(path -> managedPack("file/" + path.getFileName()))
						.sorted(java.util.Comparator.comparing(path -> path.getFileName().toString())).toList();
			}
		} else {
			revisions = List.of();
		}
		int pages = Math.max(1, (revisions.size() + 15) / 16);
		int page = Math.max(0, Math.min(requested, pages - 1));
		JsonArray entries = new JsonArray();
		for (Path path : revisions.subList(Math.min(page * 16, revisions.size()), Math.min((page + 1) * 16, revisions.size()))) {
			checkParents(world, path);
			BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (!attributes.isRegularFile()) {
				throw new IOException("Revision history contains a linked or non-file entry");
			}
			JsonObject entry = new JsonObject();
			entry.addProperty("revision", path.getFileName().toString());
			entry.addProperty("modified", attributes.lastModifiedTime().toString());
			entry.addProperty("bytes", attributes.size());
			String state = "retained";
			if (Files.exists(world.resolve("datapacks").resolve(path.getFileName()), LinkOption.NOFOLLOW_LINKS)) {
				state = "active";
			} else if (Files.exists(root(world).resolve("quarantine").resolve(path.getFileName()), LinkOption.NOFOLLOW_LINKS)) {
				state = "quarantine";
			}
			entry.addProperty("state", state);
			entries.add(entry);
		}
		JsonObject result = new JsonObject();
		result.addProperty("page", page);
		result.addProperty("pages", pages);
		result.addProperty("total", revisions.size());
		result.add("entries", entries);
		return result;
	}

	static Map<String, String> readRevision(Path world, String name) throws IOException {
		if (!managedPack("file/" + name)) {
			throw new IOException("Invalid revision identity");
		}
		Path source = root(world).resolve("revisions").resolve(name);
		checkParents(world, source);
		if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.size(source) > MAX_PACK_BYTES * 2L) {
			throw new IOException("Revision source is missing or exceeds its budget");
		}
		return BeeDesignerArchive.read(source);
	}

	record Audit(String journal, Revision pending, Revision active, Map<String, String> documents, List<String> installed) {
	}

	static Audit audit(Path world, List<String> selected) throws IOException {
		JsonObject journal = inspect(world);
		Revision pending = null;
		if (journal.has("revision")) {
			String name = journal.get("revision").getAsString();
			String checksum = journal.get("sha256").getAsString();
			if (!managedPack("file/" + name) || !checksum.matches("[a-f0-9]{64}")) {
				throw new IOException("Invalid revision journal identity or digest");
			}
			pending = new Revision(world, root(world).resolve("revisions").resolve(name), checksum, false);
			verify(world, pending.source, checksum);
		}
		List<String> managed = selected.stream().filter(BeeDesignerStore::managedPack).toList();
		if (managed.size() > 1) {
			throw new IOException("More than one managed pack is selected; keep the journal for inspection");
		}
		Revision active = null;
		Map<String, String> documents = Map.of();
		if (!managed.isEmpty()) {
			String name = managed.getFirst().substring("file/".length());
			Path source = root(world).resolve("revisions").resolve(name);
			active = new Revision(world, source, digestChecked(world, source), false);
			Path installed = active.installed();
			if (!Files.exists(installed, LinkOption.NOFOLLOW_LINKS)) {
				if (pending == null || !active.packId().equals(pending.packId())) {
					throw new IOException("Selected revision is missing from datapacks");
				}
				verify(world, root(world).resolve("quarantine").resolve(name), active.digest);
			} else {
				verify(world, installed, active.digest);
			}
			documents = readRevision(world, name);
		}
		List<String> installed = new java.util.ArrayList<>();
		Path directory = world.resolve("datapacks");
		checkParents(world, directory);
		if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
			try (var paths = Files.list(directory)) {
				for (Path path : paths.filter(value -> managedPack("file/" + value.getFileName())).toList()) {
					Path source = root(world).resolve("revisions").resolve(path.getFileName());
					if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
						verify(world, source, digestChecked(world, path));
						installed.add("file/" + path.getFileName());
					}
				}
			}
		}
		return new Audit(journal.toString(), pending, active, documents, List.copyOf(installed));
	}

	static void reconcile(Path world, Audit audit) throws IOException {
		if (!inspect(world).toString().equals(audit.journal)) {
			throw new IOException("Publication journal changed during verification");
		}
		if (audit.pending != null && (audit.active == null || !audit.pending.packId().equals(audit.active.packId()))) {
			reject(audit.pending, false, "Verified current resources use another revision");
		}
		if (audit.active != null) {
			if (!Files.exists(audit.active.installed(), LinkOption.NOFOLLOW_LINKS)) {
				install(audit.active);
			}
			complete(audit.active, audit.installed);
		}
	}

	private static void record(Revision revision, String phase, String message) throws IOException {
		JsonObject data = new JsonObject();
		data.addProperty("revision", revision.source.getFileName().toString());
		data.addProperty("sha256", revision.digest);
		data.addProperty("phase", phase);
		data.addProperty("message", message);
		Path path = root(revision.world).resolve("operation.json");
		checkParents(revision.world, path);
		Path temporary = path.resolveSibling("operation-" + UUID.randomUUID() + ".pending");
		Files.writeString(temporary, data.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void writeZip(Path path, Map<String, String> files) throws IOException {
		try (ZipOutputStream stream = new ZipOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW))) {
			for (var entry : files.entrySet()) {
				stream.putNextEntry(new ZipEntry(entry.getKey()));
				stream.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				stream.closeEntry();
			}
		}
	}

	private static Path root(Path world) {
		return world.resolve("productivebeesgenesis/bee_designer");
	}

	private static String digestChecked(Path world, Path path) throws IOException {
		checkParents(world, path);
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			throw new IOException("Revision is not a regular file: " + path);
		}
		return digest(path);
	}

	private static void verify(Path world, Path path, String expected) throws IOException {
		if (!digestChecked(world, path).equals(expected)) {
			throw new IOException("Revision digest mismatch: " + path);
		}
	}

	private static String digest(Path path) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(path)) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = input.read(buffer)) != -1) {
					digest.update(buffer, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static void checkParents(Path root, Path target) throws IOException {
		Path absoluteRoot = root.toAbsolutePath().normalize();
		Path absoluteTarget = target.toAbsolutePath().normalize();
		if (!absoluteTarget.startsWith(absoluteRoot)) {
			throw new IOException("Path outside current world");
		}
		for (Path cursor = absoluteTarget; cursor != null; cursor = cursor.getParent()) {
			if (Files.isSymbolicLink(cursor) || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
					&& Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
				throw new IOException("Linked authoring directory is not writable: " + cursor);
			}
		}
	}
}
