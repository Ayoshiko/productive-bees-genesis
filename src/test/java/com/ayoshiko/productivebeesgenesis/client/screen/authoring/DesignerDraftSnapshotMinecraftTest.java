package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class DesignerDraftSnapshotMinecraftTest {
	@Test
	void acceptedNormalizationKeepsGraphUndoAndPrivateJsonInput() throws ReflectiveOperationException {
		JsonObject document = BeeDesignerDocument.create("test:receipt");
		JsonObject definition = document.getAsJsonObject("definition");
		JsonObject recipe = new JsonObject();
		recipe.addProperty("type", "mbd2:machine");
		document.getAsJsonObject("recipes").add("test:machine", recipe);
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		setField(editor, "document", document);
		setField(editor, "editGeneration", 3L);
		setField(editor, "submittedDocument", document);
		setField(editor, "submittedGeneration", 3L);
		setField(editor, "submittedSnapshot", document.deepCopy());
		DesignerDraftHistory history = (DesignerDraftHistory) getField(editor, "history");
		history.reset(document);
		DesignerJsonScreen json = new DesignerJsonScreen(editor, definition, "pending");
		json.restoreText("{\"size\":7}");
		JsonObject receipt = document.deepCopy();
		receipt.add("disabled_recipes", new JsonArray());
		receipt.getAsJsonObject("recipes").getAsJsonObject("test:machine").addProperty("id", "test:machine");
		receipt.addProperty("baseline", BeeDesignerDocument.fingerprint(receipt));

		assertTrue(editor.acceptApplied(receipt));
		assertSame(document, editor.editingDocument());
		assertSame(definition, document.getAsJsonObject("definition"));
		assertSame(recipe, document.getAsJsonObject("recipes").getAsJsonObject("test:machine"));
		assertEquals("test:machine", recipe.get("id").getAsString());
		assertEquals(receipt.get("baseline").getAsString(), BeeDesignerDocument.fingerprint(document));
		assertTrue(history.canUndo());
		JsonObject recovery = DesignerDraftSnapshot.capture(editor, json, document).getAsJsonArray("_editor_recovery").get(0).getAsJsonObject();
		assertEquals("{\"size\":7}", recovery.get("text").getAsString());
		var commit = DesignerJsonScreen.class.getDeclaredMethod("commitChanges");
		commit.setAccessible(true);
		assertEquals(true, commit.invoke(json));
		assertSame(definition, document.getAsJsonObject("definition"));
		assertEquals(7, definition.get("size").getAsInt());
		definition.addProperty("size", 9);
		assertEquals(9, editor.editingDocument().getAsJsonObject("definition").get("size").getAsInt());
		setField(editor, "editGeneration", 4L);
		assertFalse(editor.acceptApplied(receipt));
		assertEquals(9, definition.get("size").getAsInt());
	}

	private static void setField(BeeDesignerScreen editor, String name, Object value) throws ReflectiveOperationException {
		var field = BeeDesignerScreen.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(editor, value);
	}

	private static Object getField(BeeDesignerScreen editor, String name) throws ReflectiveOperationException {
		var field = BeeDesignerScreen.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(editor);
	}

	@Test
	void incompleteJsonKeepsRawTextAndARecoverableExactPath() {
		JsonObject document = BeeDesignerDocument.create("test:unfinished");
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		DesignerJsonScreen json = new DesignerJsonScreen(editor, document.getAsJsonObject("definition"), "pending");
		json.restoreText("{\"size\":");
		JsonObject draft = DesignerDraftSnapshot.capture(editor, json, document);
		JsonObject recovery = draft.getAsJsonArray("_editor_recovery").get(0).getAsJsonObject();
		assertEquals("{\"size\":", recovery.get("text").getAsString());
		assertEquals(document.getAsJsonObject("definition"), DesignerDraftSnapshot.target(draft, recovery.getAsJsonArray("path")));
		assertEquals(document.getAsJsonObject("definition"), draft.getAsJsonObject("definition"));
		assertEquals(BeeDesignerDocument.fingerprint(document), BeeDesignerDocument.fingerprint(draft));
	}

	@Test
	void reopeningAnUnresolvedDraftPreservesEveryRecoveryRecord() {
		JsonObject document = BeeDesignerDocument.create("test:unresolved");
		JsonArray recovery = new JsonArray();
		for (String id : new String[]{"first", "second"}) {
			JsonObject record = new JsonObject();
			record.addProperty("id", id);
			record.addProperty("kind", "json");
			record.addProperty("text", "{");
			recovery.add(record);
		}
		document.add("_editor_recovery", recovery);
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		JsonObject draft = DesignerDraftSnapshot.capture(editor, editor, document);
		assertEquals(recovery, draft.get("_editor_recovery"));
		draft.getAsJsonArray("_editor_recovery").remove(0);
		assertEquals(2, recovery.size());
	}

	@Test
	void themeJsonCannotBecomePendingInputInTheBeeDraft() {
		JsonObject document = BeeDesignerDocument.create("test:settings");
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		DesignerSettingsScreen settings = new DesignerSettingsScreen(editor);
		DesignerJsonScreen json = new DesignerJsonScreen(settings, new JsonObject(), "theme");
		json.restoreText("{\"colors\":");
		assertEquals("", DesignerDraftSnapshot.pendingState(editor, json));
		assertEquals(document, DesignerDraftSnapshot.capture(editor, json, document));
	}

	@Test
	void unfinishedProductionKeepsItsNestedJsonLocationWithoutMutatingTheDeclaration() throws ReflectiveOperationException {
		JsonObject document = BeeDesignerDocument.create("test:nested");
		String original = document.toString();
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		DesignerProductionScreen production = new DesignerProductionScreen(editor, document, "test:node/with/slashes");
		var itemsField = DesignerProductionScreen.class.getDeclaredField("items");
		itemsField.setAccessible(true);
		JsonArray items = (JsonArray) itemsField.get(production);
		JsonObject output = new JsonObject();
		output.addProperty("min", 1);
		items.add(output);
		DesignerJsonScreen json = new DesignerJsonScreen(production, output, "pending");
		json.restoreText("{\"min\":");
		JsonObject draft = DesignerDraftSnapshot.capture(editor, json, document);
		JsonArray recovery = draft.getAsJsonArray("_editor_recovery");
		assertEquals(2, recovery.size());
		JsonObject node = recovery.get(0).getAsJsonObject();
		JsonObject nested = recovery.get(1).getAsJsonObject();
		assertEquals("test:node/with/slashes", node.get("node").getAsString());
		assertEquals(node.get("id"), nested.get("parent"));
		assertEquals(output, DesignerDraftSnapshot.target(node, nested.getAsJsonArray("path")));
		assertEquals("{\"min\":", nested.get("text").getAsString());
		assertEquals(original, document.toString());
	}
}
