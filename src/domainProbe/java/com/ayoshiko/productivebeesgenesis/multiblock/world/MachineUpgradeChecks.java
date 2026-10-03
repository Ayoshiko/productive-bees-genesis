package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedWorkCodec;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 扩展既有生命周期夹具，走正式背包交换与生产入口。 */
final class MachineUpgradeChecks {
	static void verify(MachineMenu menu, ServerPlayer player, MachineControllerEntity core, MachineControllerEntity other) {
		var before = core.assets.work(); var otherBefore = other.assets.work();
		var oldAccess = MachineWorkService.access(core).orElseThrow(); var stale = before.receiveEnergy(1);
		var normalBee = MachineUpgradeProfiles.apiary(before.upgrades());
		var normalCentrifuge = MachineUpgradeProfiles.centrifuge(before.upgrades());
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(0).copyWithCount(2));
		check(exchange(menu, player, true, 0, 2, true).moved() == 2 && core.assets.work() == before && player.getInventory().items.get(0).getCount() == 2, "Simulation changed plugins");
		player.getInventory().items.get(0).set(DataComponents.CUSTOM_NAME, Component.literal("variant"));
		check(exchange(menu, player, true, 0, 1, false).status() == MachineExchange.Status.INVALID && core.assets.work() == before, "Customized upgrade lost components");
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(0).copyWithCount(2));
		menu.broadcastChanges(); long oldView = menu.viewRevision();
		check(exchange(menu, player, true, 0, 2, false).moved() == 2, "Real speed installation failed");
		var installed = core.assets.work();
		check(!MachineWorkService.commit(oldAccess, stale), "Old asset access survived plugin change");
		check(installed.bee(5).equals(before.bee(5)) && installed.centrifuges().equals(before.centrifuges()) && installed.energy() == before.energy(), "Installation rewrote old work or FE");
		check(MachineUpgradeProfiles.apiary(installed.upgrades()).time() < normalBee.time()
				&& MachineUpgradeProfiles.apiary(installed.upgrades()).energy() > normalBee.energy(), "Bee speed did not change next-cycle capacity");
		check(MachineUpgradeProfiles.centrifuge(installed.upgrades()).equals(normalCentrifuge) && other.assets.work() == otherBefore, "Bee upgrade affected other work or another machine");
		var advanced = new MachineProduction().advance(player.serverLevel(), installed);
		check(advanced.bee(5).plan().equals(installed.bee(5).plan()) && advanced.centrifuges().get(0).job().plan().equals(installed.centrifuges().get(0).job().plan()), "Old work switched its plan");
		check(advanced.energy() == installed.energy() - normalBee.energy() - normalCentrifuge.energy(), "Old work was repriced");
		menu.request(player, new MachineMenuRequest(menu.containerId, menu.session(), 3, oldView, 5, 0, 0, 1));
		check(menu.status() == MachineExchange.Status.STALE.ordinal() && core.assets.work() == installed, "Stale view removed a plugin");
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(0).copyWithCount(64));
		check(exchange(menu, player, false, 0, 2, false).status() == MachineExchange.Status.NO_SPACE && core.assets.work() == installed, "Full inventory lost plugin");
		player.getInventory().items.set(0, ItemStack.EMPTY);
		check(exchange(menu, player, false, 0, 2, false).moved() == 2 && player.getInventory().items.get(0).getCount() == 2, "Plugin return lost items");
		for (int slot = 1; slot < 4; slot++) {
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot).copyWithCount(9));
			check(exchange(menu, player, true, slot, 64, false).moved() == 8 && player.getInventory().items.get(0).getCount() == 1, "Plugin cap did not preserve remainder");
			check(exchange(menu, player, true, slot, 1, false).status() == MachineExchange.Status.LIMIT, "Plugin limit exceeded");
		}
		var configured = core.assets.work();
		check(MachineUpgradeProfiles.apiary(configured.upgrades()).energy() < normalBee.energy(), "Bee saving had no effect");
		check(MachineUpgradeProfiles.centrifuge(configured.upgrades()).time() < normalCentrifuge.time(), "Centrifuge speed had no effect");
		check(configured.energyCapacity() == before.energyCapacity(), "Plugins changed fixed capacity");
		var restored = CombinedWorkCodec.decode(CombinedWorkCodec.encode(configured), key -> {}, key -> 64, item -> {});
		check(restored.upgrades().equals(configured.upgrades()) && restored.bees().equals(configured.bees()), "Plugin checkpoint lost old work");
		for (int slot = 1; slot < 4; slot++) {
			player.getInventory().items.set(0, ItemStack.EMPTY);
			check(exchange(menu, player, false, slot, 64, false).moved() == 8, "Cannot retrieve all plugins");
		}
		check(MachineUpgradeProfiles.apiary(core.assets.work().upgrades()).equals(normalBee)
				&& MachineUpgradeProfiles.centrifuge(core.assets.work().upgrades()).equals(normalCentrifuge), "Removing plugins retained effects");
		check(other.assets.work() == otherBefore, "Plugin exchange mutated another machine");
	}
	private static MachineExchange.Result exchange(MachineMenu menu, ServerPlayer player, boolean install, int slot, int count, boolean simulate) {
		return MachineExchange.exchange(menu, player, install ? MachineExchange.Action.UPGRADE_IN : MachineExchange.Action.UPGRADE_OUT, slot, 0, count, simulate);
	}
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private MachineUpgradeChecks() { }
}
