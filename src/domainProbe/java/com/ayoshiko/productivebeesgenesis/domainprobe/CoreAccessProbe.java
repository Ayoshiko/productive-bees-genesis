package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 正式命令及菜单的服务器验证；三个不同 UUID 的假玩家不构成真实登录验收。 */
final class CoreAccessProbe {
	static void verify(NetworkCoreBlockEntity core, ServerPlayer owner, NetworkSavedData data,
			ProductKey product, JsonObject report) throws Exception {
		var first = player(owner, "AccessFirst"); var second = player(owner, "AccessSecond");
		var stranger = player(owner, "AccessStranger");
		require(!core.allowed(first) && core.createMenu(71, first.getInventory(), first) == null, "Untrusted guest opened core");
		require(command(core, stranger, "grant " + first.getUUID()) == 0, "Stranger granted access");
		require(command(core, owner, "grant " + first.getUUID()) == 1
				&& command(core, owner, "grant " + second.getUUID()) == 1, "Owner grant command failed");
		require(command(core, first, "grant " + stranger.getUUID()) == 0 && !core.allowed(stranger), "Guest delegated access");
		require(command(core, owner, "list") == 1, "Owner cannot list offline UUID grants");
		var a = open(core, first, 71); var b = open(core, second, 72);
		require(!a.canManage() && core.ownerAllowed(owner), "Guest acquired owner role");
		for (int button = 0; button <= 3; button++) require(!a.clickMenuButton(first, button), "Guest used management control");
		var page = a.querySelections(first, NetworkSelectionSession.Kind.PRODUCTS, 0);
		require(page != null, "Authorized guest could not read selections");
		second.containerMenu = a;
		require(a.querySelections(second, NetworkSelectionSession.Kind.PRODUCTS, 0) == null, "Authorized UUID reused another player's menu");
		second.containerMenu = b;

		var before = data.checkpoint(); long revision = before.ledger().revision();
		require(a.withdrawProduct(first, product, revision, 0, 1, false).moved() == 1, "First guest withdrawal failed");
		require(b.withdrawProduct(second, product, revision, 0, 1, false).status() == CoreProductWithdrawal.Status.STALE,
				"Concurrent stale revision withdrew twice");
		require(b.withdrawProduct(second, product, data.checkpoint().ledger().revision(), 0, 1, false).moved() == 1,
				"Second guest could not retry with current authority");
		require(first.getInventory().getItem(0).getCount() == 1 && second.getInventory().getItem(0).getCount() == 1
				&& data.checkpoint().ledger().balances().get(product).equals(before.ledger().balances().get(product).subtract(ProductAmount.of(2))),
				"Guest inventory and ledger conservation failed");
		require(before.energy() == data.checkpoint().energy() && before.transfers() == data.checkpoint().transfers(),
				"Access transfer changed energy or ownership");

		require(command(core, owner, "revoke " + first.getUUID()) == 1, "Owner revoke failed");
		var after = data.checkpoint();
		require(!a.stillValid(first) && a.selectedRow(first, page.session(), page.generation(), 0) == null
				&& a.withdrawProduct(first, product, after.ledger().revision(), 1, 1, false).moved() == 0, "Revoked menu retained access");
		require(command(core, owner, "grant " + first.getUUID()) == 1 && core.allowed(first) && !a.stillValid(first),
				"Regrant resurrected old menu");
		a.broadcastChanges();
		require(data.checkpoint() == after, "Rejected access changed assets");
		a = open(core, first, 73);
		var location = first.position(); first.setPos(location.add(20, 0, 0));
		require(!a.stillValid(first), "Distant guest retained menu"); first.setPos(location);
		require(CompletableFuture.supplyAsync(() -> core.changeGuest(owner, stranger.getUUID(), true)).get(5, TimeUnit.SECONDS)
				== CoreAccessState.Change.DENIED && !core.allowed(stranger), "Background thread changed access");

		var saved = core.saveWithoutMetadata(owner.registryAccess());
		core.loadWithComponents(saved, owner.registryAccess());
		require(core.allowed(first) && core.allowed(second) && !a.stillValid(first), "Core reload lost access or retained old menu");
		var corrupt = saved.copy(); corrupt.getCompound("access").putInt("version", 99);
		core.loadWithComponents(corrupt, owner.registryAccess());
		require(core.ownerAllowed(owner) && !core.allowed(first) && core.guests(owner) == null
				&& corrupt.get("access").equals(core.saveWithoutMetadata(owner.registryAccess()).get("access")),
				"Corrupt access data was lost or became permissive");
		core.loadWithComponents(saved, owner.registryAccess());
		require(command(core, owner, "revoke " + first.getUUID()) == 1
				&& command(core, owner, "revoke " + second.getUUID()) == 1, "Offline UUID revoke failed");
		require(owner.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(core.getBlockPos()).inflate(3)).isEmpty(),
				"Access transactions created item entities");
		first.containerMenu = first.inventoryMenu; second.containerMenu = second.inventoryMenu;
		stranger.containerMenu = stranger.inventoryMenu;
		report.addProperty("coreAccessCommandsAndLeastPrivilege", true);
		report.addProperty("coreAccessViewerBindingRevocationAndRegrant", true);
		report.addProperty("coreAccessGuestLedgerConservation", true);
		report.addProperty("coreAccessReloadAndCorruptDataPreserved", true);
	}
	private static ServerPlayer player(ServerPlayer owner, String name) {
		var player = FakePlayerFactory.get(owner.serverLevel(), new GameProfile(UUID.randomUUID(), name));
		player.setPos(owner.position()); new PlayerInventorySyncProbe(player); return player;
	}
	private static NetworkCoreMenu open(NetworkCoreBlockEntity core, ServerPlayer player, int id) {
		var menu = (NetworkCoreMenu) core.createMenu(id, player.getInventory(), player);
		require(menu != null, "Authorized guest menu missing"); player.containerMenu = menu; return menu;
	}
	private static int command(NetworkCoreBlockEntity core, ServerPlayer player, String operation) throws Exception {
		var pos = core.getBlockPos();
		return player.serverLevel().getServer().getCommands().getDispatcher().execute(
				"pbgnetwork access " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " " + operation,
				player.createCommandSourceStack());
	}
	private CoreAccessProbe() { }
}
