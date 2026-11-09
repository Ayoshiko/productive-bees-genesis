package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DesignerOutputListTest {
	@Test
	void duplicateOutputsMoveAndDeleteByIdentityWithAllFieldsPreserved() {
		JsonObject first = JsonParser.parseString("{\"item\":{\"item\":\"minecraft:diamond\",\"components\":{\"minecraft:custom_data\":{\"marker\":1}}},\"min\":3,\"max\":8,\"chance\":0.25}").getAsJsonObject();
		JsonObject duplicate = first.deepCopy();
		JsonObject third = JsonParser.parseString("{\"item\":{\"item\":\"minecraft:emerald\"},\"min\":1,\"max\":2,\"chance\":0.75}").getAsJsonObject();
		String original = first.toString();
		JsonArray outputs = new JsonArray();
		outputs.add(first);
		outputs.add(duplicate);
		outputs.add(third);
		assertTrue(DesignerOutputList.move(outputs, duplicate, 2));
		assertSame(first, outputs.get(0));
		assertSame(third, outputs.get(1));
		assertSame(duplicate, outputs.get(2));
		assertEquals(original, duplicate.toString());
		assertTrue(DesignerOutputList.remove(outputs, duplicate));
		assertSame(first, outputs.get(0));
		assertEquals(original, first.toString());
		assertEquals(2, outputs.size());
		assertFalse(DesignerOutputList.remove(outputs, duplicate));
	}

	@Test
	void dragRejectsOtherListsMissingTargetsAndInvalidPositions() {
		JsonObject first = new JsonObject();
		JsonObject second = new JsonObject();
		JsonArray outputs = new JsonArray();
		outputs.add(first);
		outputs.add(second);
		var transfer = new DesignerOutputList.Transfer(outputs, first);
		JsonArray unrelated = outputs.deepCopy();
		assertFalse(transfer.accepts(unrelated));
		assertFalse(transfer.drop(unrelated, unrelated.get(1).getAsJsonObject()));
		assertFalse(transfer.drop(outputs, new JsonObject()));
		assertFalse(DesignerOutputList.move(outputs, first, -1));
		assertFalse(DesignerOutputList.move(outputs, first, outputs.size()));
		assertSame(first, outputs.get(0));
		assertTrue(transfer.drop(outputs, second));
		assertSame(first, outputs.get(1));
		assertTrue(DesignerOutputList.remove(outputs, first));
		assertFalse(transfer.accepts(outputs));
	}
}
