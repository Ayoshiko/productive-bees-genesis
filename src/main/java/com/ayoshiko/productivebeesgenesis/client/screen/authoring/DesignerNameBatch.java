package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCollection;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 名称先按批次保管，完整通过校验后才替换当前连接的显示状态。 */
final class DesignerNameBatch {
	private static final int MAX_BYTES = 8 * 1024 * 1024;
	private final UUID token;
	private final long generation;
	private final int count;
	private final Map<String, JsonObject> names = new HashMap<>();
	private final Set<String> retired = new HashSet<>();
	private int bytes;

	DesignerNameBatch(UUID token, long generation, JsonObject header) {
		this.token = token;
		this.generation = generation;
		if (generation < 0 || !header.has("count") || !header.get("count").isJsonPrimitive()
				|| !header.getAsJsonPrimitive("count").isNumber()) {
			throw new IllegalArgumentException("Invalid bee name batch");
		}
		count = header.get("count").getAsBigDecimal().intValueExact();
		if (count < 0 || count > BeeDesignerCollection.MAX_BEES) {
			throw new IllegalArgumentException("Bee name batch exceeds capacity");
		}
	}

	boolean matches(UUID token, long generation) {
		return this.token.equals(token) && this.generation == generation;
	}

	long generation() {
		return generation;
	}

	void add(JsonObject data) {
		if (names.size() >= count || !data.has("id") || !data.get("id").isJsonPrimitive()
				|| !data.getAsJsonPrimitive("id").isString() || !data.has("names")
				|| !data.get("names").isJsonObject()) {
			throw new IllegalArgumentException("Invalid bee name entry");
		}
		String id = data.get("id").getAsString();
		ResourceLocation bee = ResourceLocation.parse(id);
		if (!id.equals(bee.toString()) || !bee.getPath().matches("[a-z0-9_.-]{1,96}") || names.containsKey(id)) {
			throw new IllegalArgumentException("Invalid or duplicate bee name identity");
		}
		JsonObject values = data.getAsJsonObject("names");
		if (values.size() > 64) {
			throw new IllegalArgumentException("Too many bee name languages");
		}
		for (var entry : values.entrySet()) {
			if (!entry.getKey().matches("[a-z]{2,3}(?:_[a-z0-9]{2,8})?")
					|| !entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
				throw new IllegalArgumentException("Invalid bee name language or text");
			}
			String value = entry.getValue().getAsString();
			if (value.length() > 128 || value.contains("%")) {
				throw new IllegalArgumentException("Invalid bee name length or format");
			}
		}
		if (!data.has("deleted") || !data.get("deleted").isJsonPrimitive()
				|| !data.getAsJsonPrimitive("deleted").isBoolean()) {
			throw new IllegalArgumentException("Invalid bee name lifecycle");
		}
		int size = data.toString().getBytes(StandardCharsets.UTF_8).length;
		if (size > MAX_BYTES - bytes) {
			throw new IllegalArgumentException("Bee names exceed byte budget");
		}
		bytes += size;
		names.put(id, values.deepCopy());
		if (data.get("deleted").getAsBoolean()) {
			retired.add(id);
		}
	}

	Snapshot finish(JsonObject footer) {
		if (!footer.has("count") || !footer.get("count").isJsonPrimitive()
				|| !footer.getAsJsonPrimitive("count").isNumber()
				|| footer.get("count").getAsBigDecimal().intValueExact() != count || names.size() != count) {
			throw new IllegalArgumentException("Incomplete bee name batch");
		}
		return new Snapshot(Map.copyOf(names), Set.copyOf(retired));
	}

	record Snapshot(Map<String, JsonObject> names, Set<String> retired) {}
}
