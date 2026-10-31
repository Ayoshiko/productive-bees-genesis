package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMigration;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.network.chat.Component;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Set;
import java.util.Locale;

/** 整批冲突先预览；写入本机草稿后仍沿服务器原有检查与应用合同。 */
final class DesignerBatchImportScreen extends DesignerFormScreen {
	private final BeeDesignerScreen editor;
	private final DesignerFormScreen parent;
	private final Map<String, String> sources;
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

	DesignerBatchImportScreen(BeeDesignerScreen editor, DesignerFormScreen parent, Map<String, String> sources) {
		super(tr("batch_import"));
		this.editor = editor;
		this.parent = parent;
		editorParent = parent;
		this.sources = Map.copyOf(sources);
	}

	private void prepare() {
		var world = BeeReloadListener.INSTANCE.getData().keySet().stream().map(Object::toString).toList();
		var recipes = minecraft.level.getRecipeManager().getRecipes().stream().map(holder -> holder.id().toString()).toList();
		plan = BeeDesignerMigration.plan(sources, world, DesignerDraftIndex.allIds(), recipes,
				BeeDesignerMigration.Policy.valueOf(policy.toUpperCase(Locale.ROOT)));
	}

	@Override
	protected void init() {
		begin();
		note("", tr("batch_import_help"));
		if (status.startsWith("designer.error.") || status.equals("designer.batch_import_unknown")) {
			note("error", statusMessage(status));
		}
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
		if (saved) {
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
		if (!saved || transferring || applied || plan == null) {
			return;
		}
		connection = minecraft.getConnection();
		transferring = true;
		committing = false;
		uncertain = false;
		token = UUID.randomUUID();
		staged.clear();
		pending = new ArrayDeque<>(plan.documents().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList());
		JsonObject query = message();
		query.addProperty("count", plan.documents().size());
		send("import_begin", query);
		rebuild();
	}

	private JsonObject message() {
		JsonObject query = new JsonObject();
		query.addProperty("token", token.toString());
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
				if (!wasCommitting && token != null) {
					BeeDesignerClient.request("import_cancel", message());
				}
			}
			return true;
		}
		if (!Set.of("import_rejected", "import_ready", "import_staged", "import_applied", "import_state").contains(action)
				|| !token.toString().equals(BeeDesignerDocument.text(data, "token", ""))) {
			return false;
		}
		request = -1;
		if (action.equals("import_rejected")) {
			boolean unknown = data.has("uncertain") && data.get("uncertain").getAsBoolean();
			fail(data.get("message").getAsString());
			uncertain = unknown;
			if (!unknown && token != null) {
				BeeDesignerClient.request("import_cancel", message());
			}
		} else if (action.equals("import_ready") || action.equals("import_staged")) {
			if (action.equals("import_staged")) {
				JsonObject document = data.getAsJsonObject("document");
				String id = document.get("id").getAsString();
				if (!plan.documents().containsKey(id) || staged.containsKey(id)) {
					fail("designer.error.collection_changed");
					BeeDesignerClient.request("import_cancel", message());
					return true;
				}
				staged.put(id, document.toString());
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
				send("import_commit", message());
			}
		} else if (action.equals("import_applied")) {
			if (!staged.keySet().equals(plan.documents().keySet()) || data.get("count").getAsInt() != plan.documents().size()) {
				fail("designer.error.collection_changed");
				uncertain = true;
				return true;
			}
			transferring = false;
			committing = false;
			uncertain = false;
			applied = true;
			writing = true;
			Map<String, String> completed = new LinkedHashMap<>();
			staged.forEach((id, raw) -> {
				JsonObject document = JsonParser.parseString(raw).getAsJsonObject();
				document.addProperty("baseline", BeeDesignerDocument.fingerprint(document));
				completed.put(id, document.toString());
			});
			DesignerProjectFiles.importBatch(new BeeDesignerMigration.Plan(completed, plan.identities(), plan.skipped()), () -> {
				writing = false;
				status = "designer.batch_import_applied";
				if (minecraft.screen == this) {
					rebuild();
				}
			}, message -> {
				writing = false;
				status = message;
				if (minecraft.screen == this) {
					rebuild();
				}
			});
		} else if (action.equals("import_state")) {
			transferring = false;
			committing = false;
			uncertain = true;
			status = "designer.batch_import_pending";
		}
		if (minecraft.screen == this) {
			rebuild();
		}
		return true;
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
			transferring = true;
			send("import_status", message());
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
				BeeDesignerClient.request("import_cancel", message());
			}
			minecraft.setScreen(parent);
		}
	}
}
