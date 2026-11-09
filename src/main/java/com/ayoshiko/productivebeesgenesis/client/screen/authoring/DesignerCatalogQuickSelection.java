package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 同一资源代际的全部分页就绪后才提交，失败或取消不留下部分全选。 */
final class DesignerCatalogQuickSelection {
	private final Map<String, JsonObject> origins = new LinkedHashMap<>();
	private final String kind;
	private final DesignerSearch.Query filter;
	private long request = -1;
	private long requestedAt;
	private long generation = -1;
	private int nextPage;
	private int total = -1;
	private int pages;
	private String problem = "";
	private boolean complete;

	DesignerCatalogQuickSelection(String kind, String search) {
		this.kind = kind;
		filter = DesignerSearch.compile(search);
		requestPage();
	}

	private void requestPage() {
		JsonObject query = new JsonObject();
		query.addProperty("search", "");
		query.addProperty("page", nextPage);
		try {
			request = BeeDesignerClient.request("catalog", query);
			requestedAt = System.currentTimeMillis();
			if (request < 0) {
				problem = "designer.error.designer_session";
			}
		} catch (RuntimeException failure) {
			problem = failure.getMessage() == null ? "designer.error.queue" : failure.getMessage();
		}
	}

	boolean receive(long sequence, JsonObject data) {
		if (request < 0 || sequence != request) {
			return false;
		}
		request = -1;
		int receivedTotal = data.get("total").getAsInt();
		int receivedPages = data.get("pages").getAsInt();
		long receivedGeneration = data.get("generation").getAsLong();
		JsonArray ids = data.getAsJsonArray("ids");
		if (receivedTotal < 0 || receivedTotal > BeeDesignerCollection.MAX_BEES
				|| receivedPages != Math.max(1, (receivedTotal + 31) / 32)
				|| data.get("page").getAsInt() != nextPage
				|| ids.size() != Math.min(32, Math.max(0, receivedTotal - nextPage * 32))
				|| total >= 0 && (total != receivedTotal || pages != receivedPages || generation != receivedGeneration)) {
			problem = "designer.remote_catalog_changed";
			return true;
		}
		total = receivedTotal;
		pages = receivedPages;
		generation = receivedGeneration;
		for (var value : ids) {
			String id = value.getAsString();
			if (origins.containsKey(id)) {
				problem = "designer.remote_catalog_changed";
				return true;
			}
			JsonObject origin = data.getAsJsonObject("origins").getAsJsonObject(id).deepCopy();
			origins.put(id, origin);
		}
		nextPage++;
		if (nextPage < pages) {
			requestPage();
		} else {
			complete = origins.size() == total;
		}
		return true;
	}

	boolean failed(long sequence, String message) {
		if (request < 0 || request != sequence) {
			return false;
		}
		request = -1;
		problem = message;
		return true;
	}

	void tick() {
		if (request >= 0 && System.currentTimeMillis() - requestedAt >= 15000) {
			BeeDesignerClient.cancelRequest(request);
			failed(request, "designer.error.bee_catalog_timeout");
		}
	}

	List<String> selected() {
		Map<String, JsonObject> all = new LinkedHashMap<>(origins);
		for (String id : DesignerDraftIndex.pendingIds()) {
			all.putIfAbsent(id, new JsonObject());
		}
		return all.entrySet().stream().filter(entry -> matches(entry.getKey(), entry.getValue()))
				.map(Map.Entry::getKey).toList();
	}

	private boolean matches(String id, JsonObject origin) {
		boolean pending = DesignerDraftIndex.pending(id);
		Component draftName = DesignerDraftIndex.name(id);
		Component name = pending && draftName != null ? draftName : BeeDesignerClient.beeName(id);
		if (!filter.matches(id.split(":", 2)[0], filter.needsName() ? DesignerSearch.indexed(id + " " + name.getString()) : id, List.of())) {
			return false;
		}
		String source = origin.has("kind") ? origin.get("kind").getAsString() : "editor";
		boolean edited = origin.has("edited") && origin.get("edited").getAsBoolean();
		return switch (kind) {
			case "select_all" -> true;
			case "select_pack_bees" -> source.equals("pack");
			case "select_editor_bees" -> source.equals("editor") || edited || pending;
			case "select_editor_new" -> source.equals("editor") || pending && !origins.containsKey(id);
			case "select_editor_modified" -> edited && !source.equals("editor") || pending && origins.containsKey(id);
			case "select_pending_bees" -> pending;
			default -> throw new IllegalArgumentException("Unknown quick selection");
		};
	}

	boolean complete() {
		return complete;
	}

	String problem() {
		return problem;
	}

	void cancel() {
		BeeDesignerClient.cancelRequest(request);
		request = -1;
	}
}
