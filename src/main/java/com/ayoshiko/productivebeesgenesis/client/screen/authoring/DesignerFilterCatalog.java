package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigResultPayload;
import com.ayoshiko.productivebeesgenesis.network.FilterConfigSyncPayload;
import com.ayoshiko.productivebeesgenesis.network.ForgeNetwork;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 页面持有分页候选快照；关闭后释放，重载代际变化不会拼接两批目录。 */
final class DesignerFilterCatalog {
	final Map<String, String> entries = new LinkedHashMap<>();
	boolean complete;
	boolean enabled = true;
	String problem = "";
	private UUID pending;
	private long deadline;
	private long nextAt;
	private long generation = -1;
	private int page;

	void reset() {
		entries.clear();
		complete = false;
		problem = "";
		pending = null;
		generation = -1;
		page = 0;
		nextAt = System.currentTimeMillis();
	}

	boolean tick() {
		long now = System.currentTimeMillis();
		if (pending != null && now >= deadline) {
			pending = null;
			problem = "designer.filter_load_timeout";
			return true;
		}
		if (pending == null && !complete && problem.isEmpty() && now >= nextAt) {
			pending = UUID.randomUUID();
			deadline = now + 8000;
			ForgeNetwork.sendToServer(new FilterConfigSyncPayload("DISABLED", List.of(), pending, "", false, page, generation));
		}
		return false;
	}

	boolean receive(FilterConfigResultPayload response) {
		if (!response.request().equals(pending)) {
			return false;
		}
		pending = null;
		if (!response.success()) {
			problem = response.message();
			return true;
		}
		var data = BeeDesignerProtocol.parse(response.snapshot());
		long version = data.get("generation").getAsLong();
		if (data.get("page").getAsInt() != page || page > 0 && version != generation) {
			problem = "designer.error.collection_changed";
			return true;
		}
		generation = version;
		data.getAsJsonObject("entries").entrySet().forEach(entry -> entries.put(entry.getKey(), entry.getValue().getAsString()));
		enabled = data.get("enabled").getAsBoolean();
		complete = ++page >= data.get("pages").getAsInt();
		nextAt = System.currentTimeMillis() + 200;
		return true;
	}

	String kind(String id) {
		return entries.getOrDefault(id, complete ? "missing" : "pending");
	}

	boolean eligible(String id) {
		return List.of("comb", "both").contains(kind(id));
	}
}
