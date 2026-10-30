package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerArchive;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCredits;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDrafts;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineSources;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 本地导入与导出只处理工程声明；客户端不写世界目录。 */
final class DesignerProjectFiles {
	private static Path lastExport;
	private static final ThreadPoolExecutor FILES = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(8), runnable -> {
		Thread thread = new Thread(runnable, "Bee designer local files");
		thread.setDaemon(true);
		return thread;
	});

	private DesignerProjectFiles() {
	}

	static void readPreferences(Path directory, Consumer<JsonObject> loaded, Consumer<String> failed) {
		submit(() -> {
			Path path = directory.resolve("preferences.json");
			return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? readSettingsJson(path) : DesignerPreferences.defaults();
		}, loaded, failed);
	}

	static void savePreferences(Path directory, JsonObject value, Runnable saved, Consumer<String> failed) {
		String snapshot = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value);
		submit(() -> {
			writeSettingsJson(directory.resolve("preferences.json"), snapshot);
			return true;
		}, unused -> saved.run(), failed);
	}

	static void importTheme(Path path, Consumer<JsonObject> loaded, Consumer<String> failed) {
		submit(() -> readSettingsJson(path), loaded, failed);
	}

	static void exportTheme(JsonObject value, Consumer<Path> saved, Consumer<String> failed) {
		String snapshot = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value);
		Path path = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer/themes")
				.resolve("theme-" + java.util.UUID.randomUUID() + ".json");
		submit(() -> {
			writeSettingsJson(path, snapshot);
			return path;
		}, output -> {
			lastExport = output;
			saved.accept(output);
		}, failed);
	}

	private static JsonObject readSettingsJson(Path path) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 32768) {
			throw new IOException("designer.error.settings_file");
		}
		String text = Files.readString(path, StandardCharsets.UTF_8);
		if (text.length() > 32768) {
			throw new IOException("designer.error.settings_file");
		}
		try {
			return DesignerJsonCodec.parse(text);
		} catch (IllegalArgumentException exception) {
			throw new IOException("designer.error.settings_json", exception);
		}
	}

	private static void writeSettingsJson(Path path, String text) throws IOException {
		if (text.getBytes(StandardCharsets.UTF_8).length > 32768) {
			throw new IOException("designer.error.settings_file");
		}
		for (Path parent = path.toAbsolutePath(); parent != null; parent = parent.getParent()) {
			if (Files.isSymbolicLink(parent)) {
				throw new IOException("designer.error.settings_file");
			}
		}
		Files.createDirectories(path.getParent());
		Path temporary = path.resolveSibling(path.getFileName() + "." + java.util.UUID.randomUUID() + ".tmp");
		Files.writeString(temporary, text + "\n", StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
		try {
			Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	static void read(Path path, Consumer<Map<String, String>> loaded, Consumer<String> failed) {
		submit(() -> BeeDesignerArchive.read(path), loaded, failed);
	}

	static void readCredits(Path path, Consumer<JsonObject> loaded, Consumer<String> failed) {
		submit(() -> BeeDesignerCredits.readManifest(path), loaded, failed);
	}

	static void detectCredits(Consumer<JsonObject> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
		submit(() -> {
			Exception invalid = null;
			for (String name : List.of("manifest.json", "modrinth.index.json")) {
				Path path = directory.resolve(name);
				if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
					try {
						return BeeDesignerCredits.readManifest(path);
					} catch (IOException | RuntimeException failure) {
						invalid = failure;
					}
				}
			}
			if (invalid != null) {
				throw new IOException("designer.error.credits_manifest", invalid);
			}
			return BeeDesignerCredits.empty();
		}, loaded, failed);
	}

	static void exportCollection(Map<String, String> documents, JsonObject credits, String format,
			Consumer<Path> exported, Consumer<String> failed) {
		Map<String, String> snapshot = Map.copyOf(documents);
		JsonObject source = credits.deepCopy();
		Path directory = workspace();
		submit(() -> switch (format) {
			case "portable" -> BeeDesignerCollection.export(directory, snapshot, source);
			case "datapack" -> BeeDesignerArchive.export(directory, snapshot, source);
			case "kubejs" -> BeeDesignerArchive.exportKubeJs(directory, snapshot, source);
			default -> throw new IllegalArgumentException("designer.error.collection_format");
		}, path -> {
			lastExport = path;
			exported.accept(path);
		}, failed);
	}


	static void export(JsonObject document, Consumer<Path> exported, Consumer<String> failed) {
		JsonObject snapshot = document.deepCopy();
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerArchive.export(directory, snapshot), path -> {
			lastExport = path;
			exported.accept(path);
		}, failed);
	}

	static void readMachines(Path path, Consumer<BeeDesignerMachineSources.Import> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerMachineSources.read(path, directory), loaded, failed);
	}

	static void exportKubeJs(JsonObject document, Consumer<Path> exported, Consumer<String> failed) {
		JsonObject snapshot = document.deepCopy();
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerArchive.exportKubeJs(directory, snapshot), path -> {
			lastExport = path;
			exported.accept(path);
		}, failed);
	}

	static void verifyMachines(BeeDesignerMachineSources.Import imported, Runnable checked, Consumer<String> failed) {
		submit(() -> {
			BeeDesignerMachineSources.verifyUnchanged(imported);
			return true;
		}, unused -> checked.run(), failed);
	}

	static void machineSource(BeeDesignerMachineSources.Import imported,
			com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineScripts.Candidate candidate, Consumer<String> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerMachineSources.fragment(directory, imported, candidate), loaded, failed);
	}

	static void exportMachines(JsonObject document, Consumer<Path> exported, Consumer<String> failed) {
		JsonObject snapshot = document.deepCopy();
		try {
			for (var value : snapshot.getAsJsonObject("recipes").asMap().values()) {
				if (BeeDesignerMachineRecipes.host(value.getAsJsonObject()) != null) {
					BeeDesignerMachineRecipes.validateStructure(value.getAsJsonObject(), Minecraft.getInstance().level.registryAccess());
				}
			}
		} catch (RuntimeException failure) {
			failed.accept(message(failure));
			return;
		}
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerMachineSources.export(directory, snapshot), path -> {
			lastExport = path;
			exported.accept(path);
		}, failed);
	}

	static void openFolder(boolean exports, Consumer<String> failed) {
		Path directory = workspace().resolve("drafts");
		if (exports) {
			directory = lastExport == null ? workspace().resolve("productivebeesgenesis/bee_designer/exports") : lastExport.getParent();
		}
		openDirectory(directory, true, failed);
	}

	static Path workspace() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer").toAbsolutePath().normalize();
	}

	static Path lastExport() {
		return lastExport;
	}

	static void exportedOnServer(String value) {
		var server = Minecraft.getInstance().getSingleplayerServer();
		if (server == null) {
			return;
		}
		Path path = Path.of(value).toAbsolutePath().normalize();
		Path exports = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize()
				.resolve("productivebeesgenesis/bee_designer/exports");
		if (path.getParent() != null && path.getParent().equals(exports)) {
			lastExport = path;
		}
	}

	static void openDirectory(Path directory, boolean create, Consumer<String> failed) {
		Path target = directory.toAbsolutePath().normalize();
		submit(() -> {
			for (Path cursor = target; cursor != null; cursor = cursor.getParent()) {
				if (Files.isSymbolicLink(cursor) || Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
						&& Files.readAttributes(cursor, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther()) {
					throw new IOException("designer.error.file_folder");
				}
			}
			if (create) {
				Files.createDirectories(target);
			}
			if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new IOException("designer.error.file_folder_missing");
			}
			net.minecraft.Util.getPlatform().openFile(target.toFile());
			return true;
		}, unused -> {}, failed);
	}

	static void saveDraft(String snapshot, Runnable saved, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> {
			BeeDesignerDrafts.save(directory, BeeDesignerProtocol.parse(snapshot));
			return true;
		}, unused -> saved.run(), failed);
	}

	static void latestDraft(Consumer<JsonObject> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.latest(directory), loaded, failed);
	}

	static void drafts(Consumer<List<BeeDesignerDrafts.Entry>> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.list(directory), loaded, failed);
	}

	static void draft(String id, Consumer<JsonObject> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.read(directory, id), loaded, failed);
	}

	static void archiveDraft(String id, Runnable archived, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.archive(directory, id), unused -> archived.run(), failed);
	}

	static void preserveImportedDraft(JsonObject document, Runnable ready, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		String snapshot = document.toString();
		submit(() -> BeeDesignerDrafts.preserveBeforeImport(directory, BeeDesignerProtocol.parse(snapshot)), unused -> {
			DesignerDraftIndex.refresh();
			ready.run();
		}, failed);
	}

	static void recycledDrafts(Consumer<List<BeeDesignerDrafts.Archived>> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.archives(directory), loaded, failed);
	}

	static void recycledDraft(String token, Consumer<JsonObject> loaded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.archived(directory, token), loaded, failed);
	}

	static void restoreDraft(String token, Consumer<JsonObject> restored, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> BeeDesignerDrafts.restoreArchive(directory, token), document -> {
			DesignerDraftIndex.saved(document);
			restored.accept(document);
		}, failed);
	}

	static void deleteRecycledDraft(String token, long modified, Runnable deleted, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> {
			BeeDesignerDrafts.deleteArchive(directory, token, modified);
			return true;
		}, unused -> deleted.run(), failed);
	}

	static void discardDraft(String id, long modified, Runnable discarded, Consumer<String> failed) {
		Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("bee-designer");
		submit(() -> {
			BeeDesignerDrafts.discard(directory, id, modified);
			return true;
		}, unused -> discarded.run(), failed);
	}

	private static <T> void submit(FileAction<T> action, Consumer<T> completed, Consumer<String> failed) {
		submit(FILES, action, completed, failed);
	}

	private static <T> void submit(ThreadPoolExecutor executor, FileAction<T> action, Consumer<T> completed, Consumer<String> failed) {
		try {
			CompletableFuture.supplyAsync(() -> {
				try {
					return action.run();
				} catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
			}, executor).whenComplete((value, failure) -> Minecraft.getInstance().execute(() -> {
				if (failure == null) {
					completed.accept(value);
				} else {
					failed.accept(message(failure));
				}
			}));
		} catch (RejectedExecutionException exception) {
			failed.accept("designer.error.file_queue");
		}
	}

	@FunctionalInterface
	private interface FileAction<T> {
		T run() throws IOException;
	}

	private static String message(Throwable failure) {
		Throwable cause = failure;
		while (cause.getCause() != null) {
			if (cause.getMessage() != null && cause.getMessage().startsWith("designer.error.")) {
				return cause.getMessage();
			}
			cause = cause.getCause();
		}
		return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
	}
}
