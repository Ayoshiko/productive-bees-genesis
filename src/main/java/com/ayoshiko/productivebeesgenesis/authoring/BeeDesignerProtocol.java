package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.network.FriendlyByteBuf;
import com.ayoshiko.productivebeesgenesis.network.ForgeNetwork;
import net.minecraftforge.network.NetworkDirection;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/** 每个请求只处理一只蜂；限制实际 UTF-8 字节，低于原版服务端自定义包上限。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class BeeDesignerProtocol {
	public static final int MAX_BYTES = 28_000;
	public static Consumer<Response> clientReceiver = response -> {};

	private BeeDesignerProtocol() {
	}

	private static String readJson(FriendlyByteBuf buffer) {
		String json = buffer.readUtf(MAX_BYTES);
		checkSize(json);
		return json;
	}

	public static void checkSize(String json) {
		if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
			throw new IllegalArgumentException("Bee document exceeds 28000 UTF-8 bytes");
		}
	}

	public static com.google.gson.JsonObject parse(String json) {
		checkSize(json);
		checkDepth(json);
		return com.google.gson.JsonParser.parseString(json).getAsJsonObject();
	}

	static void checkDepth(String json) {
		int depth = 0;
		boolean quoted = false;
		boolean escaped = false;
		for (int index = 0; index < json.length(); index++) {
			char value = json.charAt(index);
			if (quoted) {
				if (escaped) {
					escaped = false;
				} else if (value == '\\') {
					escaped = true;
				} else if (value == '"') {
					quoted = false;
				}
			} else if (value == '"') {
				quoted = true;
			} else if (value == '{' || value == '[') {
				if (++depth > 32) {
					throw new IllegalArgumentException("Designer JSON nesting exceeds 32 levels");
				}
			} else if (value == '}' || value == ']') {
				depth--;
			}
		}
	}


    public record Request(UUID session, long sequence, String action, String json) {
        public static Request decode(FriendlyByteBuf b) { return new Request(b.readUUID(),b.readLong(),b.readUtf(24),readJson(b)); }
        public void encode(FriendlyByteBuf b) { checkSize(json); b.writeUUID(session); b.writeLong(sequence); b.writeUtf(action,24); b.writeUtf(json,MAX_BYTES); }
    }
    public record Response(UUID session, long sequence, String action, String json) {
        public static Response decode(FriendlyByteBuf b) { return new Response(b.readUUID(),b.readLong(),b.readUtf(24),readJson(b)); }
        public void encode(FriendlyByteBuf b) { checkSize(json); b.writeUUID(session); b.writeLong(sequence); b.writeUtf(action,24); b.writeUtf(json,MAX_BYTES); }
    }
    public static void register() {
        ForgeNetwork.register(Request.class, Request::encode, Request::decode, (payload, context) -> BeeDesignerServer.handle(context.player(), payload), NetworkDirection.PLAY_TO_SERVER);
        ForgeNetwork.register(Response.class, Response::encode, Response::decode, (payload, context) -> clientReceiver.accept(payload), NetworkDirection.PLAY_TO_CLIENT);
    }
}
