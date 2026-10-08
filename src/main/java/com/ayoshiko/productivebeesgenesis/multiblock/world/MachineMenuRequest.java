package com.ayoshiko.productivebeesgenesis.multiblock.world;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.HandlerThread;

/** 固定 52 字节请求；客户端只给有限索引，不能上传蜜蜂、物品或配方数据。 */
public record MachineMenuRequest(int containerId, UUID session, long sequence, long viewRevision, int action, int slot, int inventorySlot, int amount) implements CustomPacketPayload {
	public static final Type<MachineMenuRequest> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "machine_menu_request"));
	public static final StreamCodec<FriendlyByteBuf, MachineMenuRequest> STREAM_CODEC = new StreamCodec<>() {
		@Override public MachineMenuRequest decode(FriendlyByteBuf b) {
			if (b.readableBytes() != 52) throw new IllegalArgumentException("Invalid machine request size");
			return new MachineMenuRequest(b.readInt(), b.readUUID(), b.readLong(), b.readLong(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
		}
		@Override public void encode(FriendlyByteBuf b, MachineMenuRequest r) {
			b.writeInt(r.containerId); b.writeUUID(r.session); b.writeLong(r.sequence); b.writeLong(r.viewRevision);
			b.writeInt(r.action); b.writeInt(r.slot); b.writeInt(r.inventorySlot); b.writeInt(r.amount);
		}
	};
	public MachineMenuRequest {
		Objects.requireNonNull(session);
		if (containerId < 0 || sequence < 1 || viewRevision < 0 || action < 0 || action >= 6 || slot < 0 || slot >= (action >= 4 ? com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades.SLOTS : 6)
				|| inventorySlot < 0 || inventorySlot >= 36 || amount < 1 || amount > 64) throw new IllegalArgumentException("Invalid machine request");
	}
	static void register(RegisterPayloadHandlersEvent event) {
		event.registrar("10").executesOn(HandlerThread.MAIN).playToServer(TYPE, STREAM_CODEC, (request, context) -> {
			if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof MachineMenu menu) menu.request(player, request);
		});
	}
	@Override public Type<MachineMenuRequest> type() { return TYPE; }
}
