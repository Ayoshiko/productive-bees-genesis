package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.api.Upgrade;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 复用真实双玩家屏障；升级资产独立核算，权限命令由实际所有者客户端发出。 */
final class UpgradeCompetitionProbe {
	static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.upgrades"); }
	private static Map<ProductKey, Long> total;
	private static int exchanges;
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		require(core.upgradeGuests(players.getFirst()).isEmpty(), "Old guest unexpectedly has upgrade permission");
		for (var player : players) {
			player.getInventory().setItem(20, UpgradeUtils.getStack(Upgrade.SPEED, 8));
			player.getInventory().setItem(21, PbUpgradeInventorySlot.getRepresentativeStack(PbUpgradeType.TIME).copyWithCount(2));
			player.getInventory().setItem(22, UpgradeUtils.getStack(Upgrade.SPEED, 63));
			player.getInventory().setChanged(); player.containerMenu.broadcastChanges();
		}

		var data = core.ownership().readyAuthority();
		var centrifuge = data.checkpoint().ownedMachines().values().stream().filter(r -> r.bees() == null).findFirst().orElseThrow();
		var comb = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
		comb.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), net.minecraft.resources.ResourceLocation.parse("productivebees:iron"));
		if (centrifuge.centrifuge() == null) {
			var policy = new ProductPolicyRegistry(PbProductPolicyCompiler.compile(players.getFirst().serverLevel(), data.checkpoint().policyRevision()).snapshot());
			require(new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.NetworkCentrifugeService(data, NetworkPersistence.directory(players.getFirst().server), policy)
					.activate(players.getFirst().serverLevel(), centrifuge.claim().member(), data.checkpoint().revision(), ProductKeyCodec.item(comb, players.getFirst().registryAccess())), "Upgrade centrifuge activation failed");
		}
		UpgradeCompetitionRecovery.seed(core, players, ProductKeyCodec.item(comb, players.getFirst().registryAccess()));
		total = totals(core, players);
	}
	static boolean ready(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
		var guest = players.get(1);
		if (stage == 101 || stage == 113) return core.upgradeGuests(players.getFirst()).equals(List.of(guest.getUUID()));
		if (stage == 111) return core.upgradeGuests(players.getFirst()).isEmpty();
		return true;
	}
	static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage,
			Map<UUID, CompetitionSignal> replies, NetworkCheckpoint before, Map<UUID, net.minecraft.nbt.ListTag> inventories) {
		var current = core.ownership().readyAuthority().checkpoint();
		require(total.equals(totals(core, players)), "Two-player upgrades lost or duplicated items at " + stage);
		require(current.ledger().equals(before.ledger()) && current.energy().equals(before.energy())
				&& current.scheduler().equals(before.scheduler()) && current.transfers().equals(before.transfers()),
				"Upgrade request changed paid work or shared assets");
		for (var record : before.ownedMachines().activeValues()) {
			var after = current.ownedMachines().get(record.claim().member());
			if (record.bees() != null) require(record.bees().bees().equals(after.bees().bees())
					&& record.bees().feeding().equals(after.bees().feeding()), "Upgrade changed existing bees");
			if (record.centrifuge() != null) require(record.centrifuge().jobs().equals(after.centrifuge().jobs()), "Upgrade changed existing jobs");
		}
		int expected = switch (stage) { case 103, 106, 122 -> 1; case 109, 116, 118, 124 -> 2; default -> 0; };
		int moved = replies.values().stream().mapToInt(CompetitionSignal::moved).sum();
		require(moved == expected, "Upgrade replies differ at " + stage + ": " + replies);
		if (expected > 0) {
			exchanges += moved;
			if (expected == 1) require(replies.values().stream().filter(r -> r.status() == TerminalReply.Status.STALE.ordinal()).count() == 1,
					"Concurrent old selection did not become stale");
		} else require(current == before && inventories.equals(CompetitionAssets.inventories(players)), "Rejected/query upgrade changed assets");
		if (stage == 120) require(replies.values().stream().allMatch(r -> r.status() == TerminalReply.Status.NO_SPACE.ordinal()), "Full slot did not refuse upgrades");
		var owner = players.getFirst(); var guest = players.get(1);
		require(core.allowed(guest) && !core.ownerAllowed(guest), "Upgrade permission changed guest access or owner role");
		if (stage == 101) {
			require(!(guest.containerMenu instanceof NetworkCoreMenu old) || !old.stillValid(guest), "Grant retained old menu");
			open(core, players, false);
		}
		if (stage == 114) open(core, players, true);
		return stage == 124 ? -1 : stage + 1;
	}
	private static void open(NetworkCoreBlockEntity core, List<ServerPlayer> players, boolean proxy) {
		core.openTerminal(players.getFirst());
		var guest = players.get(1);
		if (!proxy) core.openTerminal(guest);
		else require(MemberUpgradeMenuAccess.open(guest, guest.serverLevel().getBlockEntity(core.getBlockPos().east(2))), "Granted guest could not open member proxy");
	}
	static void report(JsonObject report) {
		report.addProperty("upgradeAuthorizationCompetitionProxyAndConservation", true);
		report.addProperty("upgradePlayerTransfers", exchanges);
	}
	private static Map<ProductKey, Long> totals(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		var result = new HashMap<ProductKey, Long>(); var registry = players.getFirst().registryAccess();
		for (var player : players) for (var stack : player.getInventory().items)
			if (!stack.isEmpty()) result.merge(ProductKeyCodec.item(stack, registry), (long) stack.getCount(), Long::sum);
		for (var record : core.ownership().readyAuthority().checkpoint().ownedMachines().activeValues()) {
			var image = record.assets().copy();
			NativeUpgradeCounts.read(image.getCompound("upgrades")).forEach((type, count) ->
					result.merge(ProductKeyCodec.item(UpgradeUtils.getStack(type, 1), registry), (long) count, Long::sum));
			var pb = record.bees() != null ? PbApiaryUpgradeCounts.read(image.getCompound("extra")) : PbCentrifugeUpgradeCounts.read(image.getCompound("extra"));
			pb.forEach((type, count) -> result.merge(ProductKeyCodec.item(PbUpgradeInventorySlot.getRepresentativeStack(type), registry), (long) count, Long::sum));
		}
		result.values().removeIf(count -> count == 0); return Map.copyOf(result);
	}
	private UpgradeCompetitionProbe() {}
}
