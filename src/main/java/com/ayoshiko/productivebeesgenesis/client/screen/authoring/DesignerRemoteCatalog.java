package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 远端分页汇成有界名称快照，筛选不漏掉未翻页内容；同批不得混入资源重载。 */
final class DesignerRemoteCatalog {
	private final String kind;
	private final List<JsonObject> entries = new ArrayList<>();
	private long sequence = -1;
	private long requestedAt;
	private long generation = -1;
	private int nextPage;
	private int expectedTotal = -1;
	private int expectedPages;
	private boolean started;
	private String problem = "";
	private JsonObject metadata;

	DesignerRemoteCatalog(String kind) {
		this.kind = kind;
	}

	void start() {
		cancel();
		entries.clear();
		generation = -1;
		nextPage = 0;
		expectedTotal = -1;
		metadata = null;
		problem = "";
		started = true;
		request();
	}

	private void request() {
		JsonObject query = new JsonObject();
		query.addProperty("kind", kind);
		query.addProperty("search", "");
		query.addProperty("page", nextPage);
		try {
			sequence = BeeDesignerClient.request("materials", query);
			requestedAt = System.currentTimeMillis();
			if (sequence < 0) {
				problem = "designer.error.designer_session";
			}
		} catch (RuntimeException failure) {
			sequence = -1;
			problem = failure.getMessage() == null ? "designer.error.queue" : failure.getMessage();
		}
	}

	boolean receive(long received, JsonObject data) {
		if (sequence < 0 || received != sequence || !kind.equals(data.get("kind").getAsString())) {
			return false;
		}
		long version = data.get("generation").getAsLong();
		int total = data.get("total").getAsInt();
		int pages = data.get("pages").getAsInt();
		var pageEntries = data.getAsJsonArray("entries");
		if (total < 0 || total > 4096 || pages != Math.max(1, (total + 23) / 24)
				|| data.get("page").getAsInt() != nextPage || pageEntries.size() != Math.min(24, total - nextPage * 24)
				|| generation >= 0 && version != generation
				|| expectedTotal >= 0 && (total != expectedTotal || pages != expectedPages)) {
			fail(received, "designer.remote_catalog_changed");
			return true;
		}
		sequence = -1;
		generation = version;
		expectedTotal = total;
		expectedPages = pages;
		metadata = data;
		for (var entry : pageEntries) {
			entries.add(entry.getAsJsonObject());
		}
		nextPage++;
		if (nextPage < pages) {
			request();
		}
		return true;
	}

	boolean fail(long received, String message) {
		if (sequence < 0 || received != sequence) {
			return false;
		}
		cancel();
		started = true;
		entries.clear();
		metadata = null;
		problem = message;
		return true;
	}

	boolean tick() {
		return sequence >= 0 && System.currentTimeMillis() - requestedAt >= 8000
				&& fail(sequence, "designer.error.catalog_timeout");
	}

	void cancel() {
		if (sequence >= 0) {
			BeeDesignerClient.cancelRequest(sequence);
			started = false;
		}
		sequence = -1;
	}

	boolean started() {
		return started;
	}

	boolean loading() {
		return sequence >= 0;
	}

	String problem() {
		return problem;
	}

	List<JsonObject> entries() {
		return entries;
	}

	JsonObject metadata() {
		return metadata;
	}
}
