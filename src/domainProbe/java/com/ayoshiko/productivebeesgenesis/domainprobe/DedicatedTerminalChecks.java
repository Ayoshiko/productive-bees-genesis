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
	static final BlockPos BEE = new BlockPos(8, 100, 7), CENTRIFUGE = new BlockPos(8, 100, 9), COMBINED = new BlockPos(7, 100, 8);
	static void verify(NetworkCoreBlockEntity core, ServerPlayer player) {
		var level = player.serverLevel(); var data = core.ownership().readyAuthority();
		require(data != null, "Dedicated terminal authority missing");
		var before = data.checkpoint(); var position = player.position();
		level.setBlockAndUpdate(BEE, NetworkContent.BEE_TERMINAL.get().defaultBlockState());
		level.setBlockAndUpdate(CENTRIFUGE, NetworkContent.CENTRIFUGE_TERMINAL.get().defaultBlockState());
		level.setBlockAndUpdate(COMBINED, NetworkContent.COMBINED_TERMINAL.get().defaultBlockState());
		var bee = (NetworkTerminalBlockEntity) level.getBlockEntity(BEE);
		var centrifuge = (NetworkTerminalBlockEntity) level.getBlockEntity(CENTRIFUGE);
		var stranger = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "TerminalStranger"));
		stranger.setPos(position);
		require(!NetworkTerminalAccess.open(stranger, bee), "Unauthorized terminal opened");
		for (var terminal : new NetworkTerminalBlockEntity[]{bee, centrifuge}) {
			var menu = open(player, terminal);
			require(!menu.clickMenuButton(player, 10) && !menu.clickMenuButton(player, 11), "Single terminal switched type");
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
		player.closeContainer(); verifyCombined(core, player);
		require(data.checkpoint() == before, "Rejected terminal checks changed network assets");
		core.openTerminal(player);
	}
	private static void verifyCombined(NetworkCoreBlockEntity core, ServerPlayer player) {
		var terminal = (NetworkTerminalBlockEntity) player.serverLevel().getBlockEntity(COMBINED);
		var first = open(player, terminal);
		require(first.combinedTerminal(), "Combined terminal identity missing");
		var firstPage = first.querySelections(player, NetworkSelectionSession.Kind.UPGRADES, 0);
		require(firstPage.rows().size() == 1 && first.scope() == TerminalScope.APIARY, "Combined terminal initial mode");
		require(first.clickMenuButton(player, 11), "Combined terminal cannot switch to centrifuges");
		var second = (NetworkCoreMenu) player.containerMenu;
		require(second != first && second.combinedTerminal() && second.scope() == TerminalScope.CENTRIFUGE
				&& !second.terminalSession().equals(first.terminalSession()) && !first.stillValid(player), "Mode switch reused session");
		require(first.selectedRow(player, first.terminalSession(), firstPage.generation(), 0) == null, "Old mode retained selection");
		var secondPage = second.querySelections(player, NetworkSelectionSession.Kind.UPGRADES, 0);
		require(secondPage.rows().size() == 1 && ((NetworkSelectionSession.MemberRow) secondPage.rows().getFirst()).claim().machine().equals(TerminalScope.CENTRIFUGE.machine()), "Combined mode crossed member types");
		var replay = new TerminalRequest(second.containerId, first.terminalSession(), 1, TerminalRequest.Operation.UPGRADE_INSTALL,
				firstPage.generation(), 0, 0, 6, 1);
		require(second.terminalRequest(player, replay) == null, "Previous mode packet accepted");
		require(second.clickMenuButton(player, 10) && !second.stillValid(player), "Combined terminal did not switch back");
		var last = (NetworkCoreMenu) player.containerMenu;
		require(last.scope() == TerminalScope.APIARY && !last.clickMenuButton(player, 1), "Combined terminal gained owner controls");
		player.closeContainer(); verifyRecipe(player);
	}
	private static void verifyRecipe(ServerPlayer player) {
		var bee = NetworkContent.BEE_TERMINAL_ITEM.get().getDefaultInstance(); bee.setCount(3);
		var centrifuge = NetworkContent.CENTRIFUGE_TERMINAL_ITEM.get().getDefaultInstance(); centrifuge.setCount(2);
		var input = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, java.util.List.of(bee, centrifuge));
		var holder = player.serverLevel().getRecipeManager().getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, input, player.serverLevel()).orElseThrow();
		require(holder.value() instanceof CombinedTerminalRecipe, "Combined recipe not registered");
		var recipe = holder.value(); var result = recipe.assemble(input, player.registryAccess());
		require(result.is(NetworkContent.COMBINED_TERMINAL_ITEM.get()) && result.getCount() == 1 && bee.getCount() == 3 && centrifuge.getCount() == 2, "Recipe preview mutated inputs");
		var reversed = net.minecraft.world.item.crafting.CraftingInput.of(1, 2, java.util.List.of(centrifuge, bee));
		require(recipe.matches(reversed, player.level()), "Combined recipe not shapeless");
		bee.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("keep my name"));
		require(!recipe.matches(input, player.level()) && recipe.assemble(input, player.registryAccess()).isEmpty(), "Combined recipe discarded components");
		var duplicate = net.minecraft.world.item.crafting.CraftingInput.of(2, 1, java.util.List.of(centrifuge, centrifuge.copy()));
		require(!recipe.matches(duplicate, player.level()), "Duplicate terminal recipe accepted");
		var extra = net.minecraft.world.item.crafting.CraftingInput.of(3, 1, java.util.List.of(NetworkContent.BEE_TERMINAL_ITEM.get().getDefaultInstance(), centrifuge, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE)));
		require(!recipe.matches(extra, player.level()), "Recipe swallowed extra item");
	}
	private static NetworkCoreMenu open(ServerPlayer player, NetworkTerminalBlockEntity terminal) {
		require(NetworkTerminalAccess.open(player, terminal), "Dedicated terminal failed to open");
		var menu = (NetworkCoreMenu) player.containerMenu;
		require(menu.scope() == terminal.scope(), "Registered menu scope differs"); return menu;
	}
	private DedicatedTerminalChecks() { }
}
