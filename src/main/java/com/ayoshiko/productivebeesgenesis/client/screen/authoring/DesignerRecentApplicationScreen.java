package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 最近成功操作只保留身份；查看时读取正式回执，不覆写作者草稿。 */
final class DesignerRecentApplicationScreen extends DesignerFormScreen {
	private static final int MAX_DOCUMENT_BYTES = 8 * 1024 * 1024;
	private final BeeDesignerScreen parent;
	private final DesignerBatchImportScreen.RecentPublication recent;
	private final Map<String, JsonObject> documents = new LinkedHashMap<>();
	private final StringBuilder downloadingText = new StringBuilder();
	private ArrayDeque<String> downloading;
	private String downloadingId;
	private String downloadingFingerprint = "";
	private int downloadingTotal;
	private int documentBytes;
	private String requestDigest;
	private long request = -1;
	private long requestedAt;
	private Object connection;
	private boolean reading;
	private boolean complete;
	private boolean queryOnOpen = true;

	DesignerRecentApplicationScreen(BeeDesignerScreen parent, DesignerBatchImportScreen.RecentPublication recent) {
		super(tr("recent_application"));
		this.parent = parent;
		this.recent = recent;
		requestDigest = recent.requestDigest();
		editorParent = parent;
	}

	@Override
	protected void init() {
		begin();
		note("", tr("recent_application_help"));
		if (complete) {
			note("", Component.translatable("designer.recent_application_ready", documents.size()));
			for (var entry : documents.entrySet()) {
				String id = entry.getKey();
				JsonObject document = entry.getValue();
				rows.add(new Row("", (x, y, w) -> addRenderableWidget(new DesignerBeeButton(x, y, w, id,
						DesignerCollectionScreen.localizedName(document), false,
						() -> minecraft.setScreen(new DesignerJsonScreen(this, document, id, true)))), 38));
			}
		} else if (reading) {
			note("", Component.translatable("designer.recent_application_loading",
					documents.size(), recent.ids().size()));
		}
		if (status.startsWith("designer.error.") || status.equals("designer.batch_import_unknown")) {
			note("error", statusMessage(status));
		}
		finishRows();
		int width = (contentWidth - 6) / 2;
		button(left, footerY(), width, tr("refresh"), this::query).active = !reading;
		button(left + width + 6, footerY(), width, tr("back"), this::onClose);
		if (queryOnOpen) {
			queryOnOpen = false;
			query();
		}
	}

	private JsonObject message() {
		JsonObject query = new JsonObject();
		query.addProperty("token", recent.token().toString());
		if (!requestDigest.isEmpty()) {
			query.addProperty("request_digest", requestDigest);
		}
		return query;
	}

	private void query() {
		if (!DesignerBatchImportScreen.currentOwner(recent)) {
			fail("designer.error.designer_session");
			return;
		}
		BeeDesignerClient.cancelRequest(request);
		documents.clear();
		documentBytes = 0;
		downloadingId = null;
		downloading = null;
		downloadingText.setLength(0);
		complete = false;
		reading = true;
		connection = minecraft.getConnection();
		status = "designer.status.loading";
		send(recent.single() ? "apply_status" : "import_status", message());
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	private void send(String action, JsonObject query) {
		request = BeeDesignerClient.request(action, query);
		requestedAt = System.currentTimeMillis();
		if (request < 0) {
			fail("designer.error.designer_session");
		}
	}

	boolean receive(long sequence, String action, JsonObject data) {
		if (request < 0 || request != sequence) {
			return false;
		}
		if (action.equals("result")) {
			if (!data.get("success").getAsBoolean()) {
				fail(data.get("message").getAsString());
			}
			return true;
		}
		if (!Set.of("publication_state", "import_state", "import_applied", "import_rejected", "import_document").contains(action)
				|| !recent.token().toString().equals(BeeDesignerDocument.text(data, "token", ""))) {
			return false;
		}
		request = -1;
		try {
			if (!action.equals("import_document") && !BeeDesignerDocument.text(data, "phase", "").equals("committed")) {
				fail(BeeDesignerDocument.text(data, "message", "designer.batch_import_unknown"));
				return true;
			}
			String digest = BeeDesignerDocument.text(data, "request_digest", "");
			if (digest.isEmpty() || !requestDigest.isEmpty() && !digest.equals(requestDigest)) {
				throw new IllegalArgumentException("designer.error.collection_changed");
			}
			requestDigest = digest;
			if (action.equals("import_document")) {
				receiveDocument(data);
			} else {
				if (data.get("count").getAsInt() != recent.ids().size()) {
					throw new IllegalArgumentException("designer.error.collection_changed");
				}
				downloading = new ArrayDeque<>(recent.ids());
				nextDocument();
			}
		} catch (RuntimeException failure) {
			ProductiveBeesGenesis.LOGGER.warn("Rejected recent application {} document {}", recent.token(), downloadingId, failure);
			fail("designer.error.collection_changed");
		}
		return true;
	}

	private void nextDocument() {
		if (downloading.isEmpty()) {
			reading = false;
			complete = true;
			status = "designer.batch_import_applied";
			rebuild();
			return;
		}
		downloadingId = downloading.removeFirst();
		downloadingText.setLength(0);
		downloadingFingerprint = "";
		downloadingTotal = 0;
		requestDocument();
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	private void requestDocument() {
		JsonObject query = message();
		query.addProperty("id", downloadingId);
		query.addProperty("offset", downloadingText.length());
		send("import_document", query);
	}

	private void receiveDocument(JsonObject data) {
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
				|| !fingerprint.equals(BeeDesignerDocument.text(document, "baseline", ""))) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		documentBytes += downloadingText.toString().getBytes(StandardCharsets.UTF_8).length;
		if (documentBytes > MAX_DOCUMENT_BYTES) {
			throw new IllegalArgumentException("designer.error.export_budget");
		}
		documents.put(id, document);
		nextDocument();
	}

	private void fail(String message) {
		BeeDesignerClient.cancelRequest(request);
		request = -1;
		reading = false;
		complete = false;
		status = message;
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	boolean ready() {
		return complete;
	}

	@Override
	public void tick() {
		if (reading && minecraft.getConnection() != connection) {
			fail("designer.error.designer_session");
		}
		if (request >= 0 && System.currentTimeMillis() - requestedAt > 30000) {
			fail("designer.error.bee_catalog_timeout");
		}
	}

	@Override
	public void onClose() {
		BeeDesignerClient.cancelRequest(request);
		request = -1;
		reading = false;
		minecraft.setScreen(parent);
	}
}
