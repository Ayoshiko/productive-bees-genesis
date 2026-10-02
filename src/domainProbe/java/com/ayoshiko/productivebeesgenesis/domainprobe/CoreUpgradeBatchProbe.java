package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 两个真实托管成员共用一个真实背包槽；只在真实 tick 推进协议步骤。 */
final class CoreUpgradeBatchProbe {
	private static int phase, started;
	private static long sequence;
	private static final Map<UUID, AssetImage> original = new HashMap<>();
	private static TerminalView page;
	private static List<UUID> members;
	static boolean advance(NetworkSavedData data, ServerPlayer player, NetworkCoreMenu menu, JsonObject report) {
		if (phase == 5) return true;
		if (phase == 0) {
			started = player.server.getTickCount();
			for (var record : data.checkpoint().ownedMachines().activeValues()) original.put(record.claim().member(), record.assets());
			player.getInventory().setItem(24, UpgradeUtils.getStack(Upgrade.SPEED, 2)); page = query(player, menu);
			members = java.util.stream.IntStream.range(0, 2).mapToObj(i -> ((NetworkSelectionSession.MemberRow) menu.selectedRow(player, menu.terminalSession(), page.generation(), i)).claim().member()).toList();
			var before = data.checkpoint(); var inventory = player.getInventory().save(new ListTag());
			var reply = TerminalPayloads.handle(player, request(menu, UPGRADE_PREVIEW_INSTALL));
			require(reply != null && reply.preview() != null && reply.preview().movable() == 1 && reply.moved() == 0, "Upgrade preview missing");
			require(reply.preview().after().timeFactor() < reply.preview().before().timeFactor()
					&& reply.preview().after().energyPerTick() > reply.preview().before().energyPerTick(), "Speed forecast ignored current upgrade parameters");
			require(before == data.checkpoint() && inventory.equals(player.getInventory().save(new ListTag())), "Preview changed assets or revision");
			phase++; return false;
		}
		if (phase == 1) {
			var invalid = TerminalPayloads.handle(player, new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence,
					UPGRADE_PREVIEW_INSTALL, page.generation(), 0, -1, 24, 1));
			require(invalid != null && invalid.status() == TerminalReply.Status.INVALID, "Negative preview choice did not fail cleanly");
			var request = request(menu, UPGRADE_INSTALL_PAGE); var reply = TerminalPayloads.handle(player, request);
			batch(reply, TerminalReply.Status.MOVED, TerminalReply.Status.MOVED, 2);
			require(TerminalPayloads.handle(player, request) == null && player.getInventory().getItem(24).isEmpty(), "Batch replay changed finite source");
			phase++; return false;
		}
		if (phase == 2) {
			page = query(player, menu); batch(TerminalPayloads.handle(player, request(menu, UPGRADE_REMOVE_PAGE)), TerminalReply.Status.MOVED, TerminalReply.Status.MOVED, 2);
			unchanged(data); require(player.getInventory().getItem(24).getCount() == 2, "Batch return lost upgrades");
			phase++; return false;
		}
		if (phase == 3) {
			player.getInventory().setItem(24, UpgradeUtils.getStack(Upgrade.SPEED, 1)); page = query(player, menu);
			batch(TerminalPayloads.handle(player, request(menu, UPGRADE_INSTALL_PAGE)), TerminalReply.Status.MOVED, TerminalReply.Status.EMPTY, 1);
			remove(data, player, menu, members.getFirst()); unchanged(data);
			phase++; return false;
		}
		if (player.server.getTickCount() - started < 21) return false;
		player.getInventory().setItem(24, UpgradeUtils.getStack(Upgrade.SPEED, 2)); page = query(player, menu);
		var first = members.getFirst(); var current = data.checkpoint().ownedMachines().get(first);
		require(menu.exchangeUpgrade(player, first, current.centrifuge().revision(), Upgrade.SPEED, 24, 1, MemberUpgradeService.Action.INSTALL, false).moved() == 1, "Stale batch setup failed");
		remove(data, player, menu, first);
		batch(TerminalPayloads.handle(player, request(menu, UPGRADE_INSTALL_PAGE)), TerminalReply.Status.STALE, TerminalReply.Status.MOVED, 1);
		remove(data, player, menu, members.get(1)); unchanged(data);
		require(player.getInventory().getItem(24).getCount() == 2, "Partial stale batch lost items");
		report.addProperty("coreUpgradeBatchPartialStalePreviewAndConservation", true);
		original.clear(); members = null; page = null; phase = 5; return true;
	}
	private static void unchanged(NetworkSavedData data) {
		original.forEach((member, image) -> require(image.equals(data.checkpoint().ownedMachines().get(member).assets()), "Batch changed unrelated or restored assets"));
	}
	private static void remove(NetworkSavedData data, ServerPlayer player, NetworkCoreMenu menu, UUID member) {
		require(menu.exchangeUpgrade(player, member, data.checkpoint().ownedMachines().get(member).centrifuge().revision(), Upgrade.SPEED, 24, 1,
				MemberUpgradeService.Action.REMOVE, false).moved() == 1, "Batch fixture return failed");
	}
	private static TerminalView query(ServerPlayer player, NetworkCoreMenu menu) {
		var reply = TerminalPayloads.handle(player, new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence, UPGRADES, 0, -1, -1, 24, 0));
		require(reply != null && reply.status() == TerminalReply.Status.OK && reply.view() != null && reply.view().rows().size() == 2, "Batch page missing"); return reply.view();
	}
	private static TerminalRequest request(NetworkCoreMenu menu, TerminalRequest.Operation operation) {
		return new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence, operation, page.generation(), 0, 0, 24, 1);
	}
	private static void batch(TerminalReply reply, TerminalReply.Status first, TerminalReply.Status second, int moved) {
		require(reply != null && reply.status() == TerminalReply.Status.BATCH_COMPLETE && reply.moved() == moved && reply.upgrades().size() == 2
				&& reply.upgrades().get(0).status() == first && reply.upgrades().get(1).status() == second, "Unexpected batch outcome: " + reply);
	}
	private CoreUpgradeBatchProbe() { }
}
