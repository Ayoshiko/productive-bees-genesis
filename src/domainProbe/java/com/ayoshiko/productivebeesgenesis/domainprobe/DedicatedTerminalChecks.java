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
		var names = TerminalSearchNames.INSTANCE;
		require(names.text("bee", "productivebees:iron").contains("铁蜜蜂"), "PB Chinese search dictionary missing: " + names.text("bee", "productivebees:iron"));
		require(names.text("item", "minecraft:iron_ingot").contains("iron ingot"),
				"Vanilla English search dictionary missing: " + names.text("item", "minecraft:iron_ingot"));
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
	private static int detailStep;
	private static long nextDetailTick;
	private static NetworkCoreMenu detailMenu;
	private static TerminalReply detailReply;
	private static TerminalRequest disabledRequest;
	private static int detailSlot;
	private static UUID detailMember;
	private static com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingSlotStore.Slot detailFood;
	private static net.minecraft.world.item.ItemStack originalInventory;
	static boolean advanceDetails(NetworkCoreBlockEntity core, ServerPlayer player) {
		if (player.serverLevel().getGameTime() < nextDetailTick) return false;
		nextDetailTick = player.serverLevel().getGameTime() + 5;
		var authority = core.ownership().readyAuthority();
		if (detailStep == 0) {
			var initial = authority.checkpoint().ownedMachines().activeValues(TerminalScope.APIARY.machine()).iterator().next();
			if (com.ayoshiko.productivebeesgenesis.apiculture.ownership.ManagedProductionAccess.member(player.serverLevel(), authority,
					com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.directory(player.server), initial,
					com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary.class) == null) return false;
			detailMenu = open(player, (NetworkTerminalBlockEntity) player.serverLevel().getBlockEntity(BEE));
			require(initial.bees().feeding().slots().getFirst().count() == 0, "Flower fixture must start empty");
			originalInventory = player.getInventory().getItem(8).copy();
			player.getInventory().setItem(8, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_BLOCK));
			var deposited = detailMenu.exchangeFeeding(player, initial.claim().member(), 0, initial.bees().feeding().revision(), 8, 1,
					CoreFeedingExchange.Action.DEPOSIT, false);
			require(deposited.moved() == 1, "Flower fixture deposit failed: " + deposited);
			var fed = authority.checkpoint().ownedMachines().get(initial.claim().member());
			require(detailMenu.exchangeBee(player, fed.claim().member(), 0, fed.bees().revision(), null, 1,
					CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Bee display fixture insertion failed");
			detailReply = TerminalPayloads.handle(player, new TerminalRequest(detailMenu.containerId, detailMenu.terminalSession(), 1,
					TerminalRequest.Operation.MEMBERS, 0, -1, 0, 0, 0));
			require(detailReply != null && detailReply.view() != null && detailReply.view().rows().size() == 1, "Detailed terminal page missing");
			var row = detailReply.view().rows().getFirst();
			require(row.location() != null && row.location().x() == 9 && row.location().dimension().equals("minecraft:overworld"), "Structured location mismatch");
			detailSlot = row.bees().stream().filter(value -> value.feedingCount() > 0).findFirst().orElseThrow().slot();
			var record = authority.checkpoint().ownedMachines().activeValues(TerminalScope.APIARY.machine()).iterator().next();
			detailMember = record.claim().member(); detailFood = record.bees().feeding().slots().get(detailSlot);
		} else if (detailStep == 1) {
			disabledRequest = new TerminalRequest(detailMenu.containerId, detailMenu.terminalSession(), 2, TerminalRequest.Operation.FEED_DISABLE,
					detailReply.view().generation(), 0, detailSlot, 0, 0);
			var reply = TerminalPayloads.handle(player, disabledRequest);
			require(reply != null && reply.status() == TerminalReply.Status.OK, "Feeding disable failed");
			var updated = authority.checkpoint().ownedMachines().get(detailMember).bees().feeding().slots().get(detailSlot);
			require(updated.disabled() && updated.item().equals(detailFood.item()) && updated.count() == detailFood.count(), "Feeding control changed assets");
		} else if (detailStep == 2) {
			require(TerminalPayloads.handle(player, disabledRequest) == null, "Feeding control replay accepted");
			detailReply = TerminalPayloads.handle(player, new TerminalRequest(detailMenu.containerId, detailMenu.terminalSession(), 3,
					TerminalRequest.Operation.MEMBERS, 0, -1, 0, 0, 0));
			require(detailReply != null && detailReply.view().rows().getFirst().bees().get(detailSlot).feedingDisabled(), "Disabled state not projected");
		} else if (detailStep == 3) {
			var reply = TerminalPayloads.handle(player, new TerminalRequest(detailMenu.containerId, detailMenu.terminalSession(), 4, TerminalRequest.Operation.FEED_ENABLE,
					detailReply.view().generation(), 0, detailSlot, 0, 0));
			require(reply != null && reply.status() == TerminalReply.Status.OK, "Feeding enable failed");
			var updated = authority.checkpoint().ownedMachines().get(detailMember).bees().feeding().slots().get(detailSlot);
			require(!updated.disabled() && updated.item().equals(detailFood.item()) && updated.count() == detailFood.count(), "Feeding restore lost assets");
		} else if (detailStep == 4) {
			var search = detailMenu.terminalSearch(player, new TerminalSearchRequest(detailMenu.containerId, detailMenu.terminalSession(), 5, NetworkSelectionSession.Kind.MEMBERS, "definitely_missing"));
			require(search != null && search.status() == TerminalReply.Status.OK && search.view() == null, "Server search did not acknowledge subscription");
		} else {
			var page = detailMenu.terminalSelectionPage(); if (page == null) return false;
			require(page.rows().isEmpty() && !page.hasNext(), "Server subscription ignored filter");
			player.closeContainer(); core.openTerminal(player); detailMenu = null; detailReply = null; detailFood = null; disabledRequest = null; return true;
		}
		detailStep++; return false;
	}
	static void cleanupDetails(NetworkCoreBlockEntity core, ServerPlayer player) {
		require(player.containerMenu instanceof NetworkCoreMenu, "Flower cleanup menu missing");
		var menu = (NetworkCoreMenu) player.containerMenu;
		var record = core.ownership().readyAuthority().checkpoint().ownedMachines().get(detailMember);
		require(menu.exchangeBee(player, detailMember, 0, record.bees().revision(), record.bees().bee(0).id(), 1,
				CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "Bee display fixture extraction failed");
		record = core.ownership().readyAuthority().checkpoint().ownedMachines().get(detailMember);
		require(player.getInventory().getItem(8).isEmpty(), "Flower fixture receiver changed");
		require(menu.exchangeFeeding(player, detailMember, detailSlot, record.bees().feeding().revision(), 8, 1,
				CoreFeedingExchange.Action.WITHDRAW, false).moved() == 1, "Flower fixture withdrawal failed");
		require(player.getInventory().getItem(8).is(net.minecraft.world.item.Items.IRON_BLOCK) && player.getInventory().getItem(8).getCount() == 1,
				"Flower fixture duplicated or changed item");
		player.getInventory().setItem(8, originalInventory); originalInventory = null;
		player.getInventory().setChanged(); player.inventoryMenu.broadcastChanges(); player.containerMenu.broadcastChanges();
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
