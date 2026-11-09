package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 署名属于工程声明，不能用客户端整合包名称覆盖服务端实际资源来源。 */
public final class BeeDesignerCredits {

	public static final String FIELD = "_editor_credits";
	public static final List<String> FIELDS = List.of("pack", "version", "author", "url", "permission", "notes", "manifest");
	private static final int MAX_SOURCES = 8;

	private BeeDesignerCredits() {
	}

	public static JsonObject empty() {
		JsonObject result = new JsonObject();
		FIELDS.forEach(key -> result.addProperty(key, ""));
		return result;
	}

	public static void validate(JsonObject source) {
		for (var entry : source.entrySet()) {
			int maximum = Set.of("notes", "permission").contains(entry.getKey()) ? 1024 : 256;
			if (!FIELDS.contains(entry.getKey()) || !entry.getValue().isJsonPrimitive()
					|| !entry.getValue().getAsJsonPrimitive().isString() || entry.getValue().getAsString().length() > maximum
					|| entry.getValue().getAsString().codePoints().anyMatch(value -> value < 32 && value != '\n' && value != '\t')) {
				throw new IllegalArgumentException("designer.error.credits_field|" + entry.getKey());
			}
		}
		String link = BeeDesignerDocument.text(source, "url", "");
		if (!link.isBlank()) {
			try {
				URI uri = URI.create(link);
				if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
					throw new IllegalArgumentException("designer.error.credits_url");
				}
			} catch (IllegalArgumentException invalid) {
				throw new IllegalArgumentException("designer.error.credits_url", invalid);
			}
		}
	}

	public static void validateDocument(JsonObject document) {
		if (!document.has(FIELD)) {
			return;
		}
		if (!document.get(FIELD).isJsonArray() || document.getAsJsonArray(FIELD).size() > MAX_SOURCES) {
			throw new IllegalArgumentException("designer.error.credits_limit");
		}
		for (var source : document.getAsJsonArray(FIELD)) {
			if (!source.isJsonObject()) {
				throw new IllegalArgumentException("designer.error.credits_limit");
			}
			validate(source.getAsJsonObject());
		}
	}

	public static JsonObject attach(JsonObject document, JsonObject supplied) {
		validateDocument(document);
		validate(supplied);
		JsonObject result = document.deepCopy();
		JsonArray sources = result.has(FIELD) ? result.getAsJsonArray(FIELD) : new JsonArray();
		JsonObject normalized = empty();
		supplied.entrySet().forEach(entry -> normalized.add(entry.getKey(), entry.getValue().deepCopy()));
		boolean filled = FIELDS.stream().anyMatch(key -> !normalized.get(key).getAsString().isBlank());
		boolean present = sources.asList().stream().anyMatch(source -> normalized.equals(source));
		if (filled && !present) {
			if (sources.size() >= MAX_SOURCES) {
				throw new IllegalArgumentException("designer.error.credits_limit");
			}
			sources.add(normalized);
			result.add(FIELD, sources);
		}
		BeeDesignerProtocol.checkSize(result.toString());
		return result;
	}

	public static JsonObject readManifest(Path path) throws IOException {
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1024 * 1024) {
			throw new IOException("designer.error.credits_manifest");
		}
		String raw = Files.readString(path, StandardCharsets.UTF_8);
		BeeDesignerProtocol.checkDepth(raw);
		JsonObject manifest = JsonParser.parseString(raw).getAsJsonObject();
		boolean curse = BeeDesignerDocument.text(manifest, "manifestType", "").equals("minecraftModpack");
		boolean modrinth = manifest.has("formatVersion") && manifest.has("game")
				&& BeeDesignerDocument.text(manifest, "game", "").equals("minecraft");
		if (!curse && !modrinth) {
			throw new IOException("designer.error.credits_manifest");
		}
		JsonObject result = empty();
		result.addProperty("pack", BeeDesignerDocument.text(manifest, "name", ""));
		result.addProperty("version", BeeDesignerDocument.text(manifest, curse ? "version" : "versionId", ""));
		result.addProperty("author", BeeDesignerDocument.text(manifest, "author", ""));
		result.addProperty("url", BeeDesignerDocument.text(manifest, "website", BeeDesignerDocument.text(manifest, "url", "")));
		result.addProperty("manifest", path.getFileName().toString());
		validate(result);
		return result;
	}

	public static Set<String> namespaces(JsonObject document) {
		TreeSet<String> result = new TreeSet<>();
		collect(document.get("definition"), result);
		collect(document.get("recipes"), result);
		return Set.copyOf(result);
	}

	private static void collect(com.google.gson.JsonElement element, Set<String> result) {
		if (element == null) {
			return;
		}
		if (element.isJsonObject()) {
			element.getAsJsonObject().entrySet().forEach(entry -> collect(entry.getValue(), result));
		} else if (element.isJsonArray()) {
			element.getAsJsonArray().forEach(value -> collect(value, result));
		} else if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
			String value = element.getAsString();
			if (value.matches("!?[a-z0-9_.-]+:[a-z0-9_./-]+")) {
				result.add(value.substring(value.startsWith("!") ? 1 : 0, value.indexOf(':')));
			}
		}
	}
}
