package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.*;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyService;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.production.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.google.gson.JsonObject;
import java.util.*;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 在真实玩家交换之间保留两个已付费旧工作段，新 JVM 恢复后只结算一次。 */
final class UpgradeCompetitionRecovery {
	private static boolean prepared, restored;
	static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players, ProductKey input) {
		var owner = players.getFirst(); var level = owner.serverLevel(); var data = core.ownership().readyAuthority();
		var directory = NetworkPersistence.directory(owner.server);
		var hive = data.checkpoint().ownedMachines().values().stream().filter(r -> r.bees() != null).findFirst().orElseThrow();
		var centrifuge = data.checkpoint().ownedMachines().values().stream().filter(r -> r.centrifuge() != null).findFirst().orElseThrow();
		core.openTerminal(owner); var menu = (NetworkCoreMenu) owner.containerMenu;
		require(menu.exchangeFeeding(owner, hive.claim().member(), 0, hive.bees().feeding().revision(), 0, 1, CoreFeedingExchange.Action.DEPOSIT, false).moved() == 1, "Cannot return real food for old work");
		var carrier = players.stream().filter(p -> p.getInventory().getItem(1).has(DataComponents.CUSTOM_DATA)).findFirst().orElseThrow();
		core.openTerminal(carrier); menu = (NetworkCoreMenu) carrier.containerMenu;
		hive = data.checkpoint().ownedMachines().get(hive.claim().member());
		require(menu.exchangeBee(carrier, hive.claim().member(), 0, hive.bees().revision(), null, 1, CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Cannot return real bee for old work");
		for (var id : List.of(hive.claim().member(), centrifuge.claim().member())) {
			var record = data.checkpoint().ownedMachines().get(id);
			boolean powered = record.bees() != null ? record.bees().networkPowered() : record.centrifuge().networkPowered();
			if (!powered) require(NetworkEnergyService.migrate(level, data, directory, id, data.checkpoint().revision(), false), "Old work energy migration failed");
		}
		require(core.energyPort().receiveEnergy(1_000_000, false) > 0, "Cannot supply old work energy");
		level.setDayTime(1000); level.setWeatherParameters(6000, 0, false, false);
		((TileEntityMekApiary) level.getBlockEntity(core.getBlockPos().east())).setControlType(RedstoneControl.DISABLED);
		((com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge) level.getBlockEntity(core.getBlockPos().east(2))).setControlType(RedstoneControl.DISABLED);
		var stock = new HashMap<>(data.checkpoint().ledger().balances()); stock.put(input, ProductAmount.of(1)); ClientTerminalStockFixture.seed(data, stock);
		var policy = policy(core, owner);
		var service = new NetworkCentrifugeService(data, directory, policy);
		var candidate = service.candidate(level, centrifuge.claim().member(), input);
		require(candidate != null, "Missing old centrifuge candidate");
		var selection = CentrifugeLaneAllocator.select(data.checkpoint(), policy, List.of(candidate), 0, 1, 1).selection();
		require(service.assign(level, selection, 1729, false), "Old centrifuge assignment failed");
		require(service.work(level, centrifuge.claim().member(), data.checkpoint().ownedMachines().get(centrifuge.claim().member()).centrifuge().revision(),
				NetworkCentrifugeService.Action.ADVANCE, 1, false), "Cannot pay one old centrifuge tick");
		var bees = new NetworkBeeService(data, directory); var bee = data.checkpoint().ownedMachines().get(hive.claim().member()).bees().bee(0);
		require(bees.advance(level, hive.claim().member(), 0, bee.revision(), bee.plan().recipeRevision(), bee.plan().capabilityRevision(), 1, 0, false)
				== BeeWorkExecutor.Status.READY, "Cannot pay one old bee tick");
		require(data.checkpoint().ownedMachines().get(hive.claim().member()).bees().bee(0).progress() == 1
				&& data.checkpoint().ownedMachines().get(centrifuge.claim().member()).centrifuge().jobs().get(0).progress() == 1, "Old partial work not preserved");
		prepared = true;
	}
	static void resume(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
		var owner = players.getFirst(); var data = core.ownership().readyAuthority(); var directory = NetworkPersistence.directory(owner.server); var level = owner.serverLevel();
		require(core.upgradeGuests(owner).equals(List.of(players.get(1).getUUID())), "Upgrade grant did not restore");
		var beforeInventory = CompetitionAssets.inventories(players);
		var hive = data.checkpoint().ownedMachines().values().stream().filter(r -> r.bees() != null).findFirst().orElseThrow();
		var centrifuge = data.checkpoint().ownedMachines().values().stream().filter(r -> r.centrifuge() != null).findFirst().orElseThrow();
		var bee = hive.bees().bee(0); var job = centrifuge.centrifuge().jobs().get(0);
		require(bee.progress() == 1 && job.progress() == 1 && bee.plan().productionMultiplier() == 1,
				"Restored upgrade fixture lost old partial work");
		require(NativeUpgradeCounts.read(hive.assets().copy().getCompound("upgrades")).getOrDefault(mekanism.api.Upgrade.SPEED, 0) > 0
				&& NativeUpgradeCounts.read(centrifuge.assets().copy().getCompound("upgrades")).getOrDefault(mekanism.api.Upgrade.SPEED, 0) > 0,
				"Restored members lost new installed upgrades");
		long energy = data.checkpoint().energy().stored();
		var expected = new HashMap<>(data.checkpoint().ledger().balances());
		var bees = new NetworkBeeService(data, directory); level.setDayTime(1000); level.setWeatherParameters(6000, 0, false, false);
		int remaining = bee.plan().cycleTicks() - bee.progress();
		require(bees.advance(level, hive.claim().member(), 0, bee.revision(), bee.plan().recipeRevision(), bee.plan().capabilityRevision(), remaining, 1, false)
				== BeeWorkExecutor.Status.READY, "Restored bee did not finish old cycle");
		var frozenBee = data.checkpoint().ownedMachines().get(hive.claim().member()).bees().bee(0);
		require(frozenBee.plan().equals(bee.plan()) && frozenBee.frozen().equals(ProductAmount.of(bee.plan().countPerRoll())), "Bee switched old output plan");
		expected.merge(bee.plan().output(), frozenBee.frozen(), ProductAmount::add);
		require(bees.settle(level, hive.claim().member(), 0, frozenBee.revision()), "Cannot settle restored bee");
		var settledBee = data.checkpoint();
		require(!bees.settle(level, hive.claim().member(), 0, frozenBee.revision()) && data.checkpoint() == settledBee, "Restored bee settled twice");
		var service = new NetworkCentrifugeService(data, directory, policy(core, owner));
		require(service.work(level, centrifuge.claim().member(), centrifuge.centrifuge().revision(), NetworkCentrifugeService.Action.ADVANCE, Integer.MAX_VALUE, false), "Restored job did not finish");
		var state = data.checkpoint().ownedMachines().get(centrifuge.claim().member()).centrifuge();
		require(state.jobs().get(0).plan().equals(job.plan()) && state.jobs().get(0).seed() == job.seed(), "Restored job changed plan or seed");
		require(service.work(level, centrifuge.claim().member(), state.revision(), NetworkCentrifugeService.Action.FREEZE, 0, false), "Cannot freeze restored old job");
		state = data.checkpoint().ownedMachines().get(centrifuge.claim().member()).centrifuge();
		state.jobs().get(0).frozen().forEach((key, amount) -> expected.merge(key, amount, ProductAmount::add));
		long revision = state.revision();
		require(service.work(level, centrifuge.claim().member(), revision, NetworkCentrifugeService.Action.SETTLE, 0, false), "Cannot settle restored job");
		var settled = data.checkpoint();
		require(!service.work(level, centrifuge.claim().member(), revision, NetworkCentrifugeService.Action.SETTLE, 0, false)
				&& data.checkpoint() == settled && settled.ledger().balances().equals(expected), "Restored work duplicated or lost outputs");
		long spent = (long) remaining * bee.plan().energyPerTick() + (long) (job.plan().cycleTicks() - job.progress()) * job.plan().energyPerTick(job.operations());
		require(energy - settled.energy().stored() == spent && beforeInventory.equals(CompetitionAssets.inventories(players)), "Restored work changed players or charged new prices");
		restored = true;
	}
	static void report(JsonObject report, boolean reader) {
		require(reader ? restored : prepared, "Missing upgrade work recovery checks");
		report.addProperty("upgradePartialWorkPreserved", !reader && prepared);
		report.addProperty("upgradeRestoredWorkSettledExactlyOnce", reader && restored);
	}
	private static ProductPolicyRegistry policy(NetworkCoreBlockEntity core, ServerPlayer player) {
		return new ProductPolicyRegistry(PbProductPolicyCompiler.compile(player.serverLevel(), core.ownership().readyAuthority().checkpoint().policyRevision()).snapshot());
	}
	private UpgradeCompetitionRecovery() {}
}
