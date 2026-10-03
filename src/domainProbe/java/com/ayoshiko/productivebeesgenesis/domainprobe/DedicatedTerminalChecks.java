package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 在现有真实客户端夹具中核对服务端连接失效；不模拟生产或修改存档。 */
final class DedicatedTerminalChecks {
	static final BlockPos BEE = new BlockPos(8, 100, 7), CENTRIFUGE = new BlockPos(8, 100, 9);
	static void verify(NetworkCoreBlockEntity core, ServerPlayer player) {
		var level = player.serverLevel(); var data = core.ownership().readyAuthority();
		require(data != null, "Dedicated terminal authority missing");
		var before = data.checkpoint(); var position = player.position();
		level.setBlockAndUpdate(BEE, NetworkContent.BEE_TERMINAL.get().defaultBlockState());
		level.setBlockAndUpdate(CENTRIFUGE, NetworkContent.CENTRIFUGE_TERMINAL.get().defaultBlockState());
		var bee = (NetworkTerminalBlockEntity) level.getBlockEntity(BEE);
		var centrifuge = (NetworkTerminalBlockEntity) level.getBlockEntity(CENTRIFUGE);
		var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "TerminalStranger"));
		stranger.setPos(position);
		require(!NetworkTerminalAccess.open(stranger, bee), "Unauthorized terminal opened");
		for (var terminal : new NetworkTerminalBlockEntity[]{bee, centrifuge}) {
			var menu = open(player, terminal);
			require(!menu.canManage() && !menu.clickMenuButton(player, 1), "Terminal exposed takeover");
			var page = menu.querySelections(player, NetworkSelectionSession.Kind.UPGRADES, 0);
			require(page != null && page.rows().size() == 1, "Typed terminal page incorrect");
			for (var row : page.rows()) require(menu.scope().accepts(((NetworkSelectionSession.MemberRow) row).claim().machine()), "Cross-type member exposed");
			var other = before.ownedMachines().values().stream().filter(record -> !menu.scope().accepts(record.claim().machine())).findFirst().orElseThrow();
			require(menu.exchangeUpgrade(player, other.claim().member(), 0, mekanism.api.Upgrade.SPEED, 6, 1,
					MemberUpgradeService.Action.INSTALL, false).status() == MemberUpgradeService.Status.UNAVAILABLE, "Cross-type direct exchange accepted");
			require(!menu.stillValid(stranger), "Different viewer reused terminal");
			player.closeContainer();
		}
		player.teleportTo(8.5, 100.5, 0);
		require(player.distanceToSqr(core.getBlockPos().getCenter()) > 64, "Distance fixture not outside core range");
		var remote = open(player, bee); require(remote.stillValid(player), "Terminal incorrectly uses core distance");
		player.closeContainer(); player.teleportTo(position.x, position.y, position.z);
		var conflict = open(player, bee);
		level.setBlockAndUpdate(BEE.north(), NetworkContent.CORE.get().defaultBlockState());
		require(!conflict.stillValid(player), "Second adjacent core did not revoke terminal");
		level.setBlockAndUpdate(BEE.north(), Blocks.AIR.defaultBlockState());
		require(!conflict.stillValid(player), "Old terminal revived after conflict"); player.closeContainer();
		var replaced = open(player, bee);
		level.setBlockAndUpdate(BEE, Blocks.AIR.defaultBlockState());
		level.setBlockAndUpdate(BEE, NetworkContent.BEE_TERMINAL.get().defaultBlockState());
		require(!replaced.stillValid(player), "Replacement revived old terminal"); player.closeContainer();
		bee = (NetworkTerminalBlockEntity) level.getBlockEntity(BEE);
		var permission = open(player, bee); var guest = UUID.randomUUID();
		require(core.changeGuest(player, guest, true) == CoreAccessState.Change.CHANGED, "Permission fixture grant failed");
		require(!permission.stillValid(player), "Permission change retained old terminal");
		core.changeGuest(player, guest, false); require(!permission.stillValid(player), "Permission restoration revived menu");
		player.closeContainer(); require(data.checkpoint() == before, "Rejected terminal checks changed network assets");
		core.openTerminal(player);
	}
	private static NetworkCoreMenu open(ServerPlayer player, NetworkTerminalBlockEntity terminal) {
		require(NetworkTerminalAccess.open(player, terminal), "Dedicated terminal failed to open");
		var menu = (NetworkCoreMenu) player.containerMenu;
		require(menu.scope() == terminal.scope(), "Registered menu scope differs"); return menu;
	}
	private DedicatedTerminalChecks() { }
}
