package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
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
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

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

		private State(MinecraftServer server) {
			publication = new BeeDesignerPublication(server);
			io = publication.io;
		}
	}

	private static final class Session {
		private final UUID token = UUID.randomUUID();
		private long lastSequence;
		private long lastAccess = System.nanoTime();
		private long sourceVersion;
		private JsonObject original;
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
		if (session == null || !session.token.equals(request.session()) || !holding(player)
				|| request.sequence() <= session.lastSequence
				|| System.nanoTime() - session.lastAccess > SESSION_LIFETIME_NANOS) {
			return;
		}
		long now = System.nanoTime();
		if (session.lastSequence > 0 && now - session.lastAccess < 100_000_000) {
			result(player, session, request.sequence(), false, "designer.error.rate");
			return;
		}
		session.lastSequence = request.sequence();
		session.lastAccess = now;
		try {
			JsonObject data = BeeDesignerProtocol.parse(request.json());
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
				case "catalog" -> catalog(player, session, request.sequence(), data);
				case "materials" -> send(player, session, request.sequence(), "materials", BeeDesignerCatalog.page(player.server, data));
				case "bee_data" -> send(player, session, request.sequence(), "bee_data",
						BeeDesignerReader.beeData(player.server, ResourceLocation.parse(data.get("id").getAsString())));
				case "detail" -> detail(player, state, session, request.sequence(), data);
				case "copy_id" -> copyId(player, session, request.sequence(), data);
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
			result(player, session, request.sequence(), false, safeMessage(exception));
		}
	}

	private static void catalog(ServerPlayer player, Session session, long sequence, JsonObject query) {
		String search = BeeDesignerDocument.text(query, "search", "").toLowerCase(java.util.Locale.ROOT);
		if (search.length() > 128 || query.has("names") && query.getAsJsonArray("names").size() > 128) {
			throw new IllegalArgumentException("Search exceeds the catalog budget");
		}
		Set<String> names = new HashSet<>();
		Map<String, Integer> preferred = new LinkedHashMap<>();
		if (query.has("preferred")) {
			if (query.getAsJsonArray("preferred").size() > 256) {
				throw new IllegalArgumentException("designer.error.draft_limit");
			}
			for (var value : query.getAsJsonArray("preferred")) {
				String id = ResourceLocation.parse(value.getAsString()).toString();
				preferred.putIfAbsent(id, preferred.size());
			}
		}
		if (query.has("names")) {
			query.getAsJsonArray("names").forEach(value -> names.add(value.getAsString()));
		}
		Set<String> candidates = new HashSet<>(preferred.keySet());
		BeeReloadListener.INSTANCE.getData().keySet().forEach(id -> candidates.add(id.toString()));
		boolean trash = query.has("trash") && query.get("trash").getAsBoolean();
		List<String> ids = candidates.stream().filter(id -> BeeDesignerLifecycle.deleted(ResourceLocation.parse(id)) == trash
					|| !trash && preferred.containsKey(id))
				.filter(id -> id.contains(search) || names.contains(id))
				.sorted(java.util.Comparator.comparingInt((String id) -> preferred.getOrDefault(id, Integer.MAX_VALUE)).thenComparing(id -> id)).toList();
		int pages = Math.max(1, (ids.size() + 31) / 32);
		int page = Math.max(0, Math.min(pages - 1, query.has("page") ? query.get("page").getAsInt() : 0));
		JsonObject data = new JsonObject();
		JsonArray entries = new JsonArray();
		ids.stream().skip((long) page * 32).limit(32).forEach(entries::add);
		data.add("ids", entries);
		JsonObject origins = new JsonObject();
		ids.stream().skip((long) page * 32).limit(32).forEach(id -> origins.add(id, BeeDesignerOrigins.describe(ResourceLocation.parse(id))));
		data.add("origins", origins);
		data.addProperty("page", page);
		data.addProperty("pages", pages);
		data.addProperty("total", ids.size());
		send(player, session, sequence, "catalog", data);
	}

	private static void copyId(ServerPlayer player, Session session, long sequence, JsonObject document) {
		Set<String> reserved = new HashSet<>(BeeDesignerSources.managed.keySet());
		BeeReloadListener.INSTANCE.getData().keySet().forEach(id -> reserved.add(id.toString()));
		if (document.has("_copy_reserved_ids")) {
			JsonArray drafts = document.remove("_copy_reserved_ids").getAsJsonArray();
			if (drafts.size() > 256) {
				throw new IllegalArgumentException("designer.error.draft_limit");
			}
			drafts.forEach(value -> reserved.add(ResourceLocation.parse(value.getAsString()).toString()));
		}
		ResourceLocation source = ResourceLocation.parse(document.get("id").getAsString());
		if (!source.getPath().matches("[a-z0-9_.-]{1,96}")) {
			throw new IllegalArgumentException("designer.error.id");
		}
		Set<String> recipes = new HashSet<>();
		player.server.getRecipeManager().getRecipes().forEach(holder -> recipes.add(holder.id().toString()));
		for (String project : BeeDesignerSources.managed.values()) {
			recipes.addAll(JsonParser.parseString(project).getAsJsonObject().getAsJsonObject("recipes").keySet());
		}
		JsonObject response = new JsonObject();
		response.addProperty("id", BeeDesignerDocument.nextCopyId(document, reserved, recipes));
		send(player, session, sequence, "copy_id", response);
	}

	private static void prepareDeletion(ServerPlayer player, State state, Session session, long sequence, JsonObject data) {
		if (!canPublish(player) || state.busy || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.permission_or_busy");
		}
		ResourceLocation bee = ResourceLocation.parse(data.get("id").getAsString());
		if (BeeDesignerLifecycle.deleted(bee)) {
			throw new IllegalArgumentException("designer.error.already_deleted");
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
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
					session.original = loaded.deepCopy();
					session.sourceVersion = snapshot.generation();
					session.deletion = new BeeDesignerLifecycle.Confirmation(loaded, snapshot.generation());
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
		send(player, session, sequence, "delete_staged", BeeDesignerLifecycle.retire(session.original, System.currentTimeMillis(), player.getUUID()));
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

	private static void saveMyriad(ServerPlayer player, State state, Session session, long sequence, JsonObject data) {
		Map<String, Object> values = BeeDesignerMyriad.validate(data);
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

	private static void detail(ServerPlayer player, State state, Session session, long sequence, JsonObject query) {
		if (state.busy || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.busy");
		}
		ResourceLocation bee = ResourceLocation.parse(query.get("id").getAsString());
		if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE) && !BeeDesignerLifecycle.deleted(bee)
				&& !(query.has("lifecycle") && query.get("lifecycle").getAsBoolean())) {
			if (query.has("copy") && query.get("copy").getAsBoolean()) {
				send(player, session, sequence, "detail", BeeDesignerMyriad.ordinaryTemplate());
			} else {
				send(player, session, sequence, "myriad", BeeDesignerMyriad.snapshot());
			}
			return;
		}
		BeeDesignerReader.Snapshot snapshot = BeeDesignerReader.snapshot(player.server, bee);
		state.busy = true;
		CompletableFuture.supplyAsync(() -> BeeDesignerReader.readSources(snapshot), state.io)
				.whenComplete((loaded, failure) -> player.server.execute(() -> {
					state.busy = false;
					if (state.stopped || state.sessions.get(player.getUUID()) != session) {
						return;
					}
					if (failure != null || snapshot.generation() != BeeDesignerSources.generation) {
						result(player, session, sequence, false, failure == null ? "designer.error.conflict" : safeMessage(failure));
						return;
					}
					session.original = loaded.deepCopy();
					session.sourceVersion = snapshot.generation();
					send(player, session, sequence, "detail", loaded);
				}));
	}

	private static void publish(ServerPlayer player, State state, Session session, BeeDesignerProtocol.Request request, JsonObject document) {
		if (state.busy || state.uncertain || state.publication.blocked()) {
			throw new IllegalArgumentException("designer.error.busy");
		}
		boolean check = request.action().equals("check");
		boolean export = request.action().equals("export");
		if (!check && !canPublish(player)) {
			throw new IllegalArgumentException("designer.error.permission");
		}
		String id = document.get("id").getAsString();
		ResourceLocation bee = ResourceLocation.parse(id);
		if (BeeReloadListener.INSTANCE.getData().keySet().stream().anyMatch(other -> !other.equals(bee)
				&& BeeDesignerDocument.translationKey(other.toString()).equals(BeeDesignerDocument.translationKey(id)))) {
			throw new IllegalArgumentException("Another bee already uses the PB display name key: " + bee.getPath());
		}
		if (bee.equals(PBConstants.MYRIADCREATIONS_TYPE)) {
			if (!BeeDesignerLifecycle.deleted(document) && (session.original == null || !BeeDesignerLifecycle.deleted(session.original))) {
				throw new IllegalArgumentException("Use Myriad settings for its config-owned attributes");
			}
		}
		String baseline = BeeDesignerDocument.text(document, "baseline", "new");
		boolean deleting = BeeDesignerLifecycle.deleted(document);
		if (deleting && (session.original == null || !BeeDesignerLifecycle.deleted(session.original))
				&& (session.deletion == null || !session.deletion.permits(document))) {
			throw new IllegalArgumentException("designer.error.delete_confirmation");
		}
		if (baseline.equals("new")) {
			if (BeeReloadListener.INSTANCE.getData(bee) != null || BeeDesignerSources.managed.containsKey(id)) {
				throw new IllegalArgumentException("designer.error.exists");
			}
			document.addProperty("definition_path", id);
			document.remove("disabled_recipes");
		} else {
			JsonObject original = session.original;
			if (original == null || !id.equals(original.get("id").getAsString())
					|| !baseline.equals(original.get("baseline").getAsString())
					|| (session.sourceVersion != BeeDesignerSources.generation && !BeeDesignerReader.unchanged(player.server, original))) {
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
			send(player, session, request.sequence(), "diagnostics", BeeDesignerDiagnostics.check(document, player.server));
			return;
		}
		BeeDesignerCompiler.validate(document, player.server);
		Map<String, String> documents = new LinkedHashMap<>(BeeDesignerSources.managed);
		if (!documents.containsKey(id) && documents.size() >= 128) {
			throw new IllegalArgumentException("Managed pack supports at most 128 bees");
		}
		documents.put(id, document.toString());
		Map<String, String> files = BeeDesignerCompiler.files(documents);
		long version = BeeDesignerSources.generation;
		result(player, session, request.sequence(), true, export ? "designer.status.exporting" : "designer.status.applying");
		state.publication.start(document, files, export, () -> active(player, state, session) && canPublish(player),
				() -> version == BeeDesignerSources.generation, path -> {
					JsonObject response = new JsonObject();
					response.addProperty("path", path.toString());
					response.addProperty("names_path", path.resolveSibling(path.getFileName().toString().replace(".zip", "-names.zip")).toString());
					send(player, session, request.sequence(), "exported", response);
				}, loaded -> {
					loaded.addProperty("baseline", BeeDesignerDocument.fingerprint(loaded));
					if (active(player, state, session)) {
						session.original = loaded.deepCopy();
						session.sourceVersion = BeeDesignerSources.generation;
						session.deletion = null;
						send(player, session, request.sequence(), "applied", loaded);
					}
					player.server.getPlayerList().getPlayers().forEach(BeeDesignerServer::sendNames);
				}, message -> {
					if (active(player, state, session)) {
						result(player, session, request.sequence(), false, message);
					}
				});
	}

	private static boolean active(ServerPlayer player, State state, Session session) {
		return !state.stopped && !player.hasDisconnected() && state.sessions.get(player.getUUID()) == session
				&& holding(player) && System.nanoTime() - session.lastAccess <= SESSION_LIFETIME_NANOS;
	}

	public static void sendNames(ServerPlayer player) {
		PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(new UUID(0, 0), 0, "names_reset", "{}"));
		for (String value : BeeDesignerSources.managed.values()) {
			JsonObject document = JsonParser.parseString(value).getAsJsonObject();
			JsonObject names = new JsonObject();
			names.addProperty("id", document.get("id").getAsString());
			names.add("names", document.get("names"));
			names.addProperty("deleted", BeeDesignerLifecycle.deleted(document));
			PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(new UUID(0, 0), 0, "names", names.toString()));
		}
	}

	private static void result(ServerPlayer player, Session session, long sequence, boolean success, String message) {
		JsonObject result = new JsonObject();
		result.addProperty("success", success);
		result.addProperty("message", message);
		send(player, session, sequence, "result", result);
	}

	private static void send(ServerPlayer player, Session session, long sequence, String action, JsonObject data) {
		String json = data.toString();
		BeeDesignerProtocol.checkSize(json);
		if (!player.hasDisconnected()) {
			PacketDistributor.sendToPlayer(player, new BeeDesignerProtocol.Response(session.token, sequence, action, json));
		}
	}

	static String safeMessage(Throwable exception) {
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
