package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply.Status.*;

/** 一个菜单只保留一个未扣物的输入请求；逾时、关闭或源栈变化直接取消，不自动重放。 */
final class CoreAutomaticBeeInput {
	private final TerminalRequest request;
	private final ItemStack source;
	private final long deadline;
	private final boolean creative;
	private final CoreBeeInputSearch search = new CoreBeeInputSearch();
	CoreAutomaticBeeInput(TerminalRequest request, ServerPlayer player) {
		this.request = request; source = player.getInventory().getItem(request.inventorySlot()).copy();
		deadline = player.server.overworld().getGameTime() + 80; creative = player.hasInfiniteMaterials();
	}
	TerminalReply step(NetworkCoreMenu menu, ServerPlayer player, long now) {
		var core = menu.exchangeCore(player);
		if (core == null) return reply(UNAVAILABLE, 0);
		if (now >= deadline || creative != player.hasInfiniteMaterials()
				|| !ItemStack.matches(source, player.getInventory().getItem(request.inventorySlot()))) return reply(STALE, 0);
		var target = search.next(core, player);
		if (target == null) return search.exhausted() ? reply(NO_SPACE, 0) : null;
		var result = menu.exchangeBee(player, target.member(), target.slot(), target.revision(), null,
				request.inventorySlot(), CoreBeeCageExchange.Action.INSERT, false);
		if (result.status() == CoreBeeCageExchange.Status.UNAVAILABLE || result.status() == CoreBeeCageExchange.Status.OCCUPIED) return null;
		return reply(TerminalReply.Status.valueOf(result.status().name()), result.moved());
	}
	private TerminalReply reply(TerminalReply.Status status, int moved) {
		return new TerminalReply(request.containerId(), request.session(), request.sequence(), status, moved, 0, null);
	}
}
