package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMigration;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Set;
import java.util.Locale;

/** 整批冲突先预览；写入本机草稿后仍沿服务器原有检查与应用合同。 */
final class DesignerBatchImportScreen extends DesignerFormScreen {
	private static final Map<UUID, PendingPublication> PUBLICATIONS = new LinkedHashMap<>();
	private final BeeDesignerScreen editor;
	private final DesignerFormScreen parent;
	private final Map<String, String> sources;
	private final boolean drafts;
	private String policy = "copy_conflicts";
	private BeeDesignerMigration.Plan plan;
	private boolean writing;
	private boolean saved;
	private final Map<String, String> staged = new LinkedHashMap<>();
	private ArrayDeque<Map.Entry<String, String>> pending;
	private UUID token;
	private long request = -1;
	private long requestedAt;
	private boolean committing;
	private boolean applied;
	private boolean uncertain;
	private boolean transferring;
	private Object connection;
	private String requestDigest = "";
	private boolean singlePublication;
	private final Map<String, String> completed = new LinkedHashMap<>();
	private ArrayDeque<String> downloading;
	private String downloadingId;
	private String downloadingFingerprint = "";
	private int downloadingTotal;
	private final StringBuilder downloadingText = new StringBuilder();

	DesignerBatchImportScreen(BeeDesignerScreen editor, DesignerFormScreen parent, Map<String, String> sources) {
		this(editor, parent, sources, false);
	}

	DesignerBatchImportScreen(BeeDesignerScreen editor, DesignerFormScreen parent, Map<String, String> sources, boolean drafts) {
		super(tr(drafts ? "drafts_apply" : "batch_import"));
		this.editor = editor;
		this.parent = parent;
		editorParent = parent;
		this.sources = Map.copyOf(sources);
		this.drafts = drafts;
		saved = drafts;
	}

	private record PendingPublication(UUID token, Map<String, String> sources, BeeDesignerMigration.Plan plan,
			boolean drafts, boolean single, String requestDigest) {}

	static void requirePublicationCapacity(Set<String> ids) {
		if (PUBLICATIONS.size() >= 16 || PUBLICATIONS.values().stream()
				.anyMatch(pending -> pending.plan().documents().keySet().stream().anyMatch(ids::contains))) {
			throw new IllegalStateException("designer.batch_import_pending");
		}
	}

	static void rememberPublication(UUID token, JsonObject document) {
		String id = document.get("id").getAsString();
		Map<String, String> documents = Map.of(id, document.toString());
		var plan = new BeeDesignerMigration.Plan(documents, Map.of(id, id), java.util.List.of());
		PUBLICATIONS.put(token, new PendingPublication(token, documents, plan, true, true, ""));
	}

	static void completePublication(UUID token) {
		PUBLICATIONS.remove(token);
	}

	static void recordPublicationState(JsonObject data) {
		String value = BeeDesignerDocument.text(data, "token", "");
		PendingPublication pending = PUBLICATIONS.values().stream().filter(entry -> entry.token().toString().equals(value)).findFirst().orElse(null);
		if (pending == null) {
			return;
		}
		if (BeeDesignerDocument.text(data, "phase", "").equals("rejected")) {
			PUBLICATIONS.remove(pending.token());
			return;
		}
		String digest = BeeDesignerDocument.text(data, "request_digest", pending.requestDigest());
		if (!pending.requestDigest().isEmpty() && !pending.requestDigest().equals(digest)) {
			return;
		}
		PUBLICATIONS.put(pending.token(), new PendingPublication(pending.token(), pending.sources(), pending.plan(),
				pending.drafts(), pending.single(), digest));
	}

	static void addPending(BeeDesignerScreen editor) {
		for (PendingPublication pending : java.util.List.copyOf(PUBLICATIONS.values())) {
			editor.rows.add(new Row("", (x, y, w) -> editor.button(x, y, w,
					tr("batch_import_query").copy().append(" · " + pending.token().toString().substring(0, 8)), () -> {
				DesignerBatchImportScreen screen = new DesignerBatchImportScreen(editor, editor, pending.sources(), pending.drafts());
				screen.plan = pending.plan();
				screen.saved = true;
				screen.token = pending.token();
				screen.requestDigest = pending.requestDigest();
				screen.singlePublication = pending.single();
				screen.uncertain = true;
				screen.policy = "keep_identities";
				net.minecraft.client.Minecraft.getInstance().setScreen(screen);
				screen.queryResult();
			})));
		}
	}

	private void remember() {
		PUBLICATIONS.put(token, new PendingPublication(token, sources, plan, drafts, singlePublication, requestDigest));
	}

	private void prepare() {
		if (drafts) {
			Map<String, String> identities = new LinkedHashMap<>();
			sources.keySet().forEach(id -> identities.put(id, id));
			plan = new BeeDesignerMigration.Plan(sources, identities, java.util.List.of());
			return;
		}
		var world = BeeDesignerEggs.bees();
		var recipes = minecraft.level.getRecipeManager().getRecipes().stream().map(holder -> holder.id().toString()).toList();
		plan = BeeDesignerMigration.plan(sources, world, DesignerDraftIndex.allIds(), recipes,
				BeeDesignerMigration.Policy.valueOf(policy.toUpperCase(Locale.ROOT)));
	}

	@Override
	protected void init() {
		begin();
		note("", tr(drafts ? "drafts_apply_help" : "batch_import_help"));
		if (status.startsWith("designer.error.") || status.equals("designer.batch_import_unknown")) {
			note("error", statusMessage(status));
		}
		if (!drafts) {
			rows.add(new Row("import_conflict_policy", (x, y, w) -> selection(x, y, w, policy,
				new String[]{"copy_conflicts", "keep_identities", "skip_conflicts", "copy_all"}, value -> {
					if (!writing && !transferring && !uncertain) {
						policy = value;
						saved = false;
						applied = false;
						plan = null;
						rebuild();
					}
				})));
		}
		try {
			if (plan == null && !writing && !transferring) {
				prepare();
			}
			note("", Component.translatable("designer.batch_import_count", plan.documents().size(), plan.skipped().size()));
			for (var mapping : plan.identities().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
				JsonObject source = JsonParser.parseString(sources.get(mapping.getKey())).getAsJsonObject();
				Component detail = Component.literal(mapping.getKey() + " → " + mapping.getValue());
				rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, mapping.getValue(),
						DesignerCollectionScreen.localizedName(source), false, () -> minecraft.setScreen(
								new DesignerJsonScreen(this, JsonParser.parseString(plan.documents().get(mapping.getValue())).getAsJsonObject(), mapping.getValue(), true)))
						.detail(detail)), 38));
			}
			for (String skipped : plan.skipped()) {
				note("", Component.translatable("designer.import_skipped_bee", skipped));
			}
		} catch (RuntimeException failure) {
			plan = null;
			note("error", statusMessage(failure.getMessage()));
		}
		if (applied || saved && !drafts) {
			note("", tr(applied ? "batch_import_applied" : "batch_import_saved"));
		}
		if (transferring) {
			note("", Component.translatable("designer.batch_upload_progress", staged.size(), plan.documents().size()));
		}
		finishRows();
		int cell = (contentWidth - 6) / 2;
		String primary = "batch_import_save";
		if (saved && editor.canPublish && !applied) {
			primary = uncertain ? "batch_import_query" : "batch_import_apply";
		} else if (saved) {
			primary = "local_drafts";
		}
		primaryButton(left, footerY(), cell, tr(primary), () -> {
			if (!saved) {
				save();
			} else if (editor.canPublish && !applied) {
				if (uncertain) {
					queryResult();
				} else {
					apply();
				}
			} else {
				minecraft.setScreen(new DesignerDraftsScreen(editor));
			}
		}).active = !writing && !transferring && plan != null && !plan.documents().isEmpty();
		button(left + cell + 6, footerY(), cell, tr("back"), this::onClose).active = !writing && !committing;
	}

	private void save() {
		if (writing || plan == null) {
			return;
		}
		try {
			editor.changeDocument(() -> editor.leaveTo(() -> {
				minecraft.setScreen(this);
				writing = true;
				status = "designer.status.loading";
				var connection = minecraft.getConnection();
				DesignerProjectFiles.importBatch(plan, () -> {
					writing = false;
					saved = true;
					if (minecraft.screen == this && minecraft.getConnection() == connection) {
						status = "designer.batch_import_saved";
						rebuild();
					}
				}, message -> {
					writing = false;
					status = message;
					if (minecraft.screen == this) {
						rebuild();
					}
				});
				rebuild();
			}));
		} catch (RuntimeException failure) {
			status = failure.getMessage();
			rebuild();
		}
	}

	private void apply() {
		if (!saved || transferring || applied || uncertain || plan == null) {
			return;
		}
		try {
			requirePublicationCapacity(plan.documents().keySet());
		} catch (IllegalStateException full) {
			status = full.getMessage();
			return;
		}
		connection = minecraft.getConnection();
		transferring = true;
		committing = false;
		uncertain = false;
		token = UUID.randomUUID();
		requestDigest = "";
		completed.clear();
		staged.clear();
		pending = new ArrayDeque<>(plan.documents().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList());
		JsonObject query = message();
		query.addProperty("count", plan.documents().size());
		query.addProperty("drafts", drafts);
		send("import_begin", query);
		rebuild();
	}

	private JsonObject message() {
		JsonObject query = new JsonObject();
		query.addProperty("token", token.toString());
		if (!requestDigest.isEmpty()) {
			query.addProperty("request_digest", requestDigest);
		}
		return query;
	}

	private void send(String action, JsonObject query) {
		try {
			request = BeeDesignerClient.request(action, query);
			requestedAt = System.currentTimeMillis();
			if (request < 0) {
				fail("designer.error.designer_session");
			}
		} catch (RuntimeException failure) {
			fail(failure.getMessage());
		}
	}

	boolean receive(long sequence, String action, JsonObject data) {
		if (request < 0 || request != sequence) {
			return false;
		}
		if (action.equals("result")) {
			if (!data.get("success").getAsBoolean()) {
				boolean wasCommitting = committing;
				fail(data.get("message").getAsString());
				if (!wasCommitting && !uncertain && token != null) {
					BeeDesignerClient.request("import_cancel", message());
				}
			}
			return true;
		}
		if (!Set.of("import_rejected", "import_ready", "import_staged", "import_applied", "import_state", "import_document", "publication_state").contains(action)
				|| !token.toString().equals(BeeDesignerDocument.text(data, "token", ""))) {
			return false;
		}
		request = -1;
		if (!action.equals("import_staged") && !BeeDesignerDocument.text(data, "request_digest", "").isEmpty()
				&& !requestDigest.isEmpty() && !requestDigest.equals(data.get("request_digest").getAsString())) {
			fail("designer.error.collection_changed");
			uncertain = true;
			return true;
		}
		if (action.equals("import_rejected")) {
			boolean unknown = data.has("uncertain") && data.get("uncertain").getAsBoolean();
			fail(BeeDesignerDocument.text(data, "message", "designer.check_failed"));
			uncertain = unknown;
			if (!unknown && token != null) {
				completePublication(token);
				BeeDesignerClient.request("import_cancel", message());
			}
		} else if (action.equals("import_ready") || action.equals("import_staged")) {
			if (action.equals("import_staged")) {
				String id = data.get("id").getAsString();
				if (!plan.documents().containsKey(id) || staged.containsKey(id)) {
					fail("designer.error.collection_changed");
					BeeDesignerClient.request("import_cancel", message());
					return true;
				}
				staged.put(id, plan.documents().get(id));
				requestDigest = BeeDesignerDocument.text(data, "request_digest", "");
				if (requestDigest.isEmpty()) {
					fail("designer.error.collection_changed");
					return true;
				}
			}
			if (data.get("count").getAsInt() != staged.size() || data.get("expected").getAsInt() != plan.documents().size()) {
				fail("designer.error.collection_changed");
				BeeDesignerClient.request("import_cancel", message());
				return true;
			}
			if (!pending.isEmpty()) {
				var entry = pending.removeFirst();
				JsonObject query = message();
				query.add("document", JsonParser.parseString(entry.getValue()));
				send("import_entry", query);
			} else {
				committing = true;
				remember();
				send("import_commit", message());
			}
		} else if (action.equals("import_applied")
				|| (action.equals("import_state") || action.equals("publication_state"))
				&& BeeDesignerDocument.text(data, "phase", "").equals("committed")) {
			if (data.get("count").getAsInt() != plan.documents().size()) {
				fail("designer.error.collection_changed");
				uncertain = true;
				return true;
			}
			requestDigest = BeeDesignerDocument.text(data, "request_digest", requestDigest);
			if (requestDigest.isEmpty()) {
				fail("designer.error.collection_changed");
				uncertain = true;
				return true;
			}
			committing = false;
			uncertain = true;
			transferring = true;
			remember();
			completed.clear();
			downloading = new ArrayDeque<>(plan.documents().keySet().stream().sorted().toList());
			nextDocument();
		} else if (action.equals("import_document")) {
			receiveDocument(data);
		} else if (action.equals("import_state") || action.equals("publication_state")) {
			transferring = false;
			committing = false;
			uncertain = !BeeDesignerDocument.text(data, "phase", "").equals("rejected");
			if (!uncertain) {
				completePublication(token);
			}
			status = uncertain ? "designer.batch_import_pending" : BeeDesignerDocument.text(data, "message", "designer.check_failed");
		}
		if (minecraft.screen == this) {
			rebuild();
		}
		return true;
	}

	private void nextDocument() {
		if (downloading.isEmpty()) {
			transferring = false;
			writing = true;
			var identity = editor.editIdentity();
			DesignerProjectFiles.importBatch(new BeeDesignerMigration.Plan(Map.copyOf(completed), plan.identities(), plan.skipped()), () -> {
				writing = false;
				applied = true;
				uncertain = false;
				completePublication(token);
				if (editor.currentEdit(identity)) {
					status = "designer.batch_import_applied";
					rebuild();
				}
			}, message -> {
				writing = false;
				uncertain = true;
				if (editor.currentEdit(identity)) {
					status = message;
					rebuild();
				}
			});
			return;
		}
		downloadingId = downloading.removeFirst();
		downloadingText.setLength(0);
		downloadingTotal = 0;
		downloadingFingerprint = "";
		requestDocument();
	}

	private void requestDocument() {
		JsonObject query = message();
		query.addProperty("id", downloadingId);
		query.addProperty("offset", downloadingText.length());
		send("import_document", query);
	}

	private void receiveDocument(JsonObject data) {
		try {
			String id = BeeDesignerDocument.text(data, "id", "");
			String fingerprint = BeeDesignerDocument.text(data, "fingerprint", "");
			String text = data.get("text").getAsString();
			int offset = data.get("offset").getAsInt();
			int next = data.get("next_offset").getAsInt();
			int total = data.get("total").getAsInt();
			if (!id.equals(downloadingId) || offset != downloadingText.length() || next != offset + text.length()
					|| next <= offset || next > total || total > BeeDesignerProtocol.MAX_BYTES || total <= 0
					|| fingerprint.isEmpty() || offset != 0 && (total != downloadingTotal || !fingerprint.equals(downloadingFingerprint))) {
				throw new IllegalArgumentException("designer.error.collection_changed");
			}
			downloadingTotal = total;
			downloadingFingerprint = fingerprint;
			downloadingText.append(text);
			if (next < total) {
				requestDocument();
				return;
			}
			JsonObject document = BeeDesignerProtocol.parse(downloadingText.toString());
			if (!id.equals(BeeDesignerDocument.text(document, "id", ""))
					|| !fingerprint.equals(BeeDesignerDocument.fingerprint(document))
					|| !document.has("baseline") || BeeDesignerDocument.text(document, "baseline", "new").equals("new")) {
				throw new IllegalArgumentException("designer.error.collection_changed");
			}
			completed.put(id, document.toString());
			nextDocument();
		} catch (RuntimeException failure) {
			uncertain = true;
			fail(failure.getMessage());
		}
	}

	private void fail(String message) {
		BeeDesignerClient.cancelRequest(request);
		request = -1;
		transferring = false;
		uncertain = uncertain || committing;
		committing = false;
		status = message;
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	private void queryResult() {
		if (token != null && !transferring) {
			connection = minecraft.getConnection();
			transferring = true;
			send(singlePublication ? "apply_status" : "import_status", message());
		}
	}

	@Override
	public void tick() {
		if (transferring && minecraft.getConnection() != connection) {
			fail("designer.error.designer_session");
		}
		if (request >= 0 && System.currentTimeMillis() - requestedAt > 30000) {
			fail(committing ? "designer.batch_import_unknown" : "designer.error.bee_catalog_timeout");
		}
	}

	@Override
	public void onClose() {
		if (!writing && !committing) {
			if (transferring && token != null) {
				BeeDesignerClient.cancelRequest(request);
				if (!uncertain) {
					BeeDesignerClient.request("import_cancel", message());
				}
			}
			minecraft.setScreen(parent);
		}
	}
}
