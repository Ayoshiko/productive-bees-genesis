package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import java.util.LinkedHashSet;
import java.util.Objects;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/** 单蜂最多保管 64 个完整快照；恢复后页面重新绑定对象，旧控件不继续写入。 */
final class DesignerDraftHistory {
	private final List<String> entries = new ArrayList<>();
	private final List<Long> times = new ArrayList<>();
	private int cursor = -1;

	void reset(JsonObject document) {
		entries.clear();
		times.clear();
		cursor = -1;
		capture(document);
	}

	void capture(JsonObject document) {
		String value = document.toString();
		if (cursor >= 0 && entries.get(cursor).equals(value)) {
			return;
		}
		while (entries.size() > cursor + 1) {
			entries.remove(entries.size() - 1);
			times.remove(times.size() - 1);
		}
		entries.add(value);
		times.add(System.currentTimeMillis());
		if (entries.size() > 64) {
			entries.remove(0);
			times.remove(0);
		}
		cursor = entries.size() - 1;
	}

	boolean canUndo() {
		return cursor > 0;
	}

	boolean canRedo() {
		return cursor + 1 < entries.size();
	}

	JsonObject move(int direction) {
		return select(cursor + direction);
	}

	JsonObject select(int index) {
		cursor = Math.max(0, Math.min(entries.size() - 1, index));
		return JsonParser.parseString(entries.get(cursor)).getAsJsonObject();
	}

	List<Long> times() {
		return List.copyOf(times);
	}

	List<Change> changes(int index) {
		if (index <= 0 || index >= entries.size()) {
			return List.of();
		}
		List<Change> changes = new ArrayList<>();
		diff("", JsonParser.parseString(entries.get(index - 1)), JsonParser.parseString(entries.get(index)), changes);
		return List.copyOf(changes);
	}

	private static void diff(String path, JsonElement before, JsonElement after, List<Change> changes) {
		if (Objects.equals(before, after)) {
			return;
		}
		if (before != null && after != null && before.isJsonObject() && after.isJsonObject()) {
			var keys = new LinkedHashSet<>(before.getAsJsonObject().keySet());
			keys.addAll(after.getAsJsonObject().keySet());
			for (String key : keys) {
				diff(path.isEmpty() ? key : path + "." + key, before.getAsJsonObject().get(key), after.getAsJsonObject().get(key), changes);
			}
		} else {
			changes.add(new Change(path, display(before), display(after)));
		}
	}

	private static String display(JsonElement value) {
		if (value == null) {
			return "∅";
		}
		String text = value.isJsonPrimitive() ? value.getAsString() : value.toString();
		return text.length() > 100 ? text.substring(0, 97) + "…" : text;
	}

	record Change(String path, String before, String after) {
	}

	int cursor() {
		return cursor;
	}
}
