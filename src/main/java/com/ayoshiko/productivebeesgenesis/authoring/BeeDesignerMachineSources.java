package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

/** 原脚本只读，内容摘要归档独立于单蜂网络工程；导出不覆盖整合包脚本。 */
public final class BeeDesignerMachineSources {
	public record Import(Path original, String name, String sha256, List<BeeDesignerMachineScripts.Candidate> candidates) {
		public Import {
			candidates = List.copyOf(candidates);
		}
	}

	private BeeDesignerMachineSources() {
	}

	public static Import read(Path original, Path directory) throws IOException {
		String name = original.getFileName().toString();
		if (!name.endsWith(".js") && !name.endsWith(".json")) {
			throw new IOException("designer.error.machine_source_type");
		}
		String raw = readOriginal(original);
		String hash = BeeDesignerMachineScripts.hash(raw);
		Path archive = localDirectory(directory, "machine-sources").resolve(hash + (name.endsWith(".js") ? ".js" : ".json"));
		if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
			if (!readOriginal(archive).equals(raw)) {
				throw new IOException("designer.error.machine_archive_changed");
			}
		} else {
			Files.writeString(archive, raw, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		}
		List<BeeDesignerMachineScripts.Candidate> candidates;
		if (name.endsWith(".js")) {
			candidates = BeeDesignerMachineScripts.parse(raw);
		} else {
			BeeDesignerProtocol.checkDepth(raw);
			JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
			JsonObject recipe = json.has("recipe") ? json.getAsJsonObject("recipe") : json;
			if (BeeDesignerMachineRecipes.host(recipe) == null) {
				throw new IllegalArgumentException("designer.error.machine_type");
			}
			String fallback = BeeDesignerMachineRecipes.host(recipe) == BeeDesignerMachineRecipes.Host.MBD2
					? BeeDesignerDocument.text(recipe, "id", "bee_designer:imported/" + hash.substring(0, 24)) : "bee_designer:imported/" + hash.substring(0, 24);
			String id = BeeDesignerDocument.text(json, "recipe_id", fallback);
			new net.minecraft.resources.ResourceLocation(id);
			BeeDesignerMachineRecipes.recipeIdentity(recipe, id);
			candidates = List.of(new BeeDesignerMachineScripts.Candidate(id, recipe.deepCopy(), "", 1, 0, raw.length()));
		}
		return new Import(original.toAbsolutePath().normalize(), name, hash, candidates);
	}

	public static void verifyUnchanged(Import imported) throws IOException {
		if (!BeeDesignerMachineScripts.hash(readOriginal(imported.original())).equals(imported.sha256())) {
			throw new IOException("designer.error.machine_source_changed");
		}
	}

	public static String fragment(Path directory, Import imported, BeeDesignerMachineScripts.Candidate candidate) throws IOException {
		if (!imported.sha256().matches("[0-9a-f]{64}")) {
			throw new IOException("designer.error.machine_archive_changed");
		}
		Path archive = localDirectory(directory, "machine-sources").resolve(imported.sha256() + (imported.name().endsWith(".js") ? ".js" : ".json"));
		String source = readOriginal(archive);
		if (!BeeDesignerMachineScripts.hash(source).equals(imported.sha256()) || candidate.start() < 0
				|| candidate.end() > source.length() || candidate.end() < candidate.start()) {
			throw new IOException("designer.error.machine_archive_changed");
		}
		return source.substring(candidate.start(), candidate.end());
	}

	private static String readOriginal(Path path) throws IOException {
		if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)
				|| Files.size(path) > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IOException("designer.error.machine_source_budget");
		}
		byte[] bytes;
		try (var stream = Files.newInputStream(path)) {
			bytes = stream.readNBytes((int)BeeDesignerStore.MAX_PACK_BYTES + 1);
		}
		if (bytes.length > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IOException("designer.error.machine_source_budget");
		}
		return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
	}

	public static void merge(JsonObject document, Import imported, BeeDesignerMachineScripts.Candidate candidate, String version) {
		if (!candidate.parsed() || !BeeDesignerMachineRecipes.belongs(candidate.recipe(), document.get("id").getAsString())) {
			throw new IllegalArgumentException("designer.error.machine_source_bee");
		}
		JsonObject copy = document.deepCopy();
		JsonObject recipes = copy.getAsJsonObject("recipes");
		if (recipes.has(candidate.id())) {
			throw new IllegalArgumentException("designer.error.machine_duplicate");
		}
		if (recipes.size() >= 64) {
			throw new IllegalArgumentException("designer.error.machine_entries");
		}
		recipes.add(candidate.id(), candidate.recipe().deepCopy());
		JsonObject source = new JsonObject();
		source.addProperty("host", BeeDesignerMachineRecipes.host(candidate.recipe()).name());
		source.addProperty("host_version", version);
		source.addProperty("source_file", imported.name());
		source.addProperty("source_sha256", imported.sha256());
		source.addProperty("raw_source", "sha256:" + imported.sha256() + "#" + candidate.start() + ":" + candidate.end());
		source.addProperty("line", candidate.line());
		source.addProperty("parse_status", "parsed");
		source.addProperty("imported_recipe_sha256", BeeDesignerMachineScripts.hash(candidate.recipe().toString()));
		BeeDesignerDocument.object(copy, "machine_sources").add(candidate.id(), source);
		BeeDesignerProtocol.checkSize(copy.toString());
		document.add("recipes", copy.get("recipes"));
		document.add("machine_sources", copy.get("machine_sources"));
	}

	public static Path export(Path directory, JsonObject document) throws IOException {
		boolean hasMachines = document.getAsJsonObject("recipes").asMap().values().stream()
				.anyMatch(value -> BeeDesignerMachineRecipes.host(value.getAsJsonObject()) != null);
		if (!hasMachines) {
			throw new IllegalArgumentException("designer.error.machine_no_recipes");
		}
		Path destination = localDirectory(directory, "machine-exports");
		String basename = UUID.randomUUID().toString();
		Path script = destination.resolve(basename + ".js");
		Files.writeString(destination.resolve(basename + ".project.json"), document.toString(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE_NEW);
		Files.writeString(script, BeeDesignerMachineScripts.export(document), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
		return script;
	}

	private static Path localDirectory(Path directory, String child) throws IOException {
		Path root = directory.toAbsolutePath().normalize();
		Files.createDirectories(root);
		Path result = root.resolve(child);
		if (Files.isSymbolicLink(root) || Files.isSymbolicLink(result)) {
			throw new IOException("designer.error.machine_archive_changed");
		}
		Files.createDirectories(result);
		if (!result.toRealPath().startsWith(root.toRealPath())) {
			throw new IOException("designer.error.machine_archive_changed");
		}
		return result;
	}
}
