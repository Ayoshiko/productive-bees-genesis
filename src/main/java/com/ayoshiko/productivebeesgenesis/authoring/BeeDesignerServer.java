package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** 所有权限、注册表和会话状态均由服务器线程拥有。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class BeeDesignerServer {
	private static final Map<MinecraftServer, State> STATES = new HashMap<>();
	private static final long SESSION_LIFETIME_NANOS = 30L * 60 * 1_000_000_000;

	private BeeDesignerServer() {
	}

	private static final class State {
		private final Map<UUID, Session> sessions = new HashMap<>();
		private final BeeDesignerPublication publication;
		private final ExecutorService io;
		private boolean busy;
		private boolean uncertain;
		private boolean stopped;
		private int exportReads;

		private State(MinecraftServer server) {
			publication = new BeeDesignerPublication(server);
			io = publication.io;
		}
	}

	private static final class Session {
		private final UUID token = UUID.randomUUID();
		private final BeeDesignerCatalog.Snapshot materials = new BeeDesignerCatalog.Snapshot();
		private long lastSequence;
		private long lastAccess = System.nanoTime();
		private long sourceVersion;
		private JsonObject original;
		private String managedSource;
		private boolean exporting;
		private BeeDesignerImportBatch importBatch;
		private BeeDesignerLifecycle.Confirmation deletion;
	}

	public static boolean canPublish(ServerPlayer player) {
		return player.isCreative() && (player.hasPermissions(2)
				|| !player.server.isDedicatedServer() && player.server.isSingleplayerOwner(player.getGameProfile()));
	}

	private static boolean holding(Player player) {
		return player.isCreative() && (player.getMainHandItem().is(ModItems.BEE_DESIGNER.get())
				|| player.getOffhandItem().is(ModItems.BEE_DESIGNER.get()));
	}

	public static void open(ServerPlayer player) {
		if (!holding(player)) {
			return;
		}
		State state = STATES.computeIfAbsent(player.server, State::new);
		Session session = new Session();
		state.sessions.put(player.getUUID(), session);
		JsonObject info = new JsonObject();
		info.addProperty("can_publish", canPublish(player));
		info.add("publication", state.publication.status());
		send(player, session, 0, "open", info);
	}

	public static void handle(Player sender, BeeDesignerProtocol.Request request) {
		if (!(sender instanceof ServerPlayer player)) {
			return;
		}
		State state = STATES.get(player.server);
		Session session = state == null ? null : state.sessions.get(player.getUUID());
		if (session == null) {
			return;
		}
		if (!active(player, state, session)) {
			return;
		}
		if (!session.token.equals(request.session()) || request.sequence() <= session.lastSequence) {
			return;
		}
		long now = System.nanoTime();
		if (session.lastSequence > 0 && now - session.lastAccess < 100_000_000) {
			result(player, session, request.sequence(), false, "designer.error.rate");
			return;
		}
		session.lastSequence = request.sequence();
		session.lastAccess = now;
		JsonObject data = null;
		try {
			data = BeeDesignerProtocol.parse(request.json());
			switch (request.action()) {
				case "status" -> send(player, session, request.sequence(), "status", status(state));
				case "history" -> history(player, state, session, request.sequence(), data);
				case "revision", "revision_bee" -> revision(player, state, session, request.sequence(), request.action(), data);
				case "reconcile" -> {
					if (!canPublish(player) || state.busy || state.uncertain) {
						throw new IllegalArgumentException(state.uncertain ? "designer.error.myriad_unknown" : "designer.error.permission_or_busy");
					}
					state.publication.reconcile(() -> active(player, state, session) && canPublish(player),
							response -> send(player, session, request.sequence(), "status", response),
							message -> result(player, session, request.sequence(), false, message));
				}
				case "catalog" -> send(player, session, request.sequence(), "catalog", BeeDesignerCatalog.bees(data));
				case "materials" -> send(player, session, request.sequence(), "materials", BeeDesignerCatalog.page(player.server, data, session.materials));
				case "bee_data" -> send(player, session, request.sequence(), "bee_data",
						BeeDesignerReader.beeData(player.server, ResourceLocation.parse(data.get("id").getAsString())));
				case "detail" -> detail(player, state, session, request.sequence(), data);
				case "export_snapshot" -> exportSnapshot(player, state, session, request.sequence(), data);
				case "copy_id" -> copyId(player, session, request.sequence(), data);
				case "import_begin" -> importBegin(player, state, session, request.sequence(), data);
				case "import_entry" -> importEntry(player, state, session, request.sequence(), data);
				case "import_commit" -> importCommit(player, state, session, request.sequence(), data);
				case "import_status" -> operationStatus(player, state, session, request.sequence(), data, false);
				case "apply_status" -> operationStatus(player, state, session, request.sequence(), data, true);
				case "import_document" -> operationDocument(player, state, session, request.sequence(), data);
				case "import_cancel" -> {
					if (session.importBatch != null) {
						if (session.importBatch.token.toString().equals(BeeDesignerDocument.text(data, "token", ""))) {
							session.importBatch = null;
						}
					}
				}
				case "delete_prepare" -> prepareDeletion(player, state, session, request.sequence(), data);
				case "delete_confirm" -> confirmDeletion(player, state, session, request.sequence(), data);
				case "check", "apply", "export" -> publish(player, state, session, request, data);
				case "myriad" -> send(player, session, request.sequence(), "myriad", BeeDesignerMyriad.snapshot());
				case "myriad_save" -> {
					if (!canPublish(player) || state.busy || state.publication.blocked()) {
						throw new IllegalArgumentException("designer.error.permission_or_busy");
					}
					saveMyriad(player, state, session, request.sequence(), data);
				}
				case "close" -> state.sessions.remove(player.getUUID());
				default -> throw new IllegalArgumentException("Unknown designer action");
			}
		} catch (RuntimeException exception) {
			ProductiveBeesGenesis.LOGGER.debug("Bee designer request {} rejected for {}", request.action(), player.getUUID(), exception);
			if (safeMessage(exception).startsWith("designer.error.recipe_read|")) {
				ProductiveBeesGenesis.LOGGER.warn("Bee designer could not preserve recipe data for {}", player.getUUID(), exception);
			}
			if (request.action().startsWith("import_")) {
				JsonObject rejected = new JsonObject();
				rejected.addProperty("token", data == null ? "" : BeeDesignerDocument.text(data, "token", ""));
				rejected.addProperty("message", safeMessage(exception));
				boolean unknown = request.action().equals("import_status") || request.action().equals("import_commit")
						|| request.action().equals("import_document");
				rejected.addProperty("uncertain", unknown);
				rejected.addProperty("phase", unknown ? "unknown" : "rejected");
				send(player, session, request.sequence(), unknown ? "import_state" : "import_rejected", rejected);
			} else if (request.action().equals("apply_status")) {
				JsonObject unknown = new JsonObject();
				unknown.addProperty("token", data == null ? "" : BeeDesignerDocument.text(data, "token", ""));
				unknown.addProperty("phase", "unknown");
				unknown.addProperty("uncertain", true);
				unknown.addProperty("message", safeMessage(exception));
				send(player, session, request.sequence(), "publication_state", unknown);
			} else {
				result(player, session, request.sequence(), false, safeMessage(exception));
			}
		}
	}

	private static void copyId(ServerPlayer player, Session session, long sequence, JsonObject document) {
		Set<String> reserved = new HashSet<>(BeeDesignerSources.managed.keySet());
		reserved.addAll(BeeDesignerEggs.bees());
		int attempt = document.has("_copy_attempt") ? document.remove("_copy_attempt").getAsInt() : 0;
		if (attempt < 0 || attempt >= 4096) {
			throw new IllegalArgumentException("designer.error.copy_limit");
		}
		document.remove("_copy_reserved_ids");
		ResourceLocation source = ResourceLocation.parse(document.get("id").getAsString());
		if (!source.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IllegalArgumentException("designer.error.id");
		}
		Set<String> recipes = new HashSet<>();
		player.server.getRecipeManager().getRecipes().forEach(holder -> recipes.add(holder.id().toString()));
		for (String project : BeeDesignerSources.managed.values()) {
			recipes.addAll(JsonParser.parseString(project).getAsJsonObject().getAsJsonObject("recipes").keySet());
		}
		String base = source.getNamespace() + ":" + source.getPath().substring(0, Math.min(80, source.getPath().length())) + "_copy";
		for (int skipped = 0; skipped < attempt; skipped++) {
			reserved.add(skipped == 0 ? base : base + "_" + (skipped + 1));
		}
		String candidate = BeeDesignerDocument.nextCopyId(document, reserved, recipes);
		int chosen = candidate.equals(base) ? 0 : Integer.parseInt(candidate.substring(base.length() + 1)) - 1;
		if (chosen >= 4096) {
			throw new IllegalArgumentException("designer.error.copy_limit");
		}
		JsonObject response = new JsonObject();
		response.addProperty("id", candidate);
		response.addProperty("attempt", chosen);
		send(player, session, sequence, "copy_id", response);
	}

	private static void prepareDeletion(ServerPlayer player, State state, Session session, long sequence, JsonObject data) {
		if (!canPublish(player) || state.busy || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.permission_or_busy");
		}
		ResourceLocation bee = ResourceLocation.parse(data.get("id").getAsString());
		boolean purging = data.has("purging") && data.get("purging").getAsBoolean();
		if (purging && (!BeeDesignerLifecycle.deleted(bee) || BeeDesignerLifecycle.purged(bee))) {
			throw new IllegalArgumentException("designer.error.recycle_not_restorable");
		}
		if (!purging && BeeDesignerLifecycle.deleted(bee)) {
			throw new IllegalArgumentException("designer.error.already_deleted");
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
		String managedSource = BeeDesignerSources.managed.get(bee.toString());
		state.busy = true;
		session.deletion = null;
		CompletableFuture.supplyAsync(() -> BeeDesignerReader.readSources(snapshot), state.io)
				.whenComplete((loaded, failure) -> player.server.execute(() -> {
					state.busy = false;
					if (!active(player, state, session) || !canPublish(player)) {
						return;
					}
					if (failure != null || snapshot.generation() != BeeDesignerSources.generation) {
						result(player, session, sequence, false, failure == null ? "designer.error.conflict" : safeMessage(failure));
						return;
					}
					if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE)) {
						loaded.remove(BeeDesignerMyriad.RECIPES_ONLY);
						loaded.addProperty("baseline", BeeDesignerDocument.fingerprint(loaded));
					}
					session.original = loaded.deepCopy();
					session.managedSource = managedSource;
					session.sourceVersion = snapshot.generation();
					session.deletion = new BeeDesignerLifecycle.Confirmation(loaded, snapshot.generation(), purging);
					send(player, session, sequence, "delete_review", session.deletion.review());
				}));
	}

	private static void confirmDeletion(ServerPlayer player, State state, Session session, long sequence, JsonObject data) {
		if (!canPublish(player) || state.busy || state.publication.blocked() || session.deletion == null) {
			throw new IllegalArgumentException("designer.error.delete_confirmation");
		}
		session.deletion.confirm(data);
		if (!session.deletion.ready()) {
			send(player, session, sequence, "delete_review", session.deletion.review());
			return;
		}
		if (session.sourceVersion != BeeDesignerSources.generation) {
			throw new IllegalArgumentException("designer.error.conflict");
		}
		send(player, session, sequence, "delete_staged", session.deletion.stage(System.currentTimeMillis(), player.getUUID()));
	}

	private static void history(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (!canPublish(player) || state.busy) {
			throw new IllegalArgumentException("designer.error.permission_or_busy");
		}
		int page = query.has("page") ? query.get("page").getAsInt() : 0;
		var world = player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
		state.busy = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return BeeDesignerStore.history(world, page);
			} catch (java.io.IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		}, state.io).whenComplete((data, failure) -> player.server.execute(() -> {
			state.busy = false;
			if (!active(player, state, session) || !canPublish(player)) {
				return;
			}
			if (failure != null) {
				result(player, session, sequence, false, safeMessage(failure));
			} else {
				send(player, session, sequence, "history", data);
			}
		}));
	}

	private static void revision(ServerPlayer player, State state, Session session, long sequence, String action, JsonObject query) {
		if (!canPublish(player) || state.busy || state.publication.running()) {
			throw new IllegalArgumentException("designer.error.permission_or_busy");
		}
		String revision = query.get("revision").getAsString();
		String bee = BeeDesignerDocument.text(query, "id", "");
		int requested = query.has("page") ? query.get("page").getAsInt() : 0;
		var world = player.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
		state.busy = true;
		CompletableFuture.supplyAsync(() -> {
			try {
				return BeeDesignerStore.readRevision(world, revision);
			} catch (java.io.IOException exception) {
				throw new java.io.UncheckedIOException(exception);
			}
		}, state.io).whenComplete((documents, failure) -> player.server.execute(() -> {
			state.busy = false;
			if (!active(player, state, session) || !canPublish(player)) {
				return;
			}
			if (failure != null) {
				result(player, session, sequence, false, safeMessage(failure));
				return;
			}
			if (action.equals("revision_bee")) {
				if (!documents.containsKey(bee)) {
					result(player, session, sequence, false, "designer.error.revision_bee");
					return;
				}
				send(player, session, sequence, action, JsonParser.parseString(documents.get(bee)).getAsJsonObject());
				return;
			}
			List<String> ids = documents.keySet().stream().sorted().toList();
			int pages = Math.max(1, (ids.size() + 15) / 16);
			int page = Math.max(0, Math.min(requested, pages - 1));
			JsonObject response = new JsonObject();
			response.addProperty("revision", revision);
			response.addProperty("page", page);
			response.addProperty("pages", pages);
			JsonArray entries = new JsonArray();
			ids.stream().skip((long) page * 16).limit(16).forEach(id -> {
				JsonObject entry = new JsonObject();
				entry.addProperty("id", id);
				entry.add("names", JsonParser.parseString(documents.get(id)).getAsJsonObject().get("names"));
				entries.add(entry);
			});
			response.add("entries", entries);
			send(player, session, sequence, action, response);
		}));
	}

	private static JsonObject status(State state) {
		JsonObject status = state.publication.status();
		status.addProperty("busy", state.busy || status.get("busy").getAsBoolean());
		if (state.uncertain) {
			status.addProperty("uncertain", true);
			status.addProperty("phase", "myriad_uncertain");
			status.addProperty("message", "designer.error.myriad_unknown");
		}
		return status;
	}

	/** 与万象属性保存共用服务器文件队列；回执回到原服务器，停止后不再访问旧配置。 */
	public static void saveFilterConfig(MinecraftServer server, ModConfig.FilterMode mode, List<String> types, Consumer<String> completed) {
		if (!server.isSameThread()) {
			throw new IllegalStateException("Filter settings require the server thread");
		}
		State state = STATES.computeIfAbsent(server, State::new);
		if (state.busy || state.stopped || state.publication.running()) {
			throw new IllegalStateException("designer.error.busy");
		}
		ModConfig.SERVER.myriadCreationsFilteredBeeTypes.set(List.copyOf(types));
		ModConfig.SERVER.myriadCreationsFilterMode.set(mode);
		MyriadCreationsEventHandler.invalidateFilterCache();
		state.busy = true;
		CompletableFuture.runAsync(ModConfig::saveGameplayServerSpec, state.io)
				.whenComplete((unused, failure) -> server.execute(() -> {
					state.busy = false;
					if (state.stopped || STATES.get(server) != state) {
						return;
					}
					if (failure != null) {
						ProductiveBeesGenesis.LOGGER.error("Filter settings persistence needs inspection", failure);
						completed.accept("designer.filter_save_unknown");
					} else {
						completed.accept("designer.filter_saved");
					}
				}));
	}

	private static void saveMyriad(ServerPlayer player, State state, Session session, long sequence, JsonObject data) {
		Map<String, Object> values = BeeDesignerMyriad.validate(data);
		BeeDesignerMyriad.validateResources(values, player.server);
		BeeDesignerMyriad.apply(values);
		state.busy = true;
		List<String> selected = player.server.getPackRepository().getSelectedPacks().stream().map(pack -> pack.getId()).toList();
		CompletableFuture.supplyAsync(() -> {
			com.ayoshiko.productivebeesgenesis.config.ModConfig.saveGameplayServerSpec();
			return player.server.reloadResources(selected);
		}, state.io).thenCompose(value -> value)
				.whenComplete((unused, failure) -> player.server.execute(() -> {
					state.busy = false;
					if (state.stopped) {
						return;
					}
					if (failure != null) {
						state.uncertain = true;
						ProductiveBeesGenesis.LOGGER.error("Myriad designer settings need inspection", failure);
						send(player, session, sequence, "myriad", BeeDesignerMyriad.snapshot());
						result(player, session, sequence, false, safeMessage(failure));
					} else {
						state.uncertain = false;
						send(player, session, sequence, "myriad", BeeDesignerMyriad.snapshot());
						result(player, session, sequence, true, "designer.status.myriad_saved");
					}
				}));
	}

	private static void importReceipt(ServerPlayer player, Session session, long sequence, String action, JsonObject staged) {
		JsonObject receipt = new JsonObject();
		receipt.addProperty("token", session.importBatch.token.toString());
		receipt.addProperty("count", session.importBatch.size());
		receipt.addProperty("expected", session.importBatch.expected());
		receipt.addProperty("request_digest", session.importBatch.requestDigest());
		if (staged != null) {
			receipt.add("id", staged.get("id"));
		}
		send(player, session, sequence, action, receipt);
	}

	private static void importEntry(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (!canPublish(player) || state.busy || state.publication.blocked() || session.exporting || state.exportReads >= 4
				|| session.importBatch == null) {
			throw new IllegalArgumentException("designer.error.permission_or_busy");
		}
		BeeDesignerImportBatch batch = session.importBatch;
		batch.check(query);
		JsonObject source = query.getAsJsonObject("document").deepCopy();
		ResourceLocation bee = ResourceLocation.parse(source.get("id").getAsString());
		if (BeeReloadListener.INSTANCE.getData(bee) == null && !BeeDesignerNativeBees.contains(bee)) {
			batch.add(source, null);
			importReceipt(player, session, sequence, "import_staged", batch.document(bee.toString()));
			return;
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
		session.exporting = true;
		state.exportReads++;
		CompletableFuture.supplyAsync(() -> BeeDesignerReader.readSources(snapshot), state.io)
				.whenComplete((original, failure) -> player.server.execute(() -> {
					session.exporting = false;
					state.exportReads--;
					if (!active(player, state, session) || session.importBatch != batch) {
						return;
					}
					if (failure != null) {
						result(player, session, sequence, false, safeMessage(failure));
						return;
					}
					try {
						batch.check(query);
						if (BeeDesignerMyriad.recipesOnly(source)) {
							BeeDesignerMyriad.prepareRecipes(original);
						}
						batch.add(source, original);
						importReceipt(player, session, sequence, "import_staged", batch.document(bee.toString()));
					} catch (RuntimeException invalid) {
						result(player, session, sequence, false, safeMessage(invalid));
					}
				}));
	}

	private static void importBegin(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (!canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		UUID token = UUID.fromString(query.get("token").getAsString());
		state.publication.query(token, player.getUUID(), "", () -> active(player, state, session) && canPublish(player), record -> {
			if (record != null) {
				operationReceipt(player, session, sequence, record, false);
				return;
			}
			try {
				if (state.busy || state.publication.blocked() || session.exporting) {
					throw new IllegalArgumentException("designer.error.permission_or_busy");
				}
				session.importBatch = new BeeDesignerImportBatch(query);
				importReceipt(player, session, sequence, "import_ready", null);
			} catch (RuntimeException failure) {
				operationRejected(player, session, sequence, token, false, safeMessage(failure));
			}
		}, failure -> operationUnknown(player, session, sequence, token, false, failure));
	}

	private static void importCommit(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (!canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		UUID token = UUID.fromString(query.get("token").getAsString());
		String requestDigest = BeeDesignerDocument.text(query, "request_digest", "");
		if (!requestDigest.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		state.publication.query(token, player.getUUID(), requestDigest, () -> active(player, state, session) && canPublish(player), record -> {
			if (record != null) {
				operationReceipt(player, session, sequence, record, false);
				return;
			}
			try {
				if (state.busy || state.publication.blocked() || session.exporting || session.importBatch == null) {
					throw new IllegalArgumentException("designer.error.permission_or_busy");
				}
				BeeDesignerImportBatch batch = session.importBatch;
				batch.check(query);
				if (!requestDigest.equals(batch.requestDigest())) {
					throw new IllegalArgumentException("designer.error.collection_changed");
				}
				Map<String, String> projects = batch.validate(player.server);
				Map<String, String> files = BeeDesignerCompiler.files(projects);
				BeeDesignerOperation identity = BeeDesignerOperation.create(token, player.getUUID(), requestDigest, "import",
						batch.baselines(), publishedDocuments(files));
				session.importBatch = null;
				state.publication.start(batch.first(), files, false, identity,
						() -> active(player, state, session) && canPublish(player),
						() -> batch.generation == BeeDesignerSources.generation, path -> {}, loaded -> {
							if (active(player, state, session)) {
								operationStatus(player, state, session, sequence, query, false);
							}
							player.server.getPlayerList().getPlayers().forEach(BeeDesignerServer::sendNames);
						}, message -> {
							if (active(player, state, session)) {
								operationStatus(player, state, session, sequence, query, false);
							}
						});
			} catch (RuntimeException failure) {
				if (state.publication.running()) {
					operationUnknown(player, session, sequence, token, false, safeMessage(failure));
				} else {
					operationRejected(player, session, sequence, token, false, safeMessage(failure));
				}
			}
		}, failure -> operationUnknown(player, session, sequence, token, false, failure));
	}

	private static Map<String, String> publishedDocuments(Map<String, String> files) {
		JsonObject index = JsonParser.parseString(files.get("data/productivebeesgenesis/bee_designer/index.json")).getAsJsonObject();
		Map<String, String> documents = new LinkedHashMap<>();
		for (var entry : index.entrySet()) {
			JsonObject document = entry.getValue().getAsJsonObject().deepCopy();
			document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
			BeeDesignerProtocol.parse(document.toString());
			documents.put(entry.getKey(), entry.getValue().toString());
		}
		return Map.copyOf(documents);
	}

	private static void operationStatus(ServerPlayer player, State state, Session session, long sequence, JsonObject query, boolean single) {
		if (!canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		UUID token = UUID.fromString(query.get("token").getAsString());
		String digest = BeeDesignerDocument.text(query, "request_digest", "");
		state.publication.query(token, player.getUUID(), digest, () -> active(player, state, session) && canPublish(player),
				record -> operationReceipt(player, session, sequence, record == null ? BeeDesignerOperation.unknown(token) : record, single),
				failure -> operationUnknown(player, session, sequence, token, single, failure));
	}

	private static void operationDocument(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (!canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		UUID token = UUID.fromString(query.get("token").getAsString());
		String digest = BeeDesignerDocument.text(query, "request_digest", "");
		String id = ResourceLocation.parse(query.get("id").getAsString()).toString();
		int offset = query.has("offset") ? query.get("offset").getAsInt() : 0;
		state.publication.document(token, player.getUUID(), digest, id, offset, () -> active(player, state, session) && canPublish(player),
				document -> {
					if (document == null) {
						operationUnknown(player, session, sequence, token, false, "designer.error.collection_changed");
					} else {
						send(player, session, sequence, "import_document", document);
					}
				}, failure -> operationUnknown(player, session, sequence, token, false, failure));
	}

	private static void operationReceipt(ServerPlayer player, Session session, long sequence, JsonObject record, boolean single) {
		JsonObject receipt = BeeDesignerOperation.receipt(record);
		String phase = BeeDesignerDocument.text(receipt, "phase", "unknown");
		String action = "import_state";
		if (single) {
			action = "publication_state";
		} else if (phase.equals("committed")) {
			action = "import_applied";
		} else if (phase.equals("rejected")) {
			action = "import_rejected";
		}
		send(player, session, sequence, action, receipt);
	}

	private static void operationRejected(ServerPlayer player, Session session, long sequence, UUID token, boolean single, String message) {
		JsonObject rejected = new JsonObject();
		rejected.addProperty("token", token.toString());
		rejected.addProperty("phase", "rejected");
		rejected.addProperty("message", message);
		operationReceipt(player, session, sequence, rejected, single);
	}

	private static void operationUnknown(ServerPlayer player, Session session, long sequence, UUID token, boolean single, String message) {
		JsonObject unknown = BeeDesignerOperation.unknown(token);
		unknown.addProperty("message", message);
		operationReceipt(player, session, sequence, unknown, single);
	}

	private static void exportSnapshot(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (state.busy || state.publication.blocked() || session.exporting || state.exportReads >= 4) {
			throw new IllegalArgumentException("designer.error.busy");
		}
		ResourceLocation bee = ResourceLocation.parse(query.get("id").getAsString());
		if (query.has("generation") && query.get("generation").getAsLong() != BeeDesignerSources.generation) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
		session.exporting = true;
		state.exportReads++;
		CompletableFuture.supplyAsync(() -> BeeDesignerReader.readSources(snapshot), state.io)
				.whenComplete((loaded, failure) -> player.server.execute(() -> {
					session.exporting = false;
					state.exportReads--;
					if (!active(player, state, session)) {
						return;
					}
					if (failure != null || snapshot.generation() != BeeDesignerSources.generation) {
						result(player, session, sequence, false, failure == null ? "designer.error.conflict" : safeMessage(failure));
						return;
					}
					try {
						if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE) && !BeeDesignerLifecycle.deleted(bee)) {
							BeeDesignerMyriad.prepareRecipes(loaded);
						}
						loaded.addProperty("_editor_export_generation", snapshot.generation());
						send(player, session, sequence, "export_snapshot", loaded);
					} catch (RuntimeException invalid) {
						result(player, session, sequence, false, safeMessage(invalid));
					}
				}));
	}

	private static void detail(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (state.busy || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.busy");
		}
		ResourceLocation bee = ResourceLocation.parse(query.get("id").getAsString());
		if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE) && !BeeDesignerLifecycle.deleted(bee)
				&& !(query.has("lifecycle") && query.get("lifecycle").getAsBoolean())
				&& !(query.has("recipes_only") && query.get("recipes_only").getAsBoolean())) {
			if (query.has("copy") && query.get("copy").getAsBoolean()) {
				send(player, session, sequence, "detail", BeeDesignerMyriad.ordinaryTemplate());
			} else {
				send(player, session, sequence, "myriad", BeeDesignerMyriad.snapshot());
			}
			return;
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
		String managedSource = BeeDesignerSources.managed.get(bee.toString());
		state.busy = true;
		CompletableFuture.supplyAsync(() -> BeeDesignerReader.readSources(snapshot), state.io)
				.whenComplete((loaded, failure) -> player.server.execute(() -> {
					state.busy = false;
					if (!active(player, state, session)) {
						return;
					}
					if (failure != null || snapshot.generation() != BeeDesignerSources.generation) {
						result(player, session, sequence, false, failure == null ? "designer.error.conflict" : safeMessage(failure));
						return;
					}
					if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE) && query.has("recipes_only") && query.get("recipes_only").getAsBoolean()) {
						BeeDesignerMyriad.prepareRecipes(loaded);
					} else if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE) && query.has("lifecycle") && query.get("lifecycle").getAsBoolean()) {
						loaded.remove(BeeDesignerMyriad.RECIPES_ONLY);
						loaded.addProperty("baseline", BeeDesignerDocument.fingerprint(loaded));
					}
					session.original = loaded.deepCopy();
					session.managedSource = managedSource;
					session.sourceVersion = snapshot.generation();
					send(player, session, sequence, "detail", loaded);
				}));
	}

	private static void publish(ServerPlayer player, State state, Session session, BeeDesignerProtocol.Request request, JsonObject document) {
		if (!request.action().equals("apply")) {
			publishNew(player, state, session, request, document, null, "");
			return;
		}
		if (!canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		UUID token = document.has("_publication_token") ? UUID.fromString(document.remove("_publication_token").getAsString()) : UUID.randomUUID();
		JsonObject submitted = new JsonObject();
		submitted.add("document", document.deepCopy());
		String digest = BeeDesignerDocument.fingerprint(submitted);
		state.publication.query(token, player.getUUID(), digest, () -> active(player, state, session) && canPublish(player), record -> {
			if (record != null) {
				operationReceipt(player, session, request.sequence(), record, true);
				return;
			}
			try {
				publishNew(player, state, session, request, document, token, digest);
			} catch (RuntimeException failure) {
				if (state.publication.running()) {
					operationUnknown(player, session, request.sequence(), token, true, safeMessage(failure));
				} else {
					operationRejected(player, session, request.sequence(), token, true, safeMessage(failure));
				}
			}
		}, failure -> operationUnknown(player, session, request.sequence(), token, true, failure));
	}

	private static void publishNew(ServerPlayer player, State state, Session session, BeeDesignerProtocol.Request request,
			JsonObject document, UUID token, String requestDigest) {
		if (state.busy || state.uncertain || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.busy");
		}
		boolean check = request.action().equals("check");
		boolean export = request.action().equals("export");
		String checkedFingerprint = check ? BeeDesignerDocument.fingerprint(document) : "";
		if (!check && !canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		String id = document.get("id").getAsString();
		ResourceLocation bee = ResourceLocation.parse(id);
		if (BeeDesignerEggs.bees().stream().anyMatch(other -> !other.equals(id)
				&& BeeDesignerDocument.translationKey(other).equals(BeeDesignerDocument.translationKey(id)))) {
			throw new IllegalArgumentException("Another bee already uses the PB display name key: " + bee.getPath());
		}
		if (BeeDesignerNativeBees.contains(bee) || BeeDesignerNativeBees.recipesOnly(document)) {
			BeeDesignerNativeBees.validate(document);
		}
		if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE)) {
			if (session.original != null && BeeDesignerMyriad.recipesOnly(session.original) && !BeeDesignerLifecycle.deleted(document)) {
				BeeDesignerMyriad.validateRecipes(session.original, document);
			} else if (!BeeDesignerLifecycle.deleted(document) && (session.original == null || !BeeDesignerLifecycle.deleted(session.original))) {
				throw new IllegalArgumentException("Use Myriad settings for its config-owned attributes");
			}
		}
		String baseline = BeeDesignerDocument.text(document, "baseline", "new");
		BeeDesignerLifecycle.validateTransition(session.original, document, session.deletion);
		BeeDesignerLifecycle.validateSharedChanges(baseline.equals("new") ? null : session.original, document, Map.of(id, document));
		boolean deleting = BeeDesignerLifecycle.deleted(document);
		if (deleting && (session.original == null || !BeeDesignerLifecycle.deleted(session.original))
				&& (session.deletion == null || !session.deletion.permits(document))) {
			throw new IllegalArgumentException("designer.error.delete_confirmation");
		}
		if (baseline.equals("new")) {
			if (BeeReloadListener.INSTANCE.getData(bee) != null || BeeDesignerNativeBees.contains(bee) || BeeDesignerSources.managed.containsKey(id)) {
				throw new IllegalArgumentException("designer.error.exists");
			}
			document.addProperty("definition_path", id);
			document.remove("disabled_recipes");
		} else {
			JsonObject original = session.original;
			if (original == null || !id.equals(original.get("id").getAsString())
					|| !baseline.equals(original.get("baseline").getAsString())
					|| (session.sourceVersion != BeeDesignerSources.generation
							&& (!Objects.equals(session.managedSource, BeeDesignerSources.managed.get(id))
									|| BeeDesignerMyriad.recipesOnly(original) || !BeeDesignerReader.unchanged(player.server, original)))) {
				throw new IllegalArgumentException("designer.error.conflict");
			}
			session.sourceVersion = BeeDesignerSources.generation;
			document.add("definition_path", original.get("definition_path"));
			document.add("_editor_origin", original.get("_editor_origin").deepCopy());
			JsonArray disabled = original.has("disabled_recipes") ? original.getAsJsonArray("disabled_recipes").deepCopy() : new JsonArray();
			for (String recipe : original.getAsJsonObject("recipes").keySet()) {
				if (!document.getAsJsonObject("recipes").has(recipe)) {
					disabled.add(recipe);
				}
			}
			document.add("disabled_recipes", disabled);
		}
		// 停用清单来自服务端导入快照；新草稿退役但从未发布的节点无需覆盖其它包。
		JsonObject owned = baseline.equals("new") ? new JsonObject() : session.original.getAsJsonObject("recipes");
		for (String recipe : document.getAsJsonObject("recipes").keySet()) {
			if (player.server.getRecipeManager().byKey(ResourceLocation.parse(recipe)).isPresent()
					&& !owned.has(recipe)) {
				throw new IllegalArgumentException("Recipe ID already belongs to other content: " + recipe);
			}
		}
		if (check) {
			JsonObject diagnostics = BeeDesignerDiagnostics.check(document, player.server);
			// 诊断对应客户端提交；服务端补齐的停用配方等受管字段不应使刚通过的结果失效。
			diagnostics.addProperty("fingerprint", checkedFingerprint);
			send(player, session, request.sequence(), "diagnostics", diagnostics);
			return;
		}
		BeeDesignerCompiler.validate(document, player.server);
		Map<String, String> documents = new LinkedHashMap<>(BeeDesignerSources.managed);
		if (!documents.containsKey(id) && documents.size() >= BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("designer.error.collection_count");
		}
		documents.put(id, document.toString());
		Map<String, String> files = BeeDesignerCompiler.files(documents);
		long version = BeeDesignerSources.generation;
		result(player, session, request.sequence(), true, export ? "designer.status.exporting" : "designer.status.applying");
		BeeDesignerOperation identity = export ? null : BeeDesignerOperation.create(token, player.getUUID(), requestDigest, "apply",
				Map.of(id, baseline), publishedDocuments(files));
		state.publication.start(document, files, export, identity, () -> active(player, state, session) && canPublish(player),
				() -> version == BeeDesignerSources.generation, path -> {
					JsonObject response = new JsonObject();
					response.addProperty("path", path.toString());
					response.addProperty("names_path", path.resolveSibling(path.getFileName().toString().replace(".zip", "-names.zip")).toString());
					send(player, session, request.sequence(), "exported", response);
				}, loaded -> {
					loaded.addProperty("baseline", BeeDesignerDocument.fingerprint(loaded));
					if (active(player, state, session)) {
						session.original = loaded.deepCopy();
						session.managedSource = BeeDesignerSources.managed.get(id);
						session.sourceVersion = BeeDesignerSources.generation;
						session.deletion = null;
						send(player, session, request.sequence(), "applied", loaded);
					}
					player.server.getPlayerList().getPlayers().forEach(BeeDesignerServer::sendNames);
				}, message -> {
					if (active(player, state, session)) {
						result(player, session, request.sequence(), false, message);
						if (!export) {
							JsonObject query = new JsonObject();
							query.addProperty("token", token.toString());
							operationStatus(player, state, session, request.sequence(), query, true);
						}
					}
				});
		if (!export) {
			JsonObject query = new JsonObject();
			query.addProperty("token", token.toString());
			operationStatus(player, state, session, request.sequence(), query, true);
		}
	}

	private static boolean active(ServerPlayer player, State state, Session session) {
		if (state.stopped || state.sessions.get(player.getUUID()) != session) {
			return false;
		}
		if (player.hasDisconnected() || !holding(player) || System.nanoTime() - session.lastAccess > SESSION_LIFETIME_NANOS) {
			state.sessions.remove(player.getUUID(), session);
			return false;
		}
		return true;
	}

	public static void sendNames(ServerPlayer player) {
		UUID batch = UUID.randomUUID();
		long generation = BeeDesignerSources.generation;
		JsonObject header = new JsonObject();
		header.addProperty("count", BeeDesignerSources.managed.size());
		PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(batch, generation, "names_reset", header.toString()));
		for (String value : BeeDesignerSources.managed.values()) {
			JsonObject document = JsonParser.parseString(value).getAsJsonObject();
			JsonObject names = new JsonObject();
			names.addProperty("id", document.get("id").getAsString());
			names.add("names", document.get("names"));
			names.addProperty("deleted", BeeDesignerLifecycle.deleted(document));
			PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(batch, generation, "names", names.toString()));
		}
		PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(batch, generation, "names_complete", header.toString()));
	}

	private static void result(ServerPlayer player, Session session, long sequence, boolean success, String message) {
		JsonObject result = new JsonObject();
		result.addProperty("success", success);
		result.addProperty("message", message);
		send(player, session, sequence, "result", result);
	}

	private static void send(ServerPlayer player, Session session, long sequence, String action, JsonObject data) {
		State state = STATES.get(player.server);
		if (state == null || !active(player, state, session)) {
			return;
		}
		String json = data.toString();
		BeeDesignerProtocol.checkSize(json);
		PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(session.token, sequence, action, json));
	}

	public static String safeMessage(Throwable exception) {
		Throwable cause = exception;
		Set<Throwable> visited = new HashSet<>();
		while (visited.add(cause)) {
			if (cause.getMessage() != null && cause.getMessage().startsWith("designer.")) {
				String translated = cause.getMessage();
				return translated.length() > 400 ? translated.substring(0, 400) : translated;
			}
			if (cause.getCause() == null) {
				break;
			}
			cause = cause.getCause();
		}
		String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
		return "designer.error.operation_detail|" + (message.length() > 350 ? message.substring(0, 350) : message);
	}

	@SubscribeEvent
	public static void tick(ServerTickEvent.Post event) {
		MinecraftServer server = event.getServer();
		State state = STATES.get(server);
		if (state == null || state.sessions.isEmpty()) {
			return;
		}
		long now = System.nanoTime();
		state.sessions.entrySet().removeIf(entry -> {
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			return player == null || player.hasDisconnected() || !holding(player)
					|| now - entry.getValue().lastAccess > SESSION_LIFETIME_NANOS;
		});
	}

	@SubscribeEvent
	public static void login(PlayerEvent.PlayerLoggedInEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			sendNames(player);
		}
	}

	@SubscribeEvent
	public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
		if (event.getEntity() instanceof ServerPlayer player) {
			State state = STATES.get(player.server);
			if (state != null) {
				state.sessions.remove(player.getUUID());
			}
		}
	}

	@SubscribeEvent
	public static void stop(ServerStoppedEvent event) {
		BeeDesignerRecipeIndex.clear(event.getServer());
		BeeDesignerFilterCatalog.clear(event.getServer());
		BeeDesignerReader.clear();
		State state = STATES.remove(event.getServer());
		if (state != null) {
			state.stopped = true;
			state.sessions.clear();
			state.publication.stop();
		}
		BeeDesignerSources.definitions = Map.of();
		BeeDesignerSources.managed = Map.of();
		BeeDesignerSources.sourcePacks = Map.of();
		BeeDesignerSources.deleted = Set.of();
	}

}
