package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.port.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import mekanism.api.Upgrade;
import net.minecraft.core.Direction;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.AutomaticRestartFixture.require;

/** 复用读档后的正式成员，覆盖末槽菜单、供给、升级、权限和安全交还。 */
final class FactoryApiaryManagementProbe {
	private final ServerLevel level;
	private final AutomaticRestartFixture fixture;
	private final ServerPlayer player;
	private final UUID member;
	private final int slot;
	private final NetworkPortBlockEntity port;
	private NetworkCoreMenu menu;
	private NetworkPortMenu portMenu;
	private int stage, delay;
	private long sequence;
	FactoryApiaryManagementProbe(ServerLevel level, AutomaticRestartFixture fixture) {
		this.level = level; this.fixture = fixture;
		var record = fixture.data.checkpoint().ownedMachines().values().stream().filter(r -> r.bees() != null).findFirst().orElseThrow();
		member = record.claim().member(); slot = record.bees().slotCount() - 1;
		player = new ServerPlayer(level.getServer(), level, new GameProfile(fixture.core.owner(), "FactoryManager"), ClientInformation.createDefault());
		new PlayerInventorySyncProbe(player); player.setPos(fixture.pos.getCenter());
		port = (NetworkPortBlockEntity) level.getBlockEntity(fixture.pos.north());
		open();
	}
	private void open() {
		menu = (NetworkCoreMenu) fixture.core.createMenu(93, player.getInventory(), player); player.containerMenu = menu;
		require(menu != null && menu.stillValid(player), "Factory menu unavailable");
	}
	private OwnedMachineRecord record() { return fixture.data.checkpoint().ownedMachines().get(member); }
	boolean step() {
		if (++delay % 6 != 0 || fixture.core.topology() == null || !fixture.core.topology().valid()) return false;
		var record = record();
		switch (stage) {
			case 0 -> {
				command(TerminalRequest.Operation.BEE_DISABLE);
				require(!record().bees().bee(slot).enabled(), "Factory last slot disable failed");
			}
			case 1 -> {
				command(TerminalRequest.Operation.BEE_ENABLE);
				var before = fixture.data.checkpoint();
				require(menu.exchangeFeeding(player, member, slot, record().feedingRevision(), 0, 1, CoreFeedingExchange.Action.WITHDRAW, true).moved() == 1
						&& fixture.data.checkpoint() == before && player.getInventory().items.get(0).isEmpty(), "Factory feed simulation mutated assets");
				require(menu.exchangeFeeding(player, member, slot, record().feedingRevision(), 0, 1, CoreFeedingExchange.Action.WITHDRAW, false).moved() == 1, "Factory feed withdrawal failed");
				require(port.open(player), "Factory supply menu unavailable"); portMenu = (NetworkPortMenu) player.containerMenu;
			}
			case 2 -> {
				portMenu.request(player, new PortMenuRequest(portMenu.containerId, portMenu.session(), 1, portMenu.configuration(), 2, 0, -1, 1));
				require(member.equals(port.state().target()), "Factory missing from supply targets");
			}
			case 3 -> {
				portMenu.request(player, new PortMenuRequest(portMenu.containerId, portMenu.session(), 2, portMenu.configuration(), 0, 0, -1, 1));
				require(port.state().enabled(), "Factory supply disabled");
				var items = level.getCapability(Capabilities.ItemHandler.BLOCK, port.getBlockPos(), Direction.UP);
				var held = player.getInventory().items.get(0).copy();
				require(items != null && items.insertItem(0, held, true).isEmpty() && port.state().supplies().get(0).empty(), "Factory supply simulation changed state");
				require(items.insertItem(0, held, false).isEmpty(), "Factory supply rejected flower");
				player.getInventory().items.set(0, ItemStack.EMPTY);
			}
			case 4 -> {
				if (record.feedingSlots().get(slot).count() != 1 || !port.state().supplies().get(0).empty()) return false;
				open(); player.getInventory().items.set(1, new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()));
				require(menu.exchangeBee(player, member, slot, record.beeRevision(), record.bees().bee(slot).id(), 1, CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "Factory last slot caging failed");
			}
			case 5 -> {
				require(menu.exchangeBee(player, member, slot, record.beeRevision(), null, 1, CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Factory last slot cage insertion failed");
			}
			case 6 -> {
				player.getInventory().items.set(2, new ItemStack(mekanism.common.registries.MekanismItems.SPEED_UPGRADE.get()));
				require(menu.exchangeUpgrade(player, member, record.beeRevision(), Upgrade.SPEED, 2, 1, MemberUpgradeService.Action.INSTALL, false).moved() == 1, "Factory speed install failed");
			}
			case 7 -> {
				require(menu.exchangeUpgrade(player, member, record.beeRevision(), Upgrade.SPEED, 2, 1, MemberUpgradeService.Action.REMOVE, false).moved() == 1
						&& player.getInventory().items.get(2).getCount() == 1, "Factory speed removal lost upgrade");
				var stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "FactoryStranger"), ClientInformation.createDefault());
				new PlayerInventorySyncProbe(stranger); stranger.setPos(fixture.pos.getCenter());
				require(!menu.stillValid(stranger) && !port.open(stranger), "Factory assets exposed to stranger");
				player.closeContainer(); fixture.redstone(mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl.HIGH);
				require(fixture.core.ownership().command(false), "Factory return refused");
			}
			case 8 -> {
				if (fixture.core.ownership().status() != CoreOwnershipController.Status.STANDALONE) return false;
				require(!fixture.hive.getBeeSlot(slot).isEmpty() && fixture.hive.getFeederSlots().get(slot).getCount() == 1
						&& fixture.hive.energyContainer().getEnergy() == 0 && !OwnershipEndpoints.bound(fixture.hive), "Factory return lost bee/food or duplicated FE");
				return true;
			}
			default -> throw new IllegalStateException("Unknown factory management stage");
		}
		stage++; return false;
	}
	private void command(TerminalRequest.Operation operation) {
		var page = menu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
		int row = 0; while (!((NetworkSelectionSession.MemberRow) page.rows().get(row)).claim().member().equals(member)) row++;
		require(((NetworkSelectionSession.MemberRow) page.rows().get(row)).bees().size() == slot + 1, "Factory terminal truncated slots");
		var request = new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence, operation, page.generation(), row, slot, -1, 0);
		var result = menu.terminalRequest(player, request);
		require(result != null && result.status() == TerminalReply.Status.OK, "Factory last slot command failed: " + result);
		var before = fixture.data.checkpoint();
		require(menu.terminalRequest(player, request) == null && fixture.data.checkpoint() == before, "Factory command replay mutated assets");
	}
}
