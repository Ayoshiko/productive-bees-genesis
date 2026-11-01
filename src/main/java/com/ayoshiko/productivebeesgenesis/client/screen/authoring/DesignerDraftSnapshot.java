package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** 在封闭副本中合并暂存编辑；无法解析的文本单独保管，不回写当前声明。 */
final class DesignerDraftSnapshot {
	private final JsonObject document;
	private final Map<JsonElement, JsonElement> copies = new IdentityHashMap<>();
	private final JsonArray recovery;

	private DesignerDraftSnapshot(JsonObject source) {
		document = source.deepCopy();
		recovery = document.has("_editor_recovery") ? document.getAsJsonArray("_editor_recovery") : new JsonArray();
		document.add("_editor_recovery", recovery);
		bind(source, document);
	}

	static JsonObject capture(BeeDesignerScreen editor, DesignerFormScreen current, JsonObject source) {
		DesignerDraftSnapshot snapshot = new DesignerDraftSnapshot(source);
		List<DesignerFormScreen> pages = new ArrayList<>();
		DesignerFormScreen page = current;
		while (page != editor) {
			if (page instanceof DesignerSettingsScreen || page instanceof NewBeeScreen) {
				pages.clear();
				break;
			}
			pages.add(0,page);
			if (!(page.editorParent instanceof DesignerFormScreen parent)) {
				break;
			}
			page = parent;
		}
		int lastPending = -1;
		for (int index = 0; index < pages.size(); index++) {
			if (!pages.get(index).pendingDraftState().isEmpty()) {
				lastPending = index;
			}
		}
		for (int index = 0; index <= lastPending; index++) {
			pages.get(index).snapshotDraft(snapshot);
		}
		if (!snapshot.recovery.isEmpty()) {
			snapshot.document.add("_editor_recovery", snapshot.recovery);
		} else {
			snapshot.document.remove("_editor_recovery");
		}
		return snapshot.document;
	}

	static String pendingState(BeeDesignerScreen editor, DesignerFormScreen current) {
		StringBuilder state = new StringBuilder();
		DesignerFormScreen page = current;
		while (page != editor) {
			if (page instanceof DesignerSettingsScreen || page instanceof NewBeeScreen) {
				return "";
			}
			String pending = page.pendingDraftState();
			if (!pending.isEmpty()) {
				state.append(page.getClass().getName()).append('\n').append(pending).append('\n');
			}
			if (!(page.editorParent instanceof DesignerFormScreen parent)) {
				break;
			}
			page = parent;
		}
		return state.toString();
	}

	JsonObject document() {
		return document;
	}

	JsonObject target(JsonObject source) {
		JsonElement target = copies.get(source);
		return target != null && target.isJsonObject() ? target.getAsJsonObject() : null;
	}

	void replace(JsonObject source, JsonObject value) {
		JsonObject target = target(source);
		if (target == null) {
			return;
		}
		target.keySet().clear();
		value.entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue().deepCopy()));
		bind(source, target);
	}

	void bind(JsonElement source, JsonElement target) {
		if (source == null || target == null) {
			return;
		}
		copies.put(source, target);
		if (source.isJsonObject() && target.isJsonObject()) {
			for (var entry : source.getAsJsonObject().entrySet()) {
				bind(entry.getValue(), target.getAsJsonObject().get(entry.getKey()));
			}
		} else if (source.isJsonArray() && target.isJsonArray()) {
			for (int i = 0; i < Math.min(source.getAsJsonArray().size(), target.getAsJsonArray().size()); i++) {
				bind(source.getAsJsonArray().get(i), target.getAsJsonArray().get(i));
			}
		}
	}

	void recover(JsonObject value, String id) {
		completeRecovery(id);
		value.addProperty("id", id);
		recovery.add(value);
	}

	void completeRecovery(String id) {
		recovery.asList().removeIf(value -> value.isJsonObject() && value.getAsJsonObject().has("id")
				&& id.equals(value.getAsJsonObject().get("id").getAsString()));
	}

	void recoveryPath(JsonObject source, JsonObject entry) {
		JsonArray path = path(source);
		if (path != null && path.size() > 1 && path.get(0).getAsString().equals("_editor_recovery")) {
			JsonObject parent = recovery.get(path.get(1).getAsInt()).getAsJsonObject();
			if (parent.has("id")) {
				entry.add("parent", parent.get("id"));
				JsonArray relative = new JsonArray();
				for (int index = 2; index < path.size(); index++) {
					relative.add(path.get(index));
				}
				path = relative;
			}
		}
		if (path != null) {
			entry.add("path", path);
		}
	}

	void replaceValue(JsonElement source, JsonElement value) {
		JsonElement target = copies.get(source);
		if (target != null) {
			replaceValue(document, target, value);
		}
	}

	private static boolean replaceValue(JsonElement current, JsonElement target, JsonElement value) {
		if (current.isJsonObject()) {
			for (var entry : List.copyOf(current.getAsJsonObject().entrySet())) {
				if (entry.getValue() == target) {
					current.getAsJsonObject().add(entry.getKey(), value.deepCopy());
					return true;
				}
				if (replaceValue(entry.getValue(), target, value)) {
					return true;
				}
			}
		} else if (current.isJsonArray()) {
			JsonArray array = current.getAsJsonArray();
			for (int i = 0; i < array.size(); i++) {
				if (array.get(i) == target) {
					array.set(i, value.deepCopy());
					return true;
				}
				if (replaceValue(array.get(i), target, value)) {
					return true;
				}
			}
		}
		return false;
	}

	JsonArray path(JsonObject source) {
		JsonObject target = target(source);
		if (target == null) {
			return null;
		}
		return path(document, target, new JsonArray());
	}

	private static JsonArray path(JsonElement current, JsonElement target, JsonArray prefix) {
		if (current == target) {
			return prefix;
		}
		if (current.isJsonObject()) {
			for (var entry : current.getAsJsonObject().entrySet()) {
				JsonArray next = prefix.deepCopy();
				next.add(entry.getKey());
				JsonArray found = path(entry.getValue(), target, next);
				if (found != null) {
					return found;
				}
			}
		} else if (current.isJsonArray()) {
			for (int i = 0; i < current.getAsJsonArray().size(); i++) {
				JsonArray next = prefix.deepCopy();
				next.add(i);
				JsonArray found = path(current.getAsJsonArray().get(i), target, next);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	static JsonObject target(JsonObject document, JsonArray path) {
		JsonElement current = document;
		for (JsonElement segment : path) {
			if (current == null) {
				return null;
			}
			if (segment.getAsJsonPrimitive().isNumber()) {
				int index = segment.getAsInt();
				if (!current.isJsonArray() || index < 0 || index >= current.getAsJsonArray().size()) {
					return null;
				}
				current = current.getAsJsonArray().get(index);
			} else {
				if (!current.isJsonObject()) {
					return null;
				}
				current = current.getAsJsonObject().get(segment.getAsString());
			}
		}
		return current != null && current.isJsonObject() ? current.getAsJsonObject() : null;
	}
}
