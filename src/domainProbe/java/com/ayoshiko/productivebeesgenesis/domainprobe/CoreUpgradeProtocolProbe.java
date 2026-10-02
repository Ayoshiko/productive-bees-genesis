package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.JsonObject;
import java.util.UUID;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

/** 注册处理器的服务器入口；每 tick 两次，四个真实 tick 内核验进度变化、旧选择和 ABA。 */
final class CoreUpgradeProtocolProbe {
	private static int phase;
	private static com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage originalAssets;
	static boolean advance(NetworkSavedData data, UUID member, ServerPlayer player, NetworkCoreMenu menu, JsonObject report) {
		var service = new NetworkBeeService(data, NetworkPersistence.directory(player.server));
		if (phase == 0) {
		player.getInventory().items.set(24, UpgradeUtils.getStack(Upgrade.SPEED, 4));
		var initial = data.checkpoint().ownedMachines().get(member);
		originalAssets = initial.assets();
		var page = query(player, menu, 1); int row = row(player, menu, page, member);
		var bee = initial.bees().bee(0);
		require(service.advance(player.serverLevel(), member, 0, bee.revision(), bee.plan().recipeRevision(), bee.plan().capabilityRevision(), 1, 0, false) == BeeWorkExecutor.Status.READY, "Protocol progress setup failed");
		var install = request(menu, 2, UPGRADE_INSTALL, page.generation(), row, 0);
		check(TerminalPayloads.handle(player, install), TerminalReply.Status.MOVED, 1);
		require(TerminalPayloads.handle(player, install) == null, "Upgrade packet replay accepted");
		phase++; return false;
		}
		if (phase == 1) {
		var page = query(player, menu, 3); int row = row(player, menu, page, member);
		for (var action : java.util.List.of(MemberUpgradeService.Action.REMOVE, MemberUpgradeService.Action.INSTALL)) {
			var state = data.checkpoint().ownedMachines().get(member).bees();
			require(menu.exchangeUpgrade(player, member, state.revision(), Upgrade.SPEED, 24, 1, action, false).moved() == 1, "ABA setup failed");
		}
		var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag());
		check(TerminalPayloads.handle(player, request(menu, 4, UPGRADE_REMOVE, page.generation(), row, 0)), TerminalReply.Status.STALE, 0);
		require(before == data.checkpoint() && inventory.equals(player.getInventory().save(new ListTag())), "Old upgrade snapshot changed assets");
		phase++; return false;
		}
		if (phase == 2) {
		var before = data.checkpoint(); var page = query(player, menu, 5); int row = row(player, menu, page, member);
		check(TerminalPayloads.handle(player, request(menu, 6, UPGRADE_INSTALL, page.generation(), row, 15)), TerminalReply.Status.INVALID, 0);
		require(before == data.checkpoint(), "Unknown upgrade choice changed state");
		phase++; return false;
		}
		var page = query(player, menu, 7); int row = row(player, menu, page, member);
		check(TerminalPayloads.handle(player, request(menu, 8, UPGRADE_REMOVE, page.generation(), row, 0)), TerminalReply.Status.MOVED, 1);
		require(player.getInventory().getItem(24).getCount() == 4 && originalAssets.equals(data.checkpoint().ownedMachines().get(member).assets()), "Protocol upgrade round trip lost assets");
		var bee = data.checkpoint().ownedMachines().get(member).bees().bee(0);
		require(service.advance(player.serverLevel(), member, 0, bee.revision(), bee.plan().recipeRevision(), bee.plan().capabilityRevision(), bee.plan().cycleTicks() - bee.progress(), 1, false) == BeeWorkExecutor.Status.READY, "Protocol old work could not finish");
		require(service.settle(player.serverLevel(), member, 0, data.checkpoint().ownedMachines().get(member).bees().bee(0).revision()), "Protocol old work did not settle");
		report.addProperty("coreUpgradeProtocolProgressReplayAbaAndConservation", true);
		originalAssets = null; return true;
	}
	private static TerminalView query(ServerPlayer player, NetworkCoreMenu menu, long sequence) {
		var reply = TerminalPayloads.handle(player, new TerminalRequest(menu.containerId, menu.terminalSession(), sequence, UPGRADES, 0, -1, -1, 24, 0));
		check(reply, TerminalReply.Status.OK, 0); require(reply.view() != null && reply.view().kind() == NetworkSelectionSession.Kind.UPGRADES, "Missing upgrade page");
		for (var row : reply.view().rows()) require(row.upgrades().size() <= 10 && row.bees().isEmpty(), "Unbounded upgrade page");
		return reply.view();
	}
	private static int row(ServerPlayer player, NetworkCoreMenu menu, TerminalView page, UUID member) {
		for (int i = 0; i < page.rows().size(); i++) {
			var selected = menu.selectedRow(player, menu.terminalSession(), page.generation(), i);
			if (selected instanceof NetworkSelectionSession.MemberRow value && value.claim().member().equals(member)) {
				require(page.rows().get(i).upgrades().size() == 9, "Apiary upgrade whitelist mismatch"); return i;
			}
		}
		throw new IllegalStateException("Missing protocol member");
	}
	private static TerminalRequest request(NetworkCoreMenu menu, long sequence, TerminalRequest.Operation operation, long generation, int row, int choice) {
		return new TerminalRequest(menu.containerId, menu.terminalSession(), sequence, operation, generation, row, choice, 24, 1);
	}
	private static void check(TerminalReply reply, TerminalReply.Status status, int moved) { require(reply != null && reply.status() == status && reply.moved() == moved, "Upgrade protocol result: " + reply); }
	private CoreUpgradeProtocolProbe() { }
}
