package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.function.Consumer;

/** 过滤配置读写的有界回执；常驻协议不引用客户端界面类。 */
public record FilterConfigResultPayload(UUID request, boolean success, String message, String snapshot) implements CustomPacketPayload {
	public static Consumer<FilterConfigResultPayload> receiver = result -> {};
	public static final Type<FilterConfigResultPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
			ProductiveBeesGenesis.MOD_ID, "filter_config_result"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FilterConfigResultPayload> CODEC = new StreamCodec<>() {
		@Override
		public FilterConfigResultPayload decode(RegistryFriendlyByteBuf buffer) {
			return new FilterConfigResultPayload(buffer.readUUID(), buffer.readBoolean(), buffer.readUtf(512), buffer.readUtf(BeeDesignerProtocol.MAX_BYTES));
		}

		@Override
		public void encode(RegistryFriendlyByteBuf buffer, FilterConfigResultPayload value) {
			BeeDesignerProtocol.checkSize(value.snapshot);
			buffer.writeUUID(value.request);
			buffer.writeBoolean(value.success);
			buffer.writeUtf(value.message, 512);
			buffer.writeUtf(value.snapshot, BeeDesignerProtocol.MAX_BYTES);
		}
	};

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
