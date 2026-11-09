package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class DesignerNameBatchMinecraftTest {
	@Test
	void receiveCommitsOnlyCompleteValidBatchFromCurrentConnection() throws ReflectiveOperationException {
		var minecraft = mock(Minecraft.class);
		var listener = mock(ClientPacketListener.class);
		var connection = mock(Connection.class);
		var previousConnection = mock(Connection.class);
		when(minecraft.getConnection()).thenReturn(listener);
		when(listener.getConnection()).thenReturn(connection);
		var receive = BeeDesignerClient.class.getDeclaredMethod("receive", BeeDesignerProtocol.Response.class, Connection.class);
		receive.setAccessible(true);
		var names = BeeDesignerClient.class.getDeclaredField("NAMES");
		names.setAccessible(true);
		Object saved = names.get(null);
		try (var statics = mockStatic(Minecraft.class)) {
			statics.when(Minecraft::getInstance).thenReturn(minecraft);
			UUID initial = new UUID(1, 1);
			receive.invoke(null, response(initial, 1, "names_reset", header(1)), connection);
			receive.invoke(null, response(initial, 1, "names", entry("test:original", "Original")), connection);
			receive.invoke(null, response(initial, 1, "names_complete", header(1)), connection);
			Map<?, ?> original = (Map<?, ?>) names.get(null);
			assertEquals("Original", ((JsonObject) original.get("test:original")).get("en_us").getAsString());

			UUID replacement = new UUID(2, 2);
			receive.invoke(null, response(replacement, 2, "names_reset", header(2)), connection);
			receive.invoke(null, response(replacement, 2, "names", entry("test:valid", "Valid")), connection);
			JsonObject malformed = entry("test:bad", "Bad");
			malformed.getAsJsonObject("names").add("en_us", new JsonObject());
			assertDoesNotThrow(() -> receive.invoke(null, response(replacement, 2, "names", malformed), connection));
			receive.invoke(null, response(replacement, 2, "names_complete", header(2)), connection);
			assertSame(original, names.get(null));

			receive.invoke(null, response(new UUID(3, 3), 3, "names_reset", header(0)), previousConnection);
			assertSame(original, names.get(null));
			UUID empty = new UUID(4, 4);
			receive.invoke(null, response(empty, 4, "names_reset", header(0)), connection);
			receive.invoke(null, response(empty, 4, "names_complete", header(0)), connection);
			assertTrue(((Map<?, ?>) names.get(null)).isEmpty());
		} finally {
			names.set(null, saved);
			for (String field : new String[]{"nameBatch", "namesConnection"}) {
				var state = BeeDesignerClient.class.getDeclaredField(field);
				state.setAccessible(true);
				state.set(null, null);
			}
			var generation = BeeDesignerClient.class.getDeclaredField("namesGeneration");
			generation.setAccessible(true);
			generation.setLong(null, -1);
			var dirty = BeeDesignerClient.class.getDeclaredField("namesDirty");
			dirty.setAccessible(true);
			dirty.setBoolean(null, false);
		}
	}

	@Test
	void batchRetains4096CapacityAndRejectsBadTypesLengthsDuplicatesAndDepth() {
		UUID token = new UUID(5, 5);
		var batch = new DesignerNameBatch(token, 5, header(4096));
		for (int index = 0; index < 4096; index++) {
			batch.add(entry("test:bee_" + index, "Bee " + index));
		}
		assertEquals(4096, batch.finish(header(4096)).names().size());
		assertThrows(IllegalArgumentException.class, () -> new DesignerNameBatch(token, 5, header(4097)));
		JsonObject bad = entry("test:bad", "x".repeat(129));
		assertThrows(IllegalArgumentException.class, () -> new DesignerNameBatch(token, 5, header(1)).add(bad));
		JsonObject booleanName = entry("test:boolean", "Name");
		booleanName.getAsJsonObject("names").addProperty("en_us", false);
		assertThrows(IllegalArgumentException.class, () -> new DesignerNameBatch(token, 5, header(1)).add(booleanName));
		var duplicate = new DesignerNameBatch(token, 5, header(2));
		duplicate.add(entry("test:same", "One"));
		assertThrows(IllegalArgumentException.class, () -> duplicate.add(entry("test:same", "Two")));
		assertThrows(IllegalArgumentException.class, () -> BeeDesignerProtocol.parse("{\"names\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}"));
	}

	private static JsonObject header(int count) {
		JsonObject data = new JsonObject();
		data.addProperty("count", count);
		return data;
	}

	private static JsonObject entry(String id, String name) {
		JsonObject data = new JsonObject();
		data.addProperty("id", id);
		JsonObject names = new JsonObject();
		names.addProperty("en_us", name);
		data.add("names", names);
		data.addProperty("deleted", false);
		return data;
	}

	private static BeeDesignerProtocol.Response response(UUID token, long generation, String action, JsonObject data) {
		return new BeeDesignerProtocol.Response(token, generation, action, data.toString());
	}
}
