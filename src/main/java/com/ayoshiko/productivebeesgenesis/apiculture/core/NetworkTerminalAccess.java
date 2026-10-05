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
public final class NetworkTerminalAccess implements TerminalMenuAccess {
	private final NetworkTerminalBlockEntity source;
	private final NetworkCoreBlockEntity core;
	private final NetworkSavedData authority;
	private final Object connectionToken;
	private final TerminalScope scope;
	private boolean revoked;
	private NetworkTerminalAccess(NetworkTerminalBlockEntity source, NetworkCoreBlockEntity core, NetworkSavedData authority, TerminalScope scope) {
		this.source = source; this.core = core; this.authority = authority; this.scope = scope; connectionToken = source.token();
	}
	public static boolean open(ServerPlayer player, NetworkTerminalBlockEntity terminal) {
		return open(player, terminal, terminal.scope());
	}
	private static boolean open(ServerPlayer player, NetworkTerminalBlockEntity terminal, TerminalScope scope) {
		if (scope == TerminalScope.ALL || !terminal.combined() && scope != terminal.scope()) return false;
		var core = terminal.connection(); if (core == null) return false;
		var authority = core.ownership().readyAuthority(); if (authority == null) return false;
		var access = new NetworkTerminalAccess(terminal, core, authority, scope);
		if (!access.valid(player)) return false;
		var session = UUID.randomUUID();
		return player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> access.valid(viewer)
				? new NetworkCoreMenu(id, inventory, core, session, access) : null,
				Component.translatable(terminal.getBlockState().getBlock().getDescriptionId())),
				buffer -> { buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(session); buffer.writeBoolean(false);
					if (terminal.combined()) buffer.writeEnum(scope); }).isPresent();
	}
	public TerminalScope scope() { return scope; }
	public TerminalCraftingAccount crafting(ServerPlayer player) { return valid(player) ? source.crafting(core) : null; }
	public boolean combined() { return source.combined(); }
	/** 切换只重开同一来源的菜单；关闭旧菜单释放选择根，不能把旧行号解释为新类型。 */
	public boolean switchMode(ServerPlayer player, TerminalScope requested) {
		return combined() && requested != scope && valid(player) && open(player, source, requested);
	}
	public boolean valid(Player player) {
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
