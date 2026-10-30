package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDrafts;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 有界草稿摘要归客户端所有；文件 IO 异步，旧目录响应按代际丢弃。 */
final class DesignerDraftIndex {
	private static final Map<String, BeeDesignerDrafts.Entry> ENTRIES = new LinkedHashMap<>();
	private static long generation;
	private static long lifecycle;
	private static boolean loaded;
	private static boolean loading;
	private static String problem = "";

	private DesignerDraftIndex() {
	}

	static void refresh() {
		long requested = ++generation;
		loading = true;
		DesignerProjectFiles.drafts(entries -> {
			if (requested != generation) {
				return;
			}
			ENTRIES.clear();
			for (var entry : entries) {
				ENTRIES.put(entry.id(), entry);
			}
			loaded = true;
			loading = false;
			problem = "";
			updated();
		}, message -> {
			if (requested == generation) {
				loading = false;
				problem = message;
				updated();
			}
		});
	}

	static void ensureLoaded() {
		if (!loaded && !loading) {
			refresh();
		}
	}

	static void saved(JsonObject document) {
		generation++;
		loading = false;
		String id = document.get("id").getAsString();
		if (ENTRIES.size() >= 256 && !ENTRIES.containsKey(id)) {
			return;
		}
		Map<String, String> names = new LinkedHashMap<>();
		document.getAsJsonObject("names").entrySet().forEach(entry -> names.put(entry.getKey(), entry.getValue().getAsString()));
		boolean pending = document.has("_editor_recovery") || !BeeDesignerDocument.fingerprint(document).equals(BeeDesignerDocument.text(document, "baseline", "new"));
		ENTRIES.put(id, new BeeDesignerDrafts.Entry(id, Map.copyOf(names), System.currentTimeMillis(), "", pending, BeeDesignerLifecycle.deleted(document), BeeDesignerLifecycle.purged(document)));
		if (loaded) {
			updated();
		} else {
			refresh();
		}
	}

	static long lifecycle() {
		return lifecycle;
	}

	static long version() {
		return generation;
	}

	static List<String> pendingIds() {
		return ENTRIES.values().stream().filter(BeeDesignerDrafts.Entry::pending).filter(entry -> !entry.deleted())
				.sorted(Comparator.comparingLong(BeeDesignerDrafts.Entry::modified).reversed().thenComparing(BeeDesignerDrafts.Entry::id))
				.map(BeeDesignerDrafts.Entry::id).toList();
	}

	static List<String> allIds() {
		return List.copyOf(ENTRIES.keySet());
	}

	static List<String> deletingIds() {
		return ENTRIES.values().stream().filter(BeeDesignerDrafts.Entry::deleted).filter(BeeDesignerDrafts.Entry::pending)
				.map(BeeDesignerDrafts.Entry::id).toList();
	}

	static boolean pending(String id) {
		var entry = ENTRIES.get(id);
		return entry != null && entry.pending();
	}

	static boolean restorable(String id) {
		var entry = ENTRIES.get(id);
		return entry != null && !entry.purged();
	}

	static Component name(String id) {
		var entry = ENTRIES.get(id);
		if (entry == null) {
			return null;
		}
		String locale = Minecraft.getInstance().options.languageCode;
		String value = entry.names().getOrDefault(locale, entry.names().getOrDefault("en_us", ""));
		return value.isBlank() ? null : Component.literal(value);
	}

	static String problem() {
		return problem;
	}

	static void clear() {
		generation++;
		lifecycle++;
		ENTRIES.clear();
		loaded = false;
		loading = false;
		problem = "";
	}

	private static void updated() {
		if (Minecraft.getInstance().screen instanceof BeeDesignerScreen screen) {
			screen.draftsUpdated();
		}
	}
}
