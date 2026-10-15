package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.port.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.domainprobe.PlayerInventorySyncProbe;
import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import static com.ayoshiko.productivebeesgenesis.multiblock.world.MachineLifecycleProbe.check;

/** 同一混合世界覆盖正式终端命令、普通供给、条件能力及拒绝路径。 */
final class CombinedManagementProbe {
	private final NetworkCoreBlockEntity core;
	private final UUID member, basic;
	private final ServerLevel level;
	private final ServerPlayer player;
	private NetworkCoreMenu menu;
	private int step, delay;
	private long sequence;
	CombinedManagementProbe(NetworkCoreBlockEntity core, UUID member, UUID basic, ServerLevel level) {
		this.core = core; this.member = member; this.basic = basic; this.level = level;
		player = new ServerPlayer(level.getServer(), level, new GameProfile(core.owner(), "CombinedManager"), ClientInformation.createDefault());
		new PlayerInventorySyncProbe(player); player.setPos(core.getBlockPos().getCenter());
	}
	boolean step() {
		if (++delay % 6 != 0 || core.topology() == null || !core.topology().valid()) return false;
		var authority = core.ownership().readyAuthority(); var root = authority.checkpoint(); var record = root.ownedMachines().get(member);
		if (step == 0) {
			check(TerminalScope.APIARY.values(root.ownedMachines()).iterator().next().claim().member().equals(member), "Combined missing from apiary scope");
			int count = 0; for (var ignored : TerminalScope.CENTRIFUGE.values(root.ownedMachines())) count++;
			check(count == 2, "Combined missing or duplicated in centrifuge scope");
			menu = (NetworkCoreMenu) core.createMenu(93, player.getInventory(), player); player.containerMenu = menu;
			check(menu != null && menu.stillValid(player), "Unified menu unavailable");
			command(TerminalRequest.Operation.BEE_DISABLE, 5);
			check(!authority.checkpoint().ownedMachines().get(member).beeRecords().get(5).enabled(), "Sixth bee not disabled");
			step++; return false;
		}
		if (step == 1) {
			command(TerminalRequest.Operation.BEE_ENABLE, 5);
			player.getInventory().items.set(0, ItemStack.EMPTY);
			var before = authority.checkpoint();
			check(menu.exchangeFeeding(player, member, 5, record.feedingRevision(), 0, 1, CoreFeedingExchange.Action.WITHDRAW, true).moved() == 1
					&& authority.checkpoint() == before && player.getInventory().items.get(0).isEmpty(), "Sixth feeding simulation changed assets");
			check(menu.exchangeFeeding(player, member, 5, record.feedingRevision(), 0, 1, CoreFeedingExchange.Action.WITHDRAW, false).moved() == 1, "Sixth feeding withdrawal failed");
			var port = (NetworkPortBlockEntity) level.getBlockEntity(core.getBlockPos().west());
			check(port.open(player), "Combined supply unavailable");
			step++; return false;
		}
		if (step == 2) {
			var port = (NetworkPortBlockEntity) level.getBlockEntity(core.getBlockPos().west());
			var portMenu = (NetworkPortMenu) player.containerMenu;
			portMenu.request(player, new PortMenuRequest(portMenu.containerId, portMenu.session(), 1, portMenu.configuration(), 2, 0, -1, 1));
			check(port.state().target().equals(member), "Supply did not address combined member");
			step++; return false;
		}
		if (step == 3) {
			var port = (NetworkPortBlockEntity) level.getBlockEntity(core.getBlockPos().west());
			var portMenu = (NetworkPortMenu) player.containerMenu;
			if (!port.state().enabled()) portMenu.request(player, new PortMenuRequest(portMenu.containerId, portMenu.session(), 2, portMenu.configuration(), 0, 0, -1, 1));
			check(port.state().enabled(), "Supply enable was rate limited");
			var held = player.getInventory().items.get(0).copy();
			var items = level.getCapability(Capabilities.ItemHandler.BLOCK, port.getBlockPos(), net.minecraft.core.Direction.UP);
			check(items != null && items.insertItem(0, held, false).isEmpty(), "Supply rejected returned sixth flower");
			player.getInventory().items.set(0, ItemStack.EMPTY); step++; return false;
		}
		if (step == 4) {
			var port = (NetworkPortBlockEntity) level.getBlockEntity(core.getBlockPos().west());
			if (record.feedingSlots().get(5).count() != 1 || !port.state().supplies().get(0).empty()) return false;
			var input = record.beeRecords().get(0).plan().output();
			var summary = core.centrifugeIndex().step(core, input, level.getGameTime()); if (summary == null) return false;
			check(summary.members() == 2 && summary.lanes() == 4, "Capacity counted ports or missed mixed lanes: " + summary);
			// 固定 PB 300 tick 配方；基础机 2 SPEED 与组合机 0 SPEED 各自贡献，不交叉叠乘。
			var policy = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry(com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(level));
			var service = new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.NetworkCentrifugeService(authority, com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.directory(level.getServer()), policy);
			var combinedPlan = com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter.compile(level, policy, input, 0, service.profile(level, member));
			var basicPlan = com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter.compile(level, policy, input, 0, service.profile(level, basic));
			double expected = 20.0 * (3.0 * combinedPlan.maxParallel() / combinedPlan.cycleTicks() + (double) basicPlan.maxParallel() / basicPlan.cycleTicks());
			check(Math.abs(Double.parseDouble(summary.operationsPerSecond()) - expected) < expected * 0.00001, "Capacity cross-multiplied sources");
			var energy = java.math.BigInteger.valueOf(combinedPlan.energyPerTick(combinedPlan.maxParallel())).multiply(java.math.BigInteger.valueOf(3)).add(java.math.BigInteger.valueOf(basicPlan.energyPerTick(basicPlan.maxParallel())));
			check(summary.fullLoadEnergy().equals(energy.toString()), "Capacity FE duplicated");
			var stranger = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "CombinedStranger"), ClientInformation.createDefault());
			new PlayerInventorySyncProbe(stranger); stranger.setPos(core.getBlockPos().getCenter());
			check(!menu.stillValid(stranger) && !port.open(stranger), "Stranger reached managed assets");
			player.closeContainer(); return true;
		}
		return false;
	}
	private void command(TerminalRequest.Operation action, int slot) {
		var page = menu.querySelections(player, NetworkSelectionSession.Kind.MEMBERS, 0);
		int row = 0; while (!((NetworkSelectionSession.MemberRow) page.rows().get(row)).claim().member().equals(member)) row++;
		check(((NetworkSelectionSession.MemberRow) page.rows().get(row)).bees().size() == 6, "Unified menu truncated six bees");
		var request = new TerminalRequest(menu.containerId, menu.terminalSession(), ++sequence, action, page.generation(), row, slot, -1, 0);
		var reply = menu.terminalRequest(player, request);
		check(reply != null && reply.status() == TerminalReply.Status.OK, "Unified bee command failed: " + reply);
		var root = core.ownership().readyAuthority().checkpoint();
		check(menu.terminalRequest(player, request) == null && core.ownership().readyAuthority().checkpoint() == root, "Replayed terminal command mutated assets");
	}
}
