package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 预览保持只读；安装只提交封闭文件计划，失败时保管备份并明确报告回滚结果。 */
public final class BeeDesignerInstallation {
	public enum Operation {
		CREATE,
		REPLACE,
		UNCHANGED
	}

	public record FileChange(String path, Operation operation, String existingSha256, long bytes) {}

	public record Plan(Path instance, Path target, Path backupDirectory, Map<String, String> files,
			List<FileChange> changes, int createCount, int replaceCount, int unchangedCount) {
		public Plan {
			files = Map.copyOf(files);
			changes = List.copyOf(changes);
		}
	}

	private record Existing(String text, String digest, long bytes) {}

	private BeeDesignerInstallation() {
	}

	public static Plan prepareKubeJs(Path instance, Map<String, String> generated) throws IOException {
		return prepare(instance, instance.toAbsolutePath().normalize().resolve("kubejs"), "kubejs-installations", generated);
	}

	public static Plan prepareDatapack(Path instance, Map<String, String> generated) throws IOException {
		String relative = "datapacks/bee-designer";
		Map<String, String> files = new LinkedHashMap<>();
		generated.forEach((path, text) -> files.put(relative + "/" + path, text));
		files.put(relative + "/pack.mcmeta", "{\"pack\":{\"pack_format\":48,\"supported_formats\":{\"min_inclusive\":34,\"max_inclusive\":48},\"description\":\"Bee Designer installed bees\"}}");
		return prepare(instance, instance.toAbsolutePath().normalize().resolve(relative), "datapack-installations", files);
	}

	public static Plan prepareWorldDatapack(Path instance, Path world, Map<String, String> generated) throws IOException {
		instance = instance.toAbsolutePath().normalize();
		world = world.toAbsolutePath().normalize();
		if (!world.startsWith(instance.resolve("saves")) || world.getNameCount() != instance.getNameCount() + 2) {
			throw new IOException("designer.error.kubejs_install_path|" + world);
		}
		String data = instance.relativize(world.resolve("datapacks/bee-designer")).toString().replace('\\', '/');
		String names = "resourcepacks/bee-designer";
		Map<String, String> files = new LinkedHashMap<>();
		generated.forEach((path, text) -> {
			if (path.startsWith("assets/")) {
				files.put(names + "/" + path, text);
			} else {
				files.put(data + "/" + path, text);
			}
		});
		files.put(names + "/pack.mcmeta", "{\"pack\":{\"pack_format\":34,\"description\":\"Bee Designer names\"}}");
		return prepare(instance, world.resolve("datapacks/bee-designer"), "world-datapack-installations", files);
	}

	private static Plan prepare(Path instance, Path target, String backupFolder, Map<String, String> generated) throws IOException {
		instance = instance.toAbsolutePath().normalize();
		Path inspecting = instance;
		try {
			checkPath(instance, instance, true);
			if (!Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS) || generated.isEmpty()
					|| generated.size() > BeeDesignerStore.MAX_FILES) {
				throw new IOException("designer.error.kubejs_install_path|" + instance);
			}
			Map<String, String> files = new LinkedHashMap<>();
			List<FileChange> changes = new ArrayList<>();
			long readBytes = 0;
			long plannedBytes = 0;
			int created = 0;
			int replaced = 0;
			int unchanged = 0;
			for (String relative : generated.keySet().stream().sorted().toList()) {
				Path path = runtimePath(instance, relative);
				if (!withinTarget(instance, target, path)) {
					throw new IOException("designer.error.kubejs_install_path|" + path);
				}
				inspecting = path;
				checkPath(instance, path, false);
				String incoming = generated.get(relative);
				validateText(relative, incoming);
				Existing existing = read(path);
				if (existing != null) {
					readBytes += existing.bytes();
					if (readBytes > BeeDesignerStore.MAX_PACK_BYTES) {
						throw new IOException("designer.error.export_budget");
					}
				}
				String actual = incoming;
				if (relative.matches("(?:kubejs|datapacks/bee-designer|resourcepacks/bee-designer)/assets/[a-z0-9_.-]+/lang/[a-z0-9_.-]+\\.json")) {
					actual = mergeLanguage(relative, incoming, existing);
				}
				long bytes = encode(actual).length;
				plannedBytes += bytes;
				if (plannedBytes > BeeDesignerStore.MAX_PACK_BYTES) {
					throw new IOException("designer.error.export_budget");
				}
				Operation operation;
				if (existing == null) {
					operation = Operation.CREATE;
					created++;
				} else if (existing.text().equals(actual)) {
					operation = Operation.UNCHANGED;
					unchanged++;
				} else {
					operation = Operation.REPLACE;
					replaced++;
				}
				files.put(relative, actual);
				changes.add(new FileChange(relative, operation, existing == null ? null : existing.digest(), bytes));
			}
			Path backups = instance.resolve("bee-designer").resolve(backupFolder).resolve(UUID.randomUUID().toString());
			inspecting = backups;
			checkPath(instance, backups, true);
			return new Plan(instance, target, backups, files, changes, created, replaced, unchanged);
		} catch (IOException failure) {
			if (failure.getMessage() != null && failure.getMessage().startsWith("designer.error.")) {
				throw failure;
			}
			ProductiveBeesGenesis.LOGGER.warn("Cannot inspect bee installation file {}", inspecting, failure);
			throw new IOException("designer.error.kubejs_install_read|" + inspecting, failure);
		}
	}

	public static Path install(Plan plan) throws IOException {
		verify(plan);
		if (plan.createCount() == 0 && plan.replaceCount() == 0) {
			return plan.target();
		}
		checkPath(plan.instance(), plan.backupDirectory(), true);
		try {
			Files.createDirectories(plan.backupDirectory().getParent());
			Files.createDirectory(plan.backupDirectory());
		} catch (IOException failure) {
			ProductiveBeesGenesis.LOGGER.warn("Cannot prepare bee installation backup {}", plan.backupDirectory(), failure);
			throw new IOException("designer.error.kubejs_install_backup|" + plan.backupDirectory(), failure);
		}
		List<FileChange> committed = new ArrayList<>();
		try {
			for (FileChange change : plan.changes()) {
				if (change.operation() == Operation.UNCHANGED) {
					continue;
				}
				Path source = runtimePath(plan.instance(), change.path());
				if (change.operation() == Operation.REPLACE) {
					Path backup = plan.backupDirectory().resolve("original").resolve(change.path());
					Files.createDirectories(backup.getParent());
					Existing original = read(source);
					if (original == null || !original.digest().equals(change.existingSha256())) {
						throw new IOException("designer.error.kubejs_install_changed|" + source);
					}
					Files.write(backup, encode(original.text()), StandardOpenOption.CREATE_NEW);
					if (!read(backup).digest().equals(change.existingSha256())) {
						throw new IOException("Cannot verify original backup: " + backup);
					}
				}
				Path staged = plan.backupDirectory().resolve("staged").resolve(change.path());
				Files.createDirectories(staged.getParent());
				Files.write(staged, encode(plan.files().get(change.path())), StandardOpenOption.CREATE_NEW);
			}
			journal(plan, "installing", committed, "");
			// 全部原文与待写内容已准备；每次提交仍按预览 SHA 核对外部文件。
			for (FileChange change : plan.changes()) {
				if (change.operation() == Operation.UNCHANGED) {
					continue;
				}
				verifyFile(plan, change);
				Path target = runtimePath(plan.instance(), change.path());
				Files.createDirectories(target.getParent());
				committed.add(change);
				move(plan.backupDirectory().resolve("staged").resolve(change.path()), target,
						change.operation() == Operation.REPLACE);
			}
			journal(plan, "installed", committed, "");
			return plan.target();
		} catch (IOException | RuntimeException failure) {
			List<String> unresolved = rollback(plan, committed, failure);
			String phase = unresolved.isEmpty() ? "rolled_back" : "rollback_incomplete";
			try {
				journal(plan, phase, committed, failure.toString() + "; unresolved=" + unresolved);
			} catch (IOException journalFailure) {
				failure.addSuppressed(journalFailure);
			}
			ProductiveBeesGenesis.LOGGER.warn("Bee installation failed; backup={}, unresolved={}", plan.backupDirectory(), unresolved, failure);
			String key = unresolved.isEmpty() ? "designer.error.kubejs_install_failed" : "designer.error.kubejs_install_rollback";
			throw new IOException(key + "|" + plan.backupDirectory() + "|" + String.join(", ", unresolved), failure);
		}
	}

	private static void verify(Plan plan) throws IOException {
		checkPath(plan.instance(), plan.instance(), true);
		boolean kubeJs = plan.target().equals(plan.instance().resolve("kubejs"));
		boolean datapack = plan.target().equals(plan.instance().resolve("datapacks/bee-designer"));
		boolean world = worldTarget(plan.instance(), plan.target());
		String backupFolder = "datapack-installations";
		if (kubeJs) {
			backupFolder = "kubejs-installations";
		} else if (world) {
			backupFolder = "world-datapack-installations";
		}
		if ((!kubeJs && !datapack && !world)
				|| !plan.backupDirectory().getParent().equals(plan.instance().resolve("bee-designer").resolve(backupFolder))
				|| !plan.files().keySet().equals(plan.changes().stream().map(FileChange::path)
						.collect(java.util.stream.Collectors.toSet()))) {
			throw new IOException("designer.error.kubejs_install_path|" + plan.target());
		}
		long bytes = 0;
		for (FileChange change : plan.changes()) {
			String text = plan.files().get(change.path());
			validateText(change.path(), text);
			bytes += encode(text).length;
			if (bytes > BeeDesignerStore.MAX_PACK_BYTES || plan.changes().size() > BeeDesignerStore.MAX_FILES) {
				throw new IOException("designer.error.export_budget");
			}
			verifyFile(plan, change);
		}
	}

	private static void verifyFile(Plan plan, FileChange change) throws IOException {
		Path path = runtimePath(plan.instance(), change.path());
		if (!withinTarget(plan.instance(), plan.target(), path)) {
			throw new IOException("designer.error.kubejs_install_path|" + path);
		}
		checkPath(plan.instance(), path, false);
		Existing current = read(path);
		if (change.operation() == Operation.CREATE ? current != null
				: current == null || !current.digest().equals(change.existingSha256())) {
			throw new IOException("designer.error.kubejs_install_changed|" + path);
		}
	}

	private static List<String> rollback(Plan plan, List<FileChange> committed, Throwable failure) {
		List<String> unresolved = new ArrayList<>();
		for (int index = committed.size() - 1; index >= 0; index--) {
			FileChange change = committed.get(index);
			try {
				Path target = runtimePath(plan.instance(), change.path());
				checkPath(plan.instance(), target, false);
				Existing installed = read(target);
				if (change.operation() == Operation.CREATE && installed == null
						|| change.operation() == Operation.REPLACE && installed != null && installed.digest().equals(change.existingSha256())) {
					continue;
				}
				if (installed == null || !installed.digest().equals(digest(encode(plan.files().get(change.path()))))) {
					throw new IOException("Installed file changed before rollback: " + target);
				}
				if (change.operation() == Operation.CREATE) {
					Path rescued = plan.backupDirectory().resolve("failed").resolve(change.path());
					Files.createDirectories(rescued.getParent());
					move(target, rescued, false);
				} else {
					Path restored = plan.backupDirectory().resolve("restore").resolve(change.path());
					Files.createDirectories(restored.getParent());
					Path original = plan.backupDirectory().resolve("original").resolve(change.path());
					checkPath(plan.instance(), original, false);
					Existing backup = read(original);
					if (backup == null || !backup.digest().equals(change.existingSha256())) {
						throw new IOException("Original backup changed before rollback: " + original);
					}
					Files.write(restored, encode(backup.text()), StandardOpenOption.CREATE_NEW);
					move(restored, target, true);
				}
			} catch (IOException | RuntimeException rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
				unresolved.add(change.path());
			}
		}
		return List.copyOf(unresolved);
	}

	private static String mergeLanguage(String path, String incoming, Existing existing) throws IOException {
		JsonObject added = language(path, incoming);
		if (existing == null) {
			return incoming;
		}
		JsonObject previous = language(path, existing.text());
		JsonObject merged = previous.deepCopy();
		added.entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue().deepCopy()));
		return previous.equals(merged) ? existing.text() : new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(merged) + "\n";
	}

	private static JsonObject language(String path, String text) throws IOException {
		JsonObject result = json(path, text);
		if (result.entrySet().stream().anyMatch(entry -> !entry.getValue().isJsonPrimitive()
				|| !entry.getValue().getAsJsonPrimitive().isString())) {
			throw new IOException("designer.error.kubejs_install_file|" + path);
		}
		return result;
	}

	private static void validateText(String path, String text) throws IOException {
		if (encode(text).length > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IOException("designer.error.export_budget");
		}
		if (path.endsWith(".json") || path.endsWith(".mcmeta")) {
			json(path, text);
		}
	}

	private static JsonObject json(String path, String text) throws IOException {
		try {
			BeeDesignerProtocol.checkDepth(text, BeeDesignerProtocol.MAX_DEPTH);
			return JsonParser.parseString(text).getAsJsonObject();
		} catch (IllegalArgumentException | IllegalStateException | JsonParseException invalid) {
			throw new IOException("designer.error.kubejs_install_file|" + path, invalid);
		}
	}

	private static byte[] encode(String text) throws IOException {
		try {
			ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text));
			byte[] bytes = new byte[encoded.remaining()];
			encoded.get(bytes);
			return bytes;
		} catch (CharacterCodingException invalid) {
			throw new IOException("designer.error.kubejs_install_encoding", invalid);
		}
	}

	private static Existing read(Path path) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IOException("designer.error.kubejs_install_file|" + path);
		}
		rejectHardLinks(path);
		byte[] bytes;
		try (var input = Files.newInputStream(path)) {
			bytes = input.readNBytes(BeeDesignerStore.MAX_PACK_BYTES + 1);
		}
		if (bytes.length > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IOException("designer.error.export_budget");
		}
		try {
			return new Existing(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(), digest(bytes), bytes.length);
		} catch (CharacterCodingException invalid) {
			throw new IOException("designer.error.kubejs_install_file|" + path, invalid);
		}
	}

	private static Path runtimePath(Path instance, String relative) throws IOException {
		if (!relative.matches("kubejs/(?:data|assets)/[a-z0-9_.-]+/[a-z0-9_./-]+\\.json")
				&& !relative.matches("kubejs/server_scripts/[a-z0-9_./-]+\\.js")
				&& !relative.matches("datapacks/bee-designer/(?:(?:data|assets)/[a-z0-9_.-]+/[a-z0-9_./-]+\\.json|pack\\.mcmeta)")
				&& !relative.matches("saves/[^/\\\\:]+/datapacks/bee-designer/(?:data/[a-z0-9_.-]+/[a-z0-9_./-]+\\.json|pack\\.mcmeta)")
				&& !relative.matches("resourcepacks/bee-designer/(?:assets/[a-z0-9_.-]+/[a-z0-9_./-]+\\.json|pack\\.mcmeta)")
				|| java.util.Arrays.asList(relative.split("/")).contains("..") || relative.contains("//")) {
			throw new IOException("designer.error.kubejs_install_path|" + relative);
		}
		for (String segment : relative.split("/")) {
			String stem = segment.split("\\.", 2)[0];
			if (segment.endsWith(".") || java.util.Set.of("con", "prn", "aux", "nul").contains(stem) || stem.matches("(?:com|lpt)[1-9]")) {
				throw new IOException("designer.error.kubejs_install_path|" + relative);
			}
		}
		return instance.resolve(relative);
	}

	private static boolean worldTarget(Path instance, Path target) {
		return target.startsWith(instance.resolve("saves")) && target.getNameCount() == instance.getNameCount() + 4
				&& target.endsWith(Path.of("datapacks/bee-designer"));
	}

	private static boolean withinTarget(Path instance, Path target, Path file) {
		return file.startsWith(target) || worldTarget(instance, target) && file.startsWith(instance.resolve("resourcepacks/bee-designer"));
	}

	private static void checkPath(Path instance, Path target, boolean directory) throws IOException {
		if (!target.toAbsolutePath().normalize().startsWith(instance)) {
			throw new IOException("designer.error.kubejs_install_path|" + target);
		}
		boolean directoryPermissionChecked = false;
		for (Path cursor = target; cursor != null; cursor = cursor.getParent()) {
			if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
				continue;
			}
			BasicFileAttributes attributes = Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
			if (attributes.isSymbolicLink() || attributes.isOther() || !cursor.toRealPath().equals(cursor.toAbsolutePath().normalize())
					|| (directory || !cursor.equals(target)) && !attributes.isDirectory()) {
				throw new IOException("designer.error.kubejs_install_path|" + cursor);
			}
			if (cursor.equals(target) && !Files.isWritable(cursor)
					|| attributes.isDirectory() && !directoryPermissionChecked && !Files.isWritable(cursor)) {
				throw new IOException("designer.error.kubejs_install_path|" + cursor);
			}
			if (attributes.isDirectory()) {
				directoryPermissionChecked = true;
			}
		}
	}

	private static void rejectHardLinks(Path path) throws IOException {
		if (!Platform.isWindows()) {
			if (((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
				throw new IOException("designer.error.kubejs_install_path|" + path);
			}
			return;
		}
		String file = path.toString();
		String nativePath = file;
		if (!file.startsWith("\\\\?\\")) {
			nativePath = file.startsWith("\\\\") ? "\\\\?\\UNC\\" + file.substring(2) : "\\\\?\\" + file;
		}
		WinNT.HANDLE handle = Kernel32.INSTANCE.CreateFile(nativePath, WinNT.FILE_READ_ATTRIBUTES,
				WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null, WinNT.OPEN_EXISTING,
				WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
		if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) {
			throw new IOException("Cannot read file link metadata: " + path + "; Win32=" + Kernel32.INSTANCE.GetLastError());
		}
		IOException failure = null;
		try {
			WinBase.FILE_STANDARD_INFO information = new WinBase.FILE_STANDARD_INFO();
			if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileStandardInfo, information.getPointer(),
					new WinDef.DWORD(information.size()))) {
				throw new IOException("Cannot read file link count: " + path + "; Win32=" + Kernel32.INSTANCE.GetLastError());
			}
			information.read();
			if (information.NumberOfLinks != 1) {
				throw new IOException("designer.error.kubejs_install_path|" + path);
			}
		} catch (IOException invalid) {
			failure = invalid;
			throw invalid;
		} finally {
			if (!Kernel32.INSTANCE.CloseHandle(handle)) {
				IOException closeFailure = new IOException("Cannot close file metadata handle: " + path);
				if (failure == null) {
					throw closeFailure;
				}
				failure.addSuppressed(closeFailure);
			}
		}
	}

	private static String digest(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static void move(Path source, Path target, boolean replace) throws IOException {
		try {
			if (replace) {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
			}
		} catch (AtomicMoveNotSupportedException unsupported) {
			if (replace) {
				Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.move(source, target);
			}
		}
	}

	private static void journal(Plan plan, String phase, List<FileChange> committed, String failure) throws IOException {
		JsonObject data = new JsonObject();
		data.addProperty("instance", plan.instance().toString());
		data.addProperty("phase", phase);
		data.addProperty("failure", failure);
		var changes = new com.google.gson.JsonArray();
		var attempted = committed.stream().map(FileChange::path).collect(java.util.stream.Collectors.toSet());
		for (FileChange change : plan.changes()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("path", change.path());
			entry.addProperty("operation", change.operation().name());
			entry.addProperty("existing_sha256", change.existingSha256());
			entry.addProperty("installed_sha256", digest(encode(plan.files().get(change.path()))));
			entry.addProperty("attempted", attempted.contains(change.path()));
			changes.add(entry);
		}
		data.add("files", changes);
		Path temporary = plan.backupDirectory().resolve("installation.tmp");
		Files.writeString(temporary, data.toString(), StandardCharsets.UTF_8);
		move(temporary, plan.backupDirectory().resolve("installation.json"), true);
	}
}
