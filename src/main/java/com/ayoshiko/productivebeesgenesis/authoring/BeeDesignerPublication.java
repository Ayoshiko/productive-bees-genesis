package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 每个服务器只允许一项发布；游戏线程拥有状态，IO 线程只接收封闭的文件快照。 */
final class BeeDesignerPublication {
	private final MinecraftServer server;
	private final Path world;
	final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Bee designer files");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean stopped;
	private boolean busy = true;
	private boolean uncertain;
	private String phase = "recovering";
	private String operation = "";
	private String message = "";

	BeeDesignerPublication(MinecraftServer server) {
		this.server = server;
		this.world = server.getWorldPath(LevelResource.ROOT);
		CompletableFuture.supplyAsync(() -> {
			try {
				return BeeDesignerStore.inspect(world);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}, io).whenComplete((journal, failure) -> onServer(() -> {
			busy = false;
			if (failure != null) {
				uncertain = true;
				phase = "uncertain";
				message = BeeDesignerServer.safeMessage(failure);
				ProductiveBeesGenesis.LOGGER.error("Cannot inspect bee designer publication journal", failure);
				return;
			}
			phase = BeeDesignerDocument.text(journal, "phase", "uncertain");
			operation = BeeDesignerDocument.text(journal, "revision", "");
			message = BeeDesignerDocument.text(journal, "message", "");
			uncertain = !List.of("idle", "committed", "rejected").contains(phase);
		}));
	}

	boolean blocked() {
		return busy || uncertain || stopped;
	}

	boolean running() {
		return busy;
	}

	JsonObject status() {
		JsonObject data = new JsonObject();
		data.addProperty("busy", busy);
		data.addProperty("uncertain", uncertain);
		data.addProperty("phase", phase);
		data.addProperty("operation", operation);
		data.addProperty("message", message);
		return data;
	}

	void start(JsonObject document, Map<String, String> files, boolean export, BooleanSupplier authorized,
			BooleanSupplier unchanged, Consumer<Path> exported, Consumer<JsonObject> applied, Consumer<String> failed) {
		if (blocked()) {
			throw new IllegalStateException("designer.error.busy");
		}
		busy = true;
		phase = "writing";
		operation = UUID.randomUUID().toString();
		message = "";
		JsonObject snapshot = document.deepCopy();
		Map<String, String> closedFiles = Map.copyOf(files);
		Map<String, String> projects = JsonParser.parseString(files.get("data/productivebeesgenesis/bee_designer/index.json"))
				.getAsJsonObject().entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> entry.getValue().toString()));
		CompletableFuture.supplyAsync(() -> {
			try {
				return BeeDesignerStore.write(world, closedFiles, export);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}, io).whenComplete((revision, failure) -> onServer(() -> {
			if (failure != null) {
				finishFailure(failure, false, failed);
				return;
			}
			operation = revision.source().getFileName().toString();
			if (!authorized.getAsBoolean() || !unchanged.getAsBoolean()) {
				reject(revision, false, new IllegalStateException("designer.error.conflict_or_permission"), failed);
				return;
			}
			if (export) {
				busy = false;
				phase = "exported";
				exported.accept(revision.source());
				return;
			}
			phase = "installing";
			CompletableFuture.runAsync(() -> {
				try {
					BeeDesignerStore.install(revision);
				} catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
			}, io).whenComplete((unused, installFailure) -> onServer(() -> {
				if (installFailure != null) {
					reject(revision, false, installFailure, failed);
				} else if (!authorized.getAsBoolean() || !unchanged.getAsBoolean()) {
					reject(revision, false, new IllegalStateException("designer.error.conflict_or_permission"), failed);
				} else {
					reload(revision, snapshot, projects, applied, failed);
				}
			}));
		}));
	}

	private void reload(BeeDesignerStore.Revision revision, JsonObject document, Map<String, String> projects, Consumer<JsonObject> applied,
			Consumer<String> failed) {
		List<String> previous = server.getPackRepository().getSelectedIds().stream().toList();
		List<String> selected = new ArrayList<>(previous.stream().filter(id -> !BeeDesignerStore.managedPack(id)).toList());
		try {
			server.getPackRepository().reload();
			if (!server.getPackRepository().isAvailable(revision.packId())) {
				throw new IllegalStateException("Generated pack was not discovered");
			}
		} catch (RuntimeException exception) {
			reject(revision, false, exception, failed);
			return;
		}
		selected.add(revision.packId());
		phase = "reloading";
		// 显式把发起调用排入文件线程；thenCompose 可能在 future 已完成时同步执行。
		CompletableFuture.supplyAsync(() -> server.reloadResources(List.copyOf(selected)), io)
				.thenCompose(value -> value).whenComplete((unused, failure) -> onServer(() -> {
					if (failure != null) {
						reject(revision, true, failure, failed);
						return;
					}
					try {
						verifyAll(projects, revision.packId());
					} catch (RuntimeException exception) {
						reject(revision, true, exception, failed);
						return;
					}
					phase = "archiving";
					CompletableFuture.runAsync(() -> {
						try {
							BeeDesignerStore.complete(revision, previous);
						} catch (IOException exception) {
							throw new UncheckedIOException(exception);
						}
					}, io).whenComplete((ignored, archiveFailure) -> onServer(() -> {
						if (archiveFailure != null) {
							finishFailure(archiveFailure, true, failed);
							return;
						}
						busy = false;
						phase = "committed";
						applied.accept(document);
					}));
				}));
	}

	void reconcile(BooleanSupplier authorized, Consumer<JsonObject> finished, Consumer<String> failed) {
		if (busy || stopped) {
			throw new IllegalStateException("designer.error.busy");
		}
		busy = true;
		phase = "reconciling";
		long generation = BeeDesignerSources.generation;
		List<String> selected = List.copyOf(server.getPackRepository().getSelectedIds());
		CompletableFuture.supplyAsync(() -> {
			try {
				return BeeDesignerStore.audit(world, selected);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}, io).whenComplete((audit, failure) -> onServer(() -> {
			if (failure != null) {
				finishFailure(failure, true, failed);
				return;
			}
			try {
				if (!authorized.getAsBoolean() || generation != BeeDesignerSources.generation
						|| !selected.equals(List.copyOf(server.getPackRepository().getSelectedIds()))) {
					throw new IllegalStateException("designer.error.conflict_or_permission");
				}
				verifyAll(audit.documents(), audit.active() == null ? "" : audit.active().packId());
			} catch (RuntimeException exception) {
				finishFailure(exception, true, failed);
				return;
			}
			CompletableFuture.runAsync(() -> {
				try {
					BeeDesignerStore.reconcile(world, audit);
				} catch (IOException exception) {
					throw new UncheckedIOException(exception);
				}
			}, io).whenComplete((unused, writeFailure) -> onServer(() -> {
				if (writeFailure != null) {
					finishFailure(writeFailure, true, failed);
					return;
				}
				busy = false;
				uncertain = false;
				phase = audit.active() == null ? "rejected" : "committed";
				operation = audit.active() == null ? operation : audit.active().source().getFileName().toString();
				message = "designer.status.reconciled";
				finished.accept(status());
			}));
		}));
	}

	private void verifyAll(Map<String, String> projects, String packId) {
		if (!projects.keySet().equals(BeeDesignerSources.managed.keySet())) {
			throw new IllegalStateException("Managed source index differs from the selected revision");
		}
		if (projects.isEmpty()) {
			boolean active = server.getResourceManager().listResources("productivebees", path -> path.getPath().endsWith(".json"))
					.values().stream().anyMatch(resource -> BeeDesignerStore.managedPack(resource.sourcePackId()));
			active |= server.getResourceManager().listResources("recipe", path -> path.getPath().endsWith(".json"))
					.values().stream().anyMatch(resource -> BeeDesignerStore.managedPack(resource.sourcePackId()));
			if (active) {
				throw new IllegalStateException("A managed bee definition is loaded without its source index");
			}
			return;
		}
		var index = server.getResourceManager().getResource(ResourceLocation.parse("productivebeesgenesis:bee_designer/index.json"));
		if (index.isEmpty() || !index.get().sourcePackId().equals(packId)) {
			throw new IllegalStateException("Managed source index is overridden by another pack");
		}
		for (var entry : projects.entrySet()) {
			JsonObject document = JsonParser.parseString(entry.getValue()).getAsJsonObject();
			if (!document.equals(JsonParser.parseString(BeeDesignerSources.managed.get(entry.getKey())))) {
				throw new IllegalStateException("Managed project source differs from the selected revision: " + entry.getKey());
			}
			verify(document, packId);
		}
	}

	private void verify(JsonObject document, String packId) {
		String id = document.get("id").getAsString();
		ResourceLocation path = ResourceLocation.parse(document.get("definition_path").getAsString());
		String raw = BeeDesignerSources.definitions.get(path);
		var definition = server.getResourceManager().getResource(path.withPath("productivebees/" + path.getPath() + ".json"));
		if (BeeReloadListener.INSTANCE.getData(ResourceLocation.parse(id)) == null || raw == null
				|| !document.get("definition").equals(JsonParser.parseString(raw)) || definition.isEmpty()
				|| !definition.get().sourcePackId().equals(packId)) {
			throw new IllegalStateException("Bee definition was not activated: " + id);
		}
		for (String recipe : document.getAsJsonObject("recipes").keySet()) {
			ResourceLocation key = ResourceLocation.parse(recipe);
			var resource = server.getResourceManager().getResource(key.withPath("recipe/" + key.getPath() + ".json"));
            JsonObject source = BeeDesignerLifecycle.activeRecipe(document, document.getAsJsonObject("recipes").getAsJsonObject(recipe),
                    BeeDesignerLifecycle.retiredIds(BeeDesignerSources.managed));
			if (source == null) {
				if (server.getRecipeManager().byKey(key).isPresent() || resource.isEmpty() || !resource.get().sourcePackId().equals(packId)) {
					throw new IllegalStateException("Deleted bee acquisition recipe is still active: " + recipe);
				}
				continue;
			}
			if (server.getRecipeManager().byKey(key).isEmpty() || resource.isEmpty() || !resource.get().sourcePackId().equals(packId)) {
				throw new IllegalStateException("Recipe was not activated: " + recipe);
			}
			if (BeeDesignerMachineRecipes.host(source) != null) {
				var ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
				var expected = Recipe.CODEC.parse(ops, source).getOrThrow();
				var actual = server.getRecipeManager().byKey(key).orElseThrow().value();
				if (!Recipe.CODEC.encodeStart(ops, expected).getOrThrow().equals(Recipe.CODEC.encodeStart(ops, actual).getOrThrow())) {
					throw new IllegalStateException("Machine recipe contents were overridden: " + recipe);
				}
			}
		}
		if (BeeDesignerLifecycle.deleted(ResourceLocation.parse(id)) != BeeDesignerLifecycle.deleted(document)) {
			throw new IllegalStateException("Bee lifecycle state was not activated: " + id);
		}
		for (var value : BeeDesignerDocument.array(document, "disabled_recipes")) {
			String recipe = value.getAsString();
			if (!document.getAsJsonObject("recipes").has(recipe)
					&& server.getRecipeManager().byKey(ResourceLocation.parse(recipe)).isPresent()) {
				throw new IllegalStateException("Retired recipe is still active: " + recipe);
			}
		}
	}

	private void reject(BeeDesignerStore.Revision revision, boolean unknown, Throwable failure, Consumer<String> failed) {
		if (revision.export()) {
			finishFailure(failure, false, failed);
			return;
		}
		phase = "quarantining";
		String detail = BeeDesignerServer.safeMessage(failure);
		CompletableFuture.runAsync(() -> {
			try {
				BeeDesignerStore.reject(revision, unknown, detail);
			} catch (IOException exception) {
				throw new UncheckedIOException(exception);
			}
		}, io).whenComplete((unused, isolationFailure) -> onServer(() -> {
			if (isolationFailure != null) {
				failure.addSuppressed(isolationFailure);
			}
			finishFailure(failure, unknown || isolationFailure != null, failed);
		}));
	}

	private void finishFailure(Throwable failure, boolean unknown, Consumer<String> failed) {
		busy = false;
		uncertain = unknown;
		phase = unknown ? "uncertain" : "rejected";
		message = BeeDesignerServer.safeMessage(failure);
		ProductiveBeesGenesis.LOGGER.error("Bee designer operation {} ended in {}", operation, phase, failure);
		failed.accept(message);
	}

	private void onServer(Runnable callback) {
		if (!stopped) {
			server.execute(() -> {
				if (!stopped) {
					callback.run();
				}
			});
		}
	}

	void stop() {
		stopped = true;
		io.shutdownNow();
	}
}
