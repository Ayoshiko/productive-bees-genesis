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
	void lateLoadIdentityRejectsAnotherProjectAnotherGenerationAndNewInput() {
		JsonObject original = BeeDesignerDocument.create("test:original");
		var identity = new BeeDesignerScreen.EditIdentity(7, original, original.toString(), null, "");
		assertTrue(identity.matches(7, original));
		assertFalse(identity.matches(8, original));
		assertFalse(identity.matches(7, original.deepCopy()));
		original.getAsJsonObject("names").addProperty("en_us", "Unsaved");
		assertFalse(identity.matches(7, original));
	}

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

	@Test
	void normalizationDoesNotOverwritePostSubmitFieldsOrReplaceNestedArrays() {
		JsonObject current = BeeDesignerDocument.create("test:edited");
		JsonObject node = new JsonObject();
		node.addProperty("amount", 1);
		JsonArray outputs = new JsonArray();
		outputs.add(node);
		current.add("outputs", outputs);
		JsonObject submitted = current.deepCopy();
		JsonObject normalized = current.deepCopy();
		normalized.getAsJsonArray("outputs").get(0).getAsJsonObject().addProperty("unit", "FE");
		normalized.getAsJsonObject("names").addProperty("en_us", "Server");
		current.getAsJsonObject("names").addProperty("en_us", "Later local");
		BeeDesignerScreen.mergeNormalized(current, submitted, normalized);
		assertSame(outputs, current.getAsJsonArray("outputs"));
		assertSame(node, outputs.get(0));
		assertEquals("FE", node.get("unit").getAsString());
		assertEquals("Later local", current.getAsJsonObject("names").get("en_us").getAsString());
	}

	@Test
	void largeLocalCatalogUsesBoundedCopyNegotiationInsteadOfUploadingIds() {
		java.util.List<String> occupied = new java.util.ArrayList<>();
		occupied.add("test:source_copy");
		occupied.add("test:source_copy_2");
		for (int index = 2; index < 4096; index++) {
			occupied.add("test:" + "long_name_".repeat(8) + index);
		}
		JsonObject request = NewBeeScreen.copyIdentityQuery("test:source", 0, occupied);
		assertEquals(2, request.get("_copy_attempt").getAsInt());
		assertEquals(java.util.Set.of("id", "_copy_attempt"), request.keySet());
		assertDoesNotThrow(() -> com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol.checkSize(request.toString()));
		assertTrue(request.toString().length() < 100);
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
	void migrationFailureRetainsConcreteCauseAndLiteralSeparators() {
		var migration = DesignerFormScreen.statusMessage("designer.error.migration_invalid|productivebees:aka_gem|designer.error.operation_detail|flowerItem: unknown item beebeeblock:aka_cube");
		var translated = (net.minecraft.network.chat.contents.TranslatableContents) migration.getContents();
		assertEquals("productivebees:aka_gem", translated.getArgs()[0]);
		assertEquals("flowerItem: unknown item beebeeblock:aka_cube", ((net.minecraft.network.chat.Component) translated.getArgs()[1]).getString());
		var detail = DesignerFormScreen.statusMessage("designer.error.operation_detail|host | material");
		assertEquals("host | material", ((net.minecraft.network.chat.contents.TranslatableContents) detail.getContents()).getArgs()[0]);
	}

	@Test
	void pendingJsonCapturesIndependentValuesWithoutChangingCurrentDocument() {
		JsonObject document = BeeDesignerDocument.create("test:pending");
		BeeDesignerScreen editor = new BeeDesignerScreen(true);
		JsonObject definition = document.getAsJsonObject("definition");
		DesignerJsonScreen json = new DesignerJsonScreen(editor, definition, "pending");
		String source = document.toString();
		json.restoreText("{\"primaryColor\":\"#102030\",\"size\":4}");
		JsonObject draft = DesignerDraftSnapshot.capture(editor, json, document);
		assertEquals("{\"primaryColor\":\"#102030\",\"size\":4}", draft.getAsJsonArray("_editor_recovery")
				.get(0).getAsJsonObject().get("text").getAsString());
		assertEquals(definition, draft.getAsJsonObject("definition"));
		assertEquals(source, document.toString());
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
