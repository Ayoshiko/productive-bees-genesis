package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonNull;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;

/** 本地 JSON 诊断使用严格语法并保留位置；失败不回写工程。 */
final class DesignerJsonCodec {
    private DesignerJsonCodec() {
    }

    static JsonObject parse(String text) {
        BeeDesignerProtocol.checkSize(text);
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement value = read(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject()) {
                throw new IOException("Expected one JSON object " + reader);
            }
            return value.getAsJsonObject();
        } catch (IOException | NumberFormatException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 32) {
            throw new IOException("JSON depth exceeds 32 " + reader);
        }
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) {
                        throw new IOException("Duplicate JSON key: " + key + " " + reader);
                    }
                    object.add(key, read(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    array.add(read(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Unexpected JSON token " + reader);
        };
    }
}
