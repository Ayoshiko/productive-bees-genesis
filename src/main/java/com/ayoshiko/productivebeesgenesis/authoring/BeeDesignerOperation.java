package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 发布身份与最终内容属于修订，不能随编辑会话关闭而丢失。 */
record BeeDesignerOperation(UUID token, UUID owner, String requestDigest, String kind,
		Map<String, String> baselines, Map<String, String> fingerprints) {
	BeeDesignerOperation {
		baselines = Map.copyOf(baselines);
		fingerprints = Map.copyOf(fingerprints);
		if (!requestDigest.matches("[a-f0-9]{64}") || baselines.isEmpty()
				|| baselines.size() > BeeDesignerCollection.MAX_BEES || !baselines.keySet().equals(fingerprints.keySet())) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
	}

	static BeeDesignerOperation create(UUID token, UUID owner, String requestDigest, String kind,
			Map<String, String> baselines, Map<String, String> projects) {
		Map<String, String> fingerprints = new LinkedHashMap<>();
		for (String id : baselines.keySet()) {
			String source = projects.get(id);
			if (source == null) {
				throw new IllegalArgumentException("designer.error.collection_changed");
			}
			fingerprints.put(id, BeeDesignerDocument.fingerprint(JsonParser.parseString(source).getAsJsonObject()));
		}
		return new BeeDesignerOperation(token, owner, requestDigest, kind, baselines, fingerprints);
	}

	JsonObject json() {
		JsonObject data = new JsonObject();
		data.addProperty("token", token.toString());
		data.addProperty("owner", owner.toString());
		data.addProperty("request_digest", requestDigest);
		data.addProperty("kind", kind);
		JsonObject before = new JsonObject();
		baselines.forEach(before::addProperty);
		data.add("baselines", before);
		JsonObject after = new JsonObject();
		fingerprints.forEach(after::addProperty);
		data.add("fingerprints", after);
		return data;
	}

	static JsonObject unknown(UUID token) {
		JsonObject data = new JsonObject();
		data.addProperty("token", token.toString());
		data.addProperty("phase", "unknown");
		data.addProperty("uncertain", true);
		data.addProperty("done", false);
		return data;
	}

	static JsonObject receipt(JsonObject record) {
		JsonObject data = new JsonObject();
		for (String field : new String[]{"token", "request_digest", "revision", "kind", "phase", "message"}) {
			if (record.has(field)) {
				data.add(field, record.get(field).deepCopy());
			}
		}
		String phase = BeeDesignerDocument.text(record, "phase", "unknown");
		data.addProperty("uncertain", phase.equals("uncertain") || phase.equals("unknown"));
		data.addProperty("done", phase.equals("committed"));
		data.addProperty("count", record.has("fingerprints") ? record.getAsJsonObject("fingerprints").size() : 0);
		return data;
	}

	static JsonObject documentChunk(JsonObject record, String id, String source, int offset) {
		if (!BeeDesignerDocument.text(record, "phase", "").equals("committed")
				|| !record.has("fingerprints") || !record.getAsJsonObject("fingerprints").has(id)) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		JsonObject document = JsonParser.parseString(source).getAsJsonObject();
		String fingerprint = BeeDesignerDocument.fingerprint(document);
		if (!id.equals(BeeDesignerDocument.text(document, "id", ""))
				|| !fingerprint.equals(record.getAsJsonObject("fingerprints").get(id).getAsString())) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		document.addProperty("baseline", fingerprint);
		String text = document.toString();
		BeeDesignerProtocol.parse(text);
		if (offset < 0 || offset >= text.length()
				|| offset > 0 && Character.isLowSurrogate(text.charAt(offset))) {
			throw new IllegalArgumentException("designer.error.collection_changed");
		}
		JsonObject result = receipt(record);
		result.addProperty("id", id);
		result.addProperty("fingerprint", fingerprint);
		result.addProperty("offset", offset);
		result.addProperty("total", text.length());
		int end = text.length();
		while (true) {
			if (end < text.length() && Character.isLowSurrogate(text.charAt(end))) {
				end--;
			}
			if (end <= offset) {
				throw new IllegalArgumentException("designer.error.export_budget");
			}
			result.addProperty("next_offset", end);
			result.addProperty("text", text.substring(offset, end));
			if (result.toString().getBytes(StandardCharsets.UTF_8).length <= BeeDesignerProtocol.MAX_BYTES) {
				return result;
			}
			end = offset + (end - offset) / 2;
		}
	}
}
