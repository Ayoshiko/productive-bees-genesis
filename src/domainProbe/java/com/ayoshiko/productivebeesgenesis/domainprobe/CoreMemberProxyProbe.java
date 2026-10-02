package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真 ServerPlayer 菜单及发送边界；客户端鼠标路径另由实际客户端验证。 */
final class CoreMemberProxyProbe {
	private static int phase, started;
	private static ServerPlayer owner;
	private static NetworkCoreMenu stale;
	static boolean advance(NetworkCoreBlockEntity core, NetworkSavedData data, TileEntityMekCentrifuge target, UUID member, UUID other, JsonObject report) {
		if (phase == 4) return true;
		var level = (net.minecraft.server.level.ServerLevel) core.getLevel();
		if (started == 0) started = level.getServer().getTickCount();
		if (level.getServer().getTickCount() - started < 21) return false;
		if (phase == 0) {
			owner = player(level, core.owner(), "ProxyOwner"); owner.setPos(target.getBlockPos().getCenter());
			owner.getInventory().setItem(24, UpgradeUtils.getStack(Upgrade.SPEED, 2));
			owner.getInventory().setItem(25, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME).copyWithCount(2));
			var original = data.checkpoint().ownedMachines().get(member).assets(); var neighbor = data.checkpoint().ownedMachines().get(other);
			var menu = open(owner, target); var page = menu.querySelections(owner, NetworkSelectionSession.Kind.UPGRADES, 0);
			require(page != null && page.rows().size() == 1 && !page.hasNext()
					&& ((NetworkSelectionSession.MemberRow) page.rows().getFirst()).claim().member().equals(member), "Proxy listed another member");
			owner.setPos(target.getBlockPos().getX() + 8, target.getBlockPos().getY() + 0.5, target.getBlockPos().getZ() + 0.5);
			require(!core.allowed(owner) && menu.stillValid(owner), "Proxy still depends on core interaction distance");
			for (var action : MemberUpgradeService.Action.values()) {
				require(menu.exchangeUpgrade(owner, member, data.checkpoint().ownedMachines().get(member).centrifuge().revision(), Upgrade.SPEED, 24, 1, action, false).moved() == 1, "Remote member upgrade failed");
				require(menu.exchangePbUpgrade(owner, member, data.checkpoint().ownedMachines().get(member).centrifuge().revision(), PbUpgradeType.TIME, 25, 1, action, false).moved() == 1, "Remote PB upgrade failed");
			}
			var before = data.checkpoint();
			require(menu.exchangeUpgrade(owner, other, neighbor.centrifuge().revision(), Upgrade.SPEED, 24, 1, MemberUpgradeService.Action.INSTALL, false).status() == MemberUpgradeService.Status.UNAVAILABLE, "Proxy changed another member");
			require(menu.querySelections(owner, NetworkSelectionSession.Kind.PRODUCTS, 0) == null, "Proxy exposed products");
			for (int action = 0; action < 4; action++) require(!menu.clickMenuButton(owner, action), "Proxy changed core management");
			for (var operation : java.util.List.of(TerminalRequest.Operation.PRODUCTS, TerminalRequest.Operation.UPGRADE_INSTALL_PAGE)) {
				var reply = TerminalPayloads.handle(owner, new TerminalRequest(menu.containerId, menu.terminalSession(), operation == TerminalRequest.Operation.PRODUCTS ? 1 : 2,
						operation, page.generation(), 0, 0, 24, 1));
				require(reply != null && reply.status() == TerminalReply.Status.INVALID, "Proxy accepted a full-core command");
			}
			require(before == data.checkpoint() && neighbor == data.checkpoint().ownedMachines().get(other) && original.equals(data.checkpoint().ownedMachines().get(member).assets())
					&& owner.getInventory().getItem(24).getCount() == 2 && owner.getInventory().getItem(25).getCount() == 2 && new MachineAssetStore(target).empty(), "Proxy duplicated or lost assets");
			owner.setPos(target.getBlockPos().getX() + 10, target.getBlockPos().getY(), target.getBlockPos().getZ());
			require(!menu.stillValid(owner), "Distant proxy stayed valid"); menu.broadcastChanges(); owner.setPos(core.getBlockPos().getCenter());
			var guestId = UUID.randomUUID(); require(core.changeGuest(owner, guestId, true) == CoreAccessState.Change.CHANGED, "Proxy guest setup failed");
			var guest = player(level, guestId, "ProxyGuest"); guest.setPos(target.getBlockPos().getCenter()); var guestMenu = open(guest, target);
			require(!guestMenu.canManage() && guestMenu.exchangeUpgrade(guest, member, data.checkpoint().ownedMachines().get(member).centrifuge().revision(), Upgrade.SPEED, 24, 1,
					MemberUpgradeService.Action.INSTALL, false).status() == MemberUpgradeService.Status.UNAVAILABLE, "Read-only proxy guest changed upgrades");
			require(core.changeGuest(owner, guestId, false) == CoreAccessState.Change.CHANGED && !guestMenu.stillValid(guest), "Revoked proxy remained usable");
			guest.closeContainer(); require(!MemberUpgradeMenuAccess.open(guest, target), "Revoked guest reopened proxy");
			owner.setPos(target.getBlockPos().getCenter()); var bound = open(owner, target);
			MemberBinding.phase(target, MemberBinding.read(target), MemberBinding.Mode.LEAVING);
			MemberBinding.phase(target, MemberBinding.read(target), MemberBinding.Mode.MANAGED);
			require(!bound.stillValid(owner), "Restoring binding values resurrected a proxy"); stale = open(owner, target);
			com.ayoshiko.productivebeesgenesis.apiculture.topology.NetworkTopologyService.dirty(level, target.getBlockPos());
			require(core.topology() == null && stale.stillValid(owner), "Pending audit closed the proxy");
			before = data.checkpoint();
			require(stale.querySelections(owner, NetworkSelectionSession.Kind.UPGRADES, 0) == null
					&& stale.exchangeUpgrade(owner, member, before.ownedMachines().get(member).centrifuge().revision(), Upgrade.SPEED, 24, 1,
							MemberUpgradeService.Action.INSTALL, false).status() == MemberUpgradeService.Status.UNAVAILABLE
					&& before == data.checkpoint() && owner.getInventory().getItem(24).getCount() == 2, "Pending audit allowed assets to move");
			stale.broadcastChanges();
			phase = 1; return false;
		}
		if (core.topology() == null || !core.topology().valid()) return false;
		if (phase == 1) {
			require(stale.stillValid(owner) && stale.querySelections(owner, NetworkSelectionSession.Kind.UPGRADES, 0) != null, "Unchanged audit revoked the menu");
			core.toggleFace(Direction.EAST); phase = 2; return false;
		}
		if (phase == 2) {
			require(!stale.stillValid(owner), "Confirmed disconnection kept proxy valid"); stale.broadcastChanges();
			core.toggleFace(Direction.EAST); phase = 3; return false;
		}
		require(!stale.stillValid(owner), "Topology recovery resurrected an old menu"); open(owner, target); owner.closeContainer();
		owner = null; stale = null; phase = 4; report.addProperty("memberProxyScopedDistancePermissionsBindingAndConservation", true);
		report.addProperty("memberProxyPendingAuditBlocksExchangeWithoutClosing", true); return true;
	}
	private static ServerPlayer player(net.minecraft.server.level.ServerLevel level, UUID id, String name) {
		var player = new ServerPlayer(level.getServer(), level, new GameProfile(id, name), ClientInformation.createDefault());
		new PlayerInventorySyncProbe(player); return player;
	}
	private static NetworkCoreMenu open(ServerPlayer player, TileEntityMekCentrifuge source) {
		require(MemberUpgradeMenuAccess.open(player, source) && player.containerMenu instanceof NetworkCoreMenu, "Member proxy did not open");
		var menu = (NetworkCoreMenu) player.containerMenu; require(menu.memberScoped() && menu.stillValid(player), "Opened proxy has wrong scope"); return menu;
	}
	private CoreMemberProxyProbe() { }
}
