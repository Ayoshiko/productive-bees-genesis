package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

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

	private static String readJson(RegistryFriendlyByteBuf buffer) {
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

	public record Request(UUID session, long sequence, String action, String json) implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
				ProductiveBeesGenesis.MOD_ID, "bee_designer_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
			@Override
			public Request decode(RegistryFriendlyByteBuf buffer) {
				return new Request(buffer.readUUID(), buffer.readLong(), buffer.readUtf(24), readJson(buffer));
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, Request value) {
				checkSize(value.json);
				buffer.writeUUID(value.session);
				buffer.writeLong(value.sequence);
				buffer.writeUtf(value.action, 24);
				buffer.writeUtf(value.json, MAX_BYTES);
			}
		};

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	public record Response(UUID session, long sequence, String action, String json) implements CustomPacketPayload {
		public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
				ProductiveBeesGenesis.MOD_ID, "bee_designer_response"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = new StreamCodec<>() {
			@Override
			public Response decode(RegistryFriendlyByteBuf buffer) {
				return new Response(buffer.readUUID(), buffer.readLong(), buffer.readUtf(24), readJson(buffer));
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buffer, Response value) {
				checkSize(value.json);
				buffer.writeUUID(value.session);
				buffer.writeLong(value.sequence);
				buffer.writeUtf(value.action, 24);
				buffer.writeUtf(value.json, MAX_BYTES);
			}
		};

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	@SubscribeEvent
	public static void register(RegisterPayloadHandlersEvent event) {
		var registrar = event.registrar("2");
		registrar.playToServer(Request.TYPE, Request.CODEC, (payload, context) ->
				BeeDesignerServer.handle(context.player(), payload));
		registrar.playToClient(Response.TYPE, Response.CODEC, (payload, context) -> clientReceiver.accept(payload));
	}
}
