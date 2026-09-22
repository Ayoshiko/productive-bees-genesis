package com.ayoshiko.productivebeesgenesis.domainprobe;

import io.netty.buffer.Unpooled;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** 在真实服务端发送边界截获包，验证原版 codec；不把此测试称为客户端交互验收。 */
final class PlayerInventorySyncProbe extends ServerGamePacketListenerImpl {
	ClientboundContainerSetSlotPacket last;
	int packets;
	boolean failNext;
	Runnable onSend;
	PlayerInventorySyncProbe(ServerPlayer player) {
		super(player.serverLevel().getServer(), new Connection(PacketFlow.SERVERBOUND) {
			@Override public void setListenerForServerboundHandshake(PacketListener listener) { }
		}, player, CommonListenerCookie.createInitial(player.getGameProfile(), false));
	}
	@Override public void send(Packet<?> packet) {
		if (!(packet instanceof ClientboundContainerSetSlotPacket update)) return;
		packets++;
		if (failNext) { failNext = false; throw new IllegalStateException("Injected inventory sync failure"); }
		var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess(), net.neoforged.neoforge.network.connection.ConnectionType.NEOFORGE);
		try {
			ClientboundContainerSetSlotPacket.STREAM_CODEC.encode(buffer, update);
			last = ClientboundContainerSetSlotPacket.STREAM_CODEC.decode(buffer);
			DomainProbeServer.require(!buffer.isReadable(), "Inventory packet has trailing data");
		} finally { buffer.release(); }
		if (onSend != null) { var callback = onSend; onSend = null; callback.run(); }
	}
}
