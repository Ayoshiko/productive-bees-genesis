package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;

/** 固定终端实例、唯一邻接核心与权威域；失效不可在同一菜单内复活。 */
public final class NetworkTerminalAccess {
	private final NetworkTerminalBlockEntity source;
	private final NetworkCoreBlockEntity core;
	private final NetworkSavedData authority;
	private final Object connectionToken;
	private boolean revoked;
	private NetworkTerminalAccess(NetworkTerminalBlockEntity source, NetworkCoreBlockEntity core, NetworkSavedData authority) {
		this.source = source; this.core = core; this.authority = authority; connectionToken = source.token();
	}
	public static boolean open(ServerPlayer player, NetworkTerminalBlockEntity terminal) {
		var core = terminal.connection(); if (core == null) return false;
		var authority = core.ownership().readyAuthority(); if (authority == null) return false;
		var access = new NetworkTerminalAccess(terminal, core, authority);
		if (!access.valid(player)) return false;
		var session = UUID.randomUUID();
		return player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> access.valid(viewer)
				? new NetworkCoreMenu(id, inventory, core, session, access) : null,
				Component.translatable(terminal.getBlockState().getBlock().getDescriptionId())),
				buffer -> { buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(session); buffer.writeBoolean(false); }).isPresent();
	}
	TerminalScope scope() { return source.scope(); }
	boolean valid(Player player) {
		if (revoked) return false;
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || player.level() != level
				|| !player.isAlive() || player.isSpectator() || source.isRemoved() || source.getLevel() != level
				|| player.distanceToSqr(source.getBlockPos().getCenter()) > 64 || !core.permits(player)
				|| core.isRemoved() || !core.validNetworkReference() || !authority.identity().equals(core.network())
				|| core.ownership().readyAuthority() != authority) { revoked = true; return false; }
		var pos = source.getBlockPos();
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null || chunk.getBlockEntity(pos) != source || source.connection() != core || source.token() != connectionToken) {
			revoked = true; return false;
		}
		return true;
	}
}
