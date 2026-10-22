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
