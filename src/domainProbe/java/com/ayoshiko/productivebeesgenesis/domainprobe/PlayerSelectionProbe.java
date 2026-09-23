package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.MemberRow;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Page;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.ProductRow;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.Kind.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 在既有物品／蜂笼夹具上走正式菜单查询；不替代客户端传输与真实玩家文件验证。 */
final class PlayerSelectionProbe {
	private static Page beforeExchange, running, expiring;
	private static long openedAt;

	static void products(NetworkCoreMenu menu, ServerPlayer player, NetworkSavedData data, JsonObject report) {
		var source = data.checkpoint(); var seen = ConcurrentHashMap.<ProductKey>newKeySet();
		var page = menu.querySelections(player, PRODUCTS, 0);
		while (true) {
			require(page != null && page.rows().size() <= 8, "Missing or unbounded product selection page");
			for (int index = 0; index < page.rows().size(); index++) {
				var row = (ProductRow) page.rows().get(index);
				require(seen.add(row.key()) && row.owned().equals(source.ledger().balances().get(row.key()))
						&& row.available().equals(source.ledger().available(row.key())), "Selection lost exact or reserved product amount");
				require(menu.selectedRow(player, page.session(), page.generation(), index) == row, "Menu cannot resolve its product row");
			}
			if (!page.hasNext()) break;
			page = menu.querySelections(player, PRODUCTS, page.generation());
		}
		require(seen.equals(source.ledger().balances().keySet()) && data.checkpoint() == source, "Product query changed authority or lost keys");
		report.addProperty("playerSelectionsExactProductsAndReservations", true);
	}

	static void beforeExchanges(NetworkCoreMenu menu, ServerPlayer player) {
		beforeExchange = memberPage(menu, player);
	}
	static void beforeRuntime(NetworkCoreMenu menu, ServerPlayer player, NetworkSavedData data) {
		var previous = (MemberRow) beforeExchange.rows().getFirst();
		require(!NetworkSelectionSession.sameRoster(previous, data.checkpoint().ownedMachines().get(previous.claim().member())),
				"Cage replacement retained a selected roster");
		running = memberPage(menu, player); beforeExchange = null;
	}
	static void afterRuntime(NetworkCoreMenu menu, ServerPlayer player, NetworkSavedData data, JsonObject report) {
		var row = (MemberRow) running.rows().getFirst();
		require(menu.selectedRow(player, running.session(), running.generation(), 0) == row
				&& NetworkSelectionSession.sameRoster(row, data.checkpoint().ownedMachines().get(row.claim().member())),
				"Ordinary production invalidated roster selection");
		report.addProperty("playerSelectionsStableProductionAndRosterInvalidation", true); running = null;
	}
	static void beginExpiry(NetworkCoreMenu menu, ServerPlayer player) {
		expiring = memberPage(menu, player); openedAt = player.serverLevel().getGameTime();
	}
	static boolean finishExpiry(NetworkCoreMenu menu, ServerPlayer player, NetworkCoreBlockEntity core,
			JsonObject report) throws Exception {
		// FakePlayer 不在玩家列表内，显式驱动原版菜单 tick 入口。
		menu.broadcastChanges();
		long elapsed = player.serverLevel().getGameTime() - openedAt;
		if (elapsed < NetworkSelectionSession.LIFETIME_TICKS) {
			require(menu.selectedRow(player, expiring.session(), expiring.generation(), 0) != null, "Selection expired early");
			return false;
		}
		require(menu.selectedRow(player, expiring.session(), expiring.generation(), 0) == null, "Menu tick retained expired selection");
		var page = memberPage(menu, player);
		require(page.generation() > expiring.generation(), "Expired page generation was reused");
		require(menu.selectedRow(player, UUID.randomUUID(), page.generation(), 0) == null
				&& menu.selectedRow(player, page.session(), page.generation(), -1) == null
				&& menu.selectedRow(player, page.session(), page.generation(), 8) == null, "Forged selection accepted");
		require(menu.querySelections(player, PRODUCTS, page.generation()) == null, "Page kind changed without refresh");
		var stranger = FakePlayerFactory.get(player.serverLevel(), new GameProfile(UUID.randomUUID(), "SelectionOther"));
		stranger.setPos(player.position()); stranger.containerMenu = menu;
		require(menu.querySelections(stranger, MEMBERS, 0) == null, "Stranger read owner selections");
		stranger.containerMenu = stranger.inventoryMenu;
		var position = player.position(); player.setPos(position.add(20, 0, 0));
		require(menu.querySelections(player, MEMBERS, 0) == null, "Distant player read selections"); player.setPos(position);
		require(CompletableFuture.supplyAsync(() -> menu.querySelections(player, MEMBERS, 0)).get(5, TimeUnit.SECONDS) == null,
				"Off-thread selection succeeded");
		var replacement = (NetworkCoreMenu) core.createMenu(menu.containerId, player.getInventory(), player);
		require(replacement != null, "Selection replacement menu unavailable"); player.containerMenu = replacement;
		require(menu.selectedRow(player, page.session(), page.generation(), 0) == null, "Old menu still selected a row");
		var fresh = memberPage(replacement, player);
		require(!fresh.session().equals(page.session())
				&& replacement.selectedRow(player, page.session(), fresh.generation(), 0) == null, "Reopened menu reused selection session");
		replacement.removed(player);
		require(replacement.querySelections(player, MEMBERS, 0) == null
				&& replacement.selectedRow(player, fresh.session(), fresh.generation(), 0) == null, "Closed menu resurrected selection");
		player.containerMenu = menu; expiring = null;
		report.addProperty("playerSelectionsExpiryPermissionsAndClose", true);
		report.addProperty("playerSelectionsLifetimeTicks", elapsed); return true;
	}
	private static Page memberPage(NetworkCoreMenu menu, ServerPlayer player) {
		var page = menu.querySelections(player, MEMBERS, 0);
		require(page != null && page.rows().size() == 1 && !page.hasNext(), "Missing cage member page");
		var member = (MemberRow) page.rows().getFirst();
		require(member.bees().size() == 3, "Basic hive page must include empty slots"); return page;
	}
	private PlayerSelectionProbe() { }
}
