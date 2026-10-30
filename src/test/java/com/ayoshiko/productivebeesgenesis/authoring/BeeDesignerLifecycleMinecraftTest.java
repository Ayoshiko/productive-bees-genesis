package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class BeeDesignerLifecycleMinecraftTest {

	@Test
	void copyAvoidsDraftNameKeyAndRecipePrefixWithoutChangingSource() {
		JsonObject source = BeeDesignerDocument.create("test:metal_bee");
		BeeDesignerDocument.addRecipe(source, "productivebees:bee_breeding");
		String before = source.toString();
		String next = BeeDesignerDocument.nextCopyId(source, Set.of("other:metal_copy"),
				Set.of("test:bee_designer/metal_bee_copy_2/imported/1"));
		assertEquals("test:metal_bee_copy_3", next);
		assertEquals(before, source.toString());
		JsonObject copy = BeeDesignerDocument.copyAs(source, next);
		assertEquals("new", copy.get("baseline").getAsString());
		assertEquals("editor", copy.getAsJsonObject("_editor_origin").get("kind").getAsString());
		assertTrue(copy.getAsJsonObject("recipes").keySet().stream().allMatch(id -> id.startsWith("test:bee_designer/metal_bee_copy_3/")));
	}

	@Test
	void deletionRetainsDefinitionSettlementAndSharedNestWhileDisablingEggRecipe() {
		JsonObject original = BeeDesignerDocument.create("test:retained");
		String produce = BeeDesignerDocument.addRecipe(original, "productivebees:advanced_beehive");
		String centrifuge = BeeDesignerDocument.addRecipe(original, "productivebees:centrifuge");
		String egg = BeeDesignerDocument.addRecipe(original, "minecraft:crafting_shapeless");
		String nest = BeeDesignerDocument.addRecipe(original, "productivebees:bee_spawning");
		original.getAsJsonObject("recipes").getAsJsonObject(nest).getAsJsonArray("results").add("test:another");
		String before = original.toString();
		JsonObject retired = BeeDesignerLifecycle.retire(original, 1234, UUID.randomUUID());
		var files = BeeDesignerCompiler.files(Map.of("test:retained", retired.toString()));
		assertEquals(original.get("definition"), JsonParser.parseString(files.get("data/test/productivebees/retained.json")));
		for (String recipe : new String[]{produce, centrifuge}) {
			assertEquals(original.getAsJsonObject("recipes").get(recipe), JsonParser.parseString(recipeFile(files, recipe)));
		}
		assertTrue(recipeFile(files, egg).contains("neoforge:false"));
		assertEquals(JsonParser.parseString("[\"test:another\"]"), JsonParser.parseString(recipeFile(files, nest)).getAsJsonObject().get("results"));
		assertEquals(before, original.toString());
		assertEquals(original, BeeDesignerLifecycle.restore(retired));
		assertFalse(BeeDesignerLifecycle.deleted(BeeDesignerDocument.copyAs(retired, "test:retained_copy")));
	}

	@Test
	void confirmationRequiresThreeStepsExactIdentityAndUnchangedSource() {
		JsonObject original = BeeDesignerDocument.create("test:confirm");
		var confirmation = new BeeDesignerLifecycle.Confirmation(original, BeeDesignerSources.generation);
		JsonObject request = confirmation.review();
		request.addProperty("step", 3);
		assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(request));
		request.addProperty("step", 1);
		confirmation.confirm(request);
		assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(request));
		request.addProperty("step", 2);
		confirmation.confirm(request);
		request.addProperty("step", 3);
		request.addProperty("phrase", "test:wrong");
		assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(request));
		request.addProperty("phrase", "test:confirm");
		confirmation.confirm(request);
		JsonObject retired = BeeDesignerLifecycle.retire(original, 1234, UUID.randomUUID());
		assertTrue(confirmation.permits(retired));
		retired.getAsJsonObject("definition").addProperty("size", 2);
		assertFalse(confirmation.permits(retired));
		assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(request));
	}

    @Test
    void sharedNestDeletionAndRestorationFilterAllRetiredResultsWithoutColliding() {
        JsonObject first = BeeDesignerDocument.create("test:first_nest_bee");
        JsonObject second = BeeDesignerDocument.create("test:second_nest_bee");
        String id = BeeDesignerDocument.addRecipe(first, "productivebees:bee_spawning");
        JsonObject source = first.getAsJsonObject("recipes").getAsJsonObject(id);
        source.getAsJsonArray("results").add("test:second_nest_bee");
        second.getAsJsonObject("recipes").add(id, source.deepCopy());
        JsonObject deletedFirst = BeeDesignerLifecycle.retire(first, 1234, UUID.randomUUID());
        var files = BeeDesignerCompiler.files(Map.of("test:first_nest_bee", deletedFirst.toString(),
                "test:second_nest_bee", second.toString()));
        assertEquals(JsonParser.parseString("[\"test:second_nest_bee\"]"),
                JsonParser.parseString(recipeFile(files, id)).getAsJsonObject().get("results"));
        JsonObject deletedSecond = BeeDesignerLifecycle.retire(second, 1235, UUID.randomUUID());
        var removed = BeeDesignerCompiler.files(Map.of("test:first_nest_bee", deletedFirst.toString(),
                "test:second_nest_bee", deletedSecond.toString()));
        assertTrue(recipeFile(removed, id).contains("neoforge:false"));
        var restored = BeeDesignerCompiler.files(Map.of("test:first_nest_bee", first.toString(),
                "test:second_nest_bee", second.toString()));
        assertEquals(source, JsonParser.parseString(recipeFile(restored, id)));
        second.getAsJsonObject("recipes").getAsJsonObject(id).addProperty("chance", 0.5);
        assertThrows(IllegalArgumentException.class, () -> BeeDesignerCompiler.files(Map.of(
                "test:first_nest_bee", first.toString(), "test:second_nest_bee", second.toString())));
    }

	@Test
	void archivedDraftRestoresWithoutOverwritingNewDraftAndRetainsConflicts(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("test:restore");
		original.getAsJsonObject("definition").addProperty("unknown", "kept");
		BeeDesignerDrafts.save(directory, original);
		Path retained = BeeDesignerDrafts.archive(directory, "test:restore");
		String token = retained.getFileName().toString();
		assertEquals(original, BeeDesignerDrafts.archived(directory, token));
		assertEquals(1, BeeDesignerDrafts.archives(directory).size());
		JsonObject replacement = original.deepCopy();
		replacement.getAsJsonObject("definition").addProperty("size", 2);
		BeeDesignerDrafts.save(directory, replacement);
		assertThrows(IOException.class, () -> BeeDesignerDrafts.restoreArchive(directory, token));
		assertEquals(replacement, BeeDesignerDrafts.read(directory, "test:restore"));
		assertTrue(Files.exists(retained));
		BeeDesignerDrafts.archive(directory, "test:restore");
		assertEquals(original, BeeDesignerDrafts.restoreArchive(directory, token));
		assertEquals(original, BeeDesignerDrafts.read(directory, "test:restore"));
		assertFalse(Files.exists(retained));
		assertThrows(IOException.class, () -> BeeDesignerDrafts.archived(directory, "../outside.json"));
	}

	private static String recipeFile(Map<String, String> files, String id) {
		return files.get("data/" + id.replace(":", "/recipe/") + ".json");
	}

	@Test
	void stagingDeletionRetainsPreviousUnappliedDraft(@TempDir Path directory) throws IOException {
		JsonObject original = BeeDesignerDocument.create("test:pending_delete");
		original.getAsJsonObject("definition").addProperty("speed", 2);
		BeeDesignerDrafts.save(directory, original);
		JsonObject world = BeeDesignerDocument.create("test:pending_delete");
		JsonObject retired = BeeDesignerLifecycle.retire(world, 1234, UUID.randomUUID());
		BeeDesignerDrafts.save(directory, retired);
		assertEquals(retired, BeeDesignerDrafts.read(directory, "test:pending_delete"));
		var archives = BeeDesignerDrafts.archives(directory);
		assertEquals(1, archives.size());
		assertEquals(original, BeeDesignerDrafts.archived(directory, archives.getFirst().token()));
	}

	@Test
	void clearingRecycleRequiresItsOwnConfirmationAndPreservesWorldSettlement() {
		JsonObject source = BeeDesignerDocument.create("test:purged");
		String processing = BeeDesignerDocument.addRecipe(source, "productivebees:centrifuge");
		JsonObject retired = BeeDesignerLifecycle.retire(source, 1234, UUID.randomUUID());
		String before = retired.toString();
		JsonObject purged = BeeDesignerLifecycle.purge(retired);
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.validateTransition(retired, purged, null));
		var confirmation = new BeeDesignerLifecycle.Confirmation(retired, BeeDesignerSources.generation, true);
		JsonObject request = confirmation.review();
		request.addProperty("purging", false);
		request.addProperty("step", 1);
		assertThrows(IllegalArgumentException.class, () -> confirmation.confirm(request));
		request.addProperty("purging", true);
		for (int step = 1; step <= 3; step++) {
			request.addProperty("step", step);
			request.addProperty("phrase", "test:purged");
			confirmation.confirm(request);
		}
		assertTrue(confirmation.permits(purged));
		assertEquals(purged, confirmation.stage(9999, UUID.randomUUID()));
		assertDoesNotThrow(() -> BeeDesignerLifecycle.validateTransition(retired, purged, confirmation));
		JsonObject changed = purged.deepCopy();
		changed.getAsJsonObject("definition").addProperty("size", 2);
		assertFalse(confirmation.permits(changed));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.validateTransition(retired, changed, confirmation));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.validateTransition(purged, retired, confirmation));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.restore(purged));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerLifecycle.purge(purged));
		assertThrows(IllegalArgumentException.class, () -> new BeeDesignerLifecycle.Confirmation(source, BeeDesignerSources.generation, true));
		assertEquals(before, retired.toString());
		var files = BeeDesignerCompiler.files(Map.of("test:purged", purged.toString()));
		assertEquals(source.get("definition"), JsonParser.parseString(files.get("data/test/productivebees/purged.json")));
		assertEquals(source.getAsJsonObject("recipes").get(processing), JsonParser.parseString(recipeFile(files, processing)));
		assertFalse(BeeDesignerLifecycle.deleted(BeeDesignerDocument.copyAs(purged, "test:purged_copy")));
		assertDoesNotThrow(() -> BeeDesignerLifecycle.validateTransition(purged, BeeDesignerDocument.copyAs(purged, "test:purged_copy"), null));
	}

	@Test
	void recycledFileDeletionRejectsChangedFilesAndRemovesOnlyConfirmedRecord(@TempDir Path directory) throws IOException {
		JsonObject first = BeeDesignerDocument.create("test:delete_archive");
		BeeDesignerDrafts.save(directory, first);
		Path archived = BeeDesignerDrafts.archive(directory, "test:delete_archive");
		JsonObject second = BeeDesignerDocument.create("test:kept_archive");
		BeeDesignerDrafts.save(directory, second);
		Path kept = BeeDesignerDrafts.archive(directory, "test:kept_archive");
		long modified = Files.getLastModifiedTime(archived).toMillis();
		Files.setLastModifiedTime(archived, java.nio.file.attribute.FileTime.fromMillis(modified + 5000));
		String token = archived.getFileName().toString();
		assertThrows(IOException.class, () -> BeeDesignerDrafts.deleteArchive(directory, token, modified));
		assertTrue(Files.exists(archived));
		assertThrows(IOException.class, () -> BeeDesignerDrafts.deleteArchive(directory, "../outside.json", modified));
		BeeDesignerDrafts.deleteArchive(directory, token, Files.getLastModifiedTime(archived).toMillis());
		assertFalse(Files.exists(archived));
		assertTrue(Files.exists(kept));
		assertEquals(second, BeeDesignerDrafts.archived(directory, kept.getFileName().toString()));
		Files.writeString(archived, "{broken", java.nio.charset.StandardCharsets.UTF_8);
		BeeDesignerDrafts.deleteArchive(directory, token, Files.getLastModifiedTime(archived).toMillis());
		assertFalse(Files.exists(archived));
	}

	@Test
	void pendingDeletionDiscardDoesNotDeletePreviousArchivedEdits(@TempDir Path directory) throws IOException {
		JsonObject source = BeeDesignerDocument.create("test:discard_delete");
		BeeDesignerDrafts.save(directory, source);
		BeeDesignerDrafts.save(directory, BeeDesignerLifecycle.retire(source, 1234, UUID.randomUUID()));
		var entry = BeeDesignerDrafts.list(directory).getFirst();
		assertThrows(IOException.class, () -> BeeDesignerDrafts.discard(directory, entry.id(), entry.modified() - 5000));
		assertTrue(BeeDesignerLifecycle.deleted(BeeDesignerDrafts.read(directory, entry.id())));
		BeeDesignerDrafts.discard(directory, entry.id(), entry.modified());
		assertTrue(BeeDesignerDrafts.list(directory).isEmpty());
		var archived = BeeDesignerDrafts.archives(directory).getFirst();
		assertEquals(source, BeeDesignerDrafts.archived(directory, archived.token()));
	}
}
