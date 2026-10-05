package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import mekanism.api.Upgrade;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 客户端只发布阶段请求；服务器线程执行测试位移并独立核对最终资产。 */
final class ClientMemberProxyFixture {
	static volatile int positionRequest, positionApplied;
	static volatile boolean done, verified;
	static volatile boolean terminalsRequested, terminalsReady, terminalsDone, terminalsVerified;
	static volatile boolean combinedDone, combinedVerified;
	static volatile boolean detailsDone, detailsRestored;
	static volatile int liveCommand, liveApplied;
	static volatile boolean liveRestored;
	static volatile boolean liveBeeRequested, liveBeeChanged;
	static volatile boolean controlRequested, controlVerified;
	private static java.util.Map<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey, com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount> liveStock;
	private static boolean terminalsChecked;
	static void tick(NetworkCoreBlockEntity core, ServerPlayer player) {
		if (controlRequested && !controlVerified) verifyBeeControl(core, player);
		if (liveBeeRequested && !liveBeeChanged) replaceLiveBee(core, player);
		if (liveCommand != liveApplied) updateLiveStock(core, player);
		if (positionRequest != positionApplied) {
			player.teleportTo(positionRequest == 1 ? 17.5 : 8.5, 102, 8.5); positionApplied = positionRequest;
		}
		if (terminalsRequested && !terminalsReady) {
			if (!terminalsChecked) { DedicatedTerminalChecks.verify(core, player); terminalsChecked = true; return; }
			terminalsReady = DedicatedTerminalChecks.advanceDetails(core, player);
		}
		if (terminalsDone && !terminalsVerified) {
			require(player.getInventory().getItem(6).getCount() == 2, "Dedicated terminal upgrade conservation");
			for (var record : core.ownership().readyAuthority().checkpoint().ownedMachines().activeValues())
				require(NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0) == 0, "Dedicated terminal retained duplicate upgrade");
			terminalsVerified = true;
		}
		if (combinedDone && !combinedVerified) {
			require(player.getInventory().getItem(6).getCount() == 2, "Combined terminal upgrade conservation");
			for (var record : core.ownership().readyAuthority().checkpoint().ownedMachines().activeValues())
				require(NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0) == 0, "Combined terminal duplicated upgrade");
			combinedVerified = true;
		}
		if (detailsDone && !detailsRestored) { DedicatedTerminalChecks.cleanupDetails(core, player); detailsRestored = true; }
		if (!done || verified) return;
		require(player.getInventory().getItem(6).getCount() == 2 && player.getInventory().getItem(7).getCount() == 2
				&& player.getInventory().getItem(0).getCount() == 64, "Proxy UI changed inventory conservation");
		var data = core.ownership().readyAuthority(); require(data != null, "Proxy UI lost network authority");
		for (var record : data.checkpoint().ownedMachines().activeValues())
			require(NativeUpgradeCounts.read(record.assets().copy().getCompound("upgrades")).getOrDefault(Upgrade.SPEED, 0) == 0, "Proxy UI left duplicate upgrades");
		require(player.serverLevel().getBlockState(new net.minecraft.core.BlockPos(9, 101, 8)).isAir()
				&& player.serverLevel().getBlockState(new net.minecraft.core.BlockPos(10, 101, 8)).isAir(), "Proxy click placed the held block");
		verified = true;
	}
	private static void updateLiveStock(NetworkCoreBlockEntity core, ServerPlayer player) {
		var data = core.ownership().readyAuthority(); require(data != null, "Live fixture lost authority");
		var stock = new java.util.HashMap<>(data.checkpoint().ledger().balances());
		if (liveCommand == 1) {
			liveStock = java.util.Map.copyOf(stock);
			for (int i = 0; i < 80; i++) {
				var item = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT);
				item.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("live-" + i));
				stock.put(com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.item(item, player.registryAccess()),
						com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(10));
			}
		} else if (liveCommand == 2) {
			var key = com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.item(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COAL), player.registryAccess());
			require(stock.get(key).longSaturated() == 1024, "Unexpected live stock baseline");
			com.ayoshiko.productivebeesgenesis.apiculture.persistence.ClientTerminalStockFixture.withdraw(data, key, 7);
			liveApplied = liveCommand; return;
		} else if (liveCommand == 3) stock = new java.util.HashMap<>(liveStock);
		else throw new IllegalStateException("Invalid live fixture step");
		com.ayoshiko.productivebeesgenesis.apiculture.persistence.ClientTerminalStockFixture.seed(data, stock);
		if (liveCommand == 3) { require(data.checkpoint().ledger().balances().equals(liveStock), "Live fixture changed original assets"); liveRestored = true; liveStock = null; }
		liveApplied = liveCommand;
	}
	private static void verifyBeeControl(NetworkCoreBlockEntity core, ServerPlayer player) {
		var menu = (com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu) player.containerMenu;
		var data = core.ownership().readyAuthority(); var directory = com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.directory(player.server);
		var record = data.checkpoint().ownedMachines().activeValues(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY.machine()).iterator().next();
		var state = record.bees(); var bee = state.bee(0); var member = record.claim().member(); var before = data.checkpoint();
		require(!bee.enabled(), "UI did not disable selected bee");
		require(menu.setBeeEnabled(player, member, 0, bee.id(), state.rosterVersion(), true, true) && data.checkpoint() == before, "Simulated control mutated authority");
		require(!menu.setBeeEnabled(player, member, 0, java.util.UUID.randomUUID(), state.rosterVersion(), true, false), "Wrong bee identity controlled work");
		require(!menu.setBeeEnabled(player, member, 2, bee.id(), state.rosterVersion(), true, false), "Empty slot controlled another bee");
		require(menu.setBeeEnabled(player, member, 0, bee.id(), state.rosterVersion(), true, false), "Control enable failed");
		var enabled = data.checkpoint().ownedMachines().get(member).bees();
		require(menu.setBeeEnabled(player, member, 0, bee.id(), enabled.rosterVersion(), false, false), "Control disable failed");
		var paused = data.checkpoint();
		require(!menu.setBeeEnabled(player, member, 0, bee.id(), state.rosterVersion(), true, false) && data.checkpoint() == paused, "ABA revived old control selection");
		var current = paused.ownedMachines().get(member).bees().bee(0);
		var service = new com.ayoshiko.productivebeesgenesis.apiculture.production.NetworkBeeService(data, directory);
		require(service.advance(player.serverLevel(), member, 0, current.revision(), current.plan().recipeRevision(), current.plan().capabilityRevision(), 1, 1, false)
				== com.ayoshiko.productivebeesgenesis.apiculture.production.BeeWorkExecutor.Status.DISABLED && data.checkpoint() == paused, "Direct production bypassed bee control");
		try {
			var run = Class.forName("com.ayoshiko.productivebeesgenesis.apiculture.runtime.BeeRuntimeStep").getDeclaredMethod("run",
					net.minecraft.server.level.ServerLevel.class, com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkSavedData.class,
					com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkDirectory.class, java.util.UUID.class, int.class, boolean.class);
			run.setAccessible(true);
			for (int i = 0; i < 20; i++) require(run.invoke(null, player.serverLevel(), data, directory, member, 0, true)
					== com.ayoshiko.productivebeesgenesis.apiculture.runtime.NetworkRuntime.Status.STOPPED, "Running scheduler advanced disabled bee");
		} catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot invoke production runtime probe", failure); }
		require(data.checkpoint() == paused && current.progress() == bee.progress() && current.random().equals(bee.random())
				&& current.originalSlot().equals(bee.originalSlot()) && state.feeding() == paused.ownedMachines().get(member).bees().feeding(), "Control changed paid assets or food");
		controlVerified = true;
	}
	private static void replaceLiveBee(NetworkCoreBlockEntity core, ServerPlayer player) {
		var menu = (com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu) player.containerMenu;
		var data = core.ownership().readyAuthority();
		var record = data.checkpoint().ownedMachines().activeValues(com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope.APIARY.machine()).iterator().next();
		var oldBee = record.bees().bee(0);
		require(!oldBee.enabled() && controlVerified, "Cage control fixture was not paused");
		require(menu.exchangeBee(player, record.claim().member(), 0, record.bees().revision(), oldBee.id(), 1,
				com.ayoshiko.productivebeesgenesis.apiculture.core.CoreBeeCageExchange.Action.EXTRACT, false).moved() == 1, "Live bee extraction failed");
		record = data.checkpoint().ownedMachines().get(record.claim().member());
		require(menu.exchangeBee(player, record.claim().member(), 0, record.bees().revision(), null, 1,
				com.ayoshiko.productivebeesgenesis.apiculture.core.CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Live bee reinsertion failed");
		var newBee = data.checkpoint().ownedMachines().get(record.claim().member()).bees().bee(0);
		require(!oldBee.id().equals(newBee.id()) && newBee.enabled() && oldBee.originalSlot().equals(newBee.originalSlot()), "Live bee identity or asset conservation mismatch");
		liveBeeChanged = true;
	}
	private ClientMemberProxyFixture() { }
}
