package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductPolicyRegistry;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedWorkCodec;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 在既有世界夹具验证真实 PB 交换、公式边界及接口菜单资格。 */
final class MachinePbUpgradeChecks {
	static void verify(MachineMenu menu, ServerPlayer player, MachineControllerEntity core, MachineControllerEntity other) {
		var initial = core.assets.work(); var untouched = other.assets.work();
		var baseBee = MachineUpgradeProfiles.apiary(initial.upgrades());
		var baseCentrifuge = MachineUpgradeProfiles.centrifuge(initial.upgrades());
		var level = player.serverLevel(); var policy = new ProductPolicyRegistry(RuntimeProductPolicies.peek(level));
		var input = initial.bee(5).plan().sourceOutput();
		var basePlan = StaticCentrifugeAdapter.compile(level, policy, input, 0, baseCentrifuge);
		for (int slot = MachineUpgrades.NATIVE_SLOTS; slot < MachineUpgrades.SLOTS; slot++) {
			var before = core.assets.work(); var pb = MachineUpgrades.pbType(slot); boolean bee = MachineUpgrades.apiarySlot(slot);
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot));
			check(exchange(menu, player, slot, true, 1, true).moved() == 1 && core.assets.work() == before, "PB simulation changed assets");
			player.getInventory().items.get(0).set(DataComponents.CUSTOM_NAME, Component.literal("custom PB"));
			check(exchange(menu, player, slot, true, 1, false).status() == MachineExchange.Status.INVALID && core.assets.work() == before, "PB custom components were discarded");
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot));
			check(exchange(menu, player, slot, true, 1, false).moved() == 1 && player.getInventory().items.get(0).isEmpty(), "PB installation failed: " + pb);
			var installed = core.assets.work();
			check(installed.bees().equals(initial.bees()) && installed.centrifuges().equals(initial.centrifuges()) && installed.energy() == initial.energy(), "PB installation changed old work");
			var a = MachineUpgradeProfiles.apiary(installed.upgrades()); var c = MachineUpgradeProfiles.centrifuge(installed.upgrades());
			check(bee ? c.equals(baseCentrifuge) : a.equals(baseBee), "PB effect crossed subsystem");
			boolean productivity = pb.getId().startsWith("productivity"), time = pb == PbUpgradeType.TIME || pb == PbUpgradeType.TIME_2;
			if (bee) {
				check(a.energy() == baseBee.energy(), "PB apiary upgrade changed FE price");
				if (productivity) check(a.productivity() > baseBee.productivity(), "Missing bee productivity");
				if (time) check(a.time() < baseBee.time(), "Missing bee time effect");
				check(a.combBlock() == (pb == PbUpgradeType.BLOCK || pb == PbUpgradeType.PRODUCTIVITY_4), "Wrong comb block effect");
			} else {
				check(c.energy() == baseCentrifuge.energy(), "PB centrifuge upgrade changed FE price");
				if (productivity) {
					check(c.parallel() > baseCentrifuge.parallel(), "Missing PB parallelism");
					check(BalanceConfig.centrifugeProductivityAffectsOutput() ? c.productivity() > 1 : c.productivity() == 1, "Ignored centrifuge output balance rule");
				}
				if (time) check(c.time() < baseCentrifuge.time(), "Missing centrifuge time effect");
				if (pb == PbUpgradeType.STABILITY) check(c.stability() > 0, "Missing stability");
				if (pb == PbUpgradeType.USELESS_BYPRODUCT) {
					var filtered = StaticCentrifugeAdapter.compile(level, policy, input, installed.upgrades().revision(), c);
					check(c.discardByproducts() && filtered.outputs().size() < basePlan.outputs().size(), "Byproduct filter did not change the new plan");
					check(installed.centrifuges().get(0).job().plan().equals(initial.centrifuges().get(0).job().plan()), "Byproduct filter rewrote paid outputs");
				}
			}
			var restored = CombinedWorkCodec.decode(CombinedWorkCodec.encode(installed), key -> {}, key -> 64, food -> {});
			check(restored.upgrades().equals(installed.upgrades()) && other.assets.work() == untouched, "PB restore or machine isolation failed");
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot).copyWithCount(64));
			check(exchange(menu, player, slot, false, 1, false).status() == MachineExchange.Status.NO_SPACE && core.assets.work() == installed, "Full inventory consumed a PB upgrade");
			player.getInventory().items.set(0, ItemStack.EMPTY);
			check(exchange(menu, player, slot, false, 1, false).moved() == 1 && player.getInventory().items.get(0).getCount() == 1, "PB return lost its item");
		}
		int alpha = MachineUpgrades.slot(true, PbUpgradeType.PRODUCTIVITY), beta = MachineUpgrades.slot(true, PbUpgradeType.PRODUCTIVITY_2);
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(alpha).copyWithCount(2));
		check(exchange(menu, player, alpha, true, 2, false).moved() == 2, "Cannot prepare PB cap check");
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(beta));
		check(BalanceConfig.productivityTiersExclusive() && exchange(menu, player, beta, true, 1, false).status() == MachineExchange.Status.CONFLICT, "PB tier conflict accepted");
		int oldLimit = ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount.get();
		try {
			menu.broadcastChanges(); long oldView = menu.viewRevision();
			ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount.set(1); menu.broadcastChanges();
			check(menu.upgradeCount(alpha) == 2 && menu.upgradeLimit(alpha) == 1 && menu.viewRevision() > oldView, "Config cap change clamped assets or did not refresh view");
			player.getInventory().items.set(0, MachineUpgradeProfiles.unit(alpha));
			check(exchange(menu, player, alpha, true, 1, false).status() == MachineExchange.Status.LIMIT, "Lowered cap still accepted installation");
			player.getInventory().items.set(0, ItemStack.EMPTY);
			check(exchange(menu, player, alpha, false, 64, false).moved() == 2 && player.getInventory().items.get(0).getCount() == 2, "Lowered cap prevented full return");
		} finally { ModConfig.SERVER.apiaryPbUpgradeProductivityMaxCount.set(oldLimit); }
		int[] combination = {MachineUpgrades.slot(true, PbUpgradeType.PRODUCTIVITY_4), MachineUpgrades.slot(true, PbUpgradeType.BLOCK),
				MachineUpgrades.slot(true, PbUpgradeType.TIME_2)};
		for (int slot : combination) { player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot)); check(exchange(menu, player, slot, true, 1, false).moved() == 1, "Cannot prepare combined PB effects"); }
		var installed = core.assets.work(); var candidate = installed; var production = new MachineProduction();
		int remaining = initial.bee(5).plan().cycleTicks() - initial.bee(5).progress();
		for (int i = 0; i < remaining; i++) {
			candidate = production.advance(level, candidate);
			check(candidate.bee(5).plan().equals(initial.bee(5).plan()), "PB changed an in-progress cycle");
		}
		check(candidate.bee(5).progress() == 0 && candidate.bee(5).drained(), "Old bee cycle did not settle");
		candidate = production.advance(level, candidate);
		var newPlan = candidate.bee(5).plan();
		check(newPlan.productionMultiplier() > 1 && !newPlan.output().equals(newPlan.sourceOutput()) && candidate.bee(5).progress() > 0, "New bee cycle did not adopt PB output");
		check(newPlan.output().equals(StaticApiaryAdapter.output(input, true, level.registryAccess())), "Omega and block converted more than once");
		check(core.assets.work() == installed, "Private production candidate leaked into world assets");
		for (int slot : combination) { player.getInventory().items.set(0, ItemStack.EMPTY); check(exchange(menu, player, slot, false, 64, false).moved() == 1, "Combined PB return failed"); }
		check(other.assets.work() == untouched && core.assets.work().bees().equals(initial.bees()), "PB checks altered unrelated work");
	}
	static MachineMenu interfaceMenu(ServerPlayer player, MachineProbeFixture fixture) {
		var level = player.serverLevel(); var core = fixture.core();
		var local = fixture.template().features().entrySet().stream().filter(e -> e.getValue().roles().contains(StructureRole.INTERFACE)).findFirst().orElseThrow().getKey();
		var pos = fixture.world(local); var part = (MachinePartEntity) level.getBlockEntity(pos);
		var away = pos.getCenter().subtract(core.getBlockPos().getCenter()).normalize();
		var standing = pos.getCenter().add(away.scale(7.5)); player.setPos(standing);
		check(!core.allowed(player), "Interface fixture did not exceed controller radius");
		var menu = new MachineMenu(83, player.getInventory(), core, UUID.randomUUID(), part); player.containerMenu = menu;
		check(menu.controller(player) == core, "Interface menu still required controller distance");
		int slot = MachineUpgrades.slot(false, PbUpgradeType.STABILITY);
		player.getInventory().items.set(0, MachineUpgradeProfiles.unit(slot));
		check(exchange(menu, player, slot, true, 1, false).moved() == 1, "Interface could not use shared PB service");
		check(exchange(menu, player, slot, false, 1, false).moved() == 1, "Interface could not return PB item");
		var stranger = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(UUID.randomUUID(), "InterfaceVisitor"));
		stranger.setPos(standing); stranger.containerMenu = menu;
		check(menu.controller(stranger) == null && !MachineMenu.open(part, stranger), "Interface gave access to a stranger");
		player.setPos(pos.getCenter().add(away.scale(8.1))); check(menu.controller(player) == null, "Interface ignored its own distance");
		player.setPos(standing);
		var clone = new MachinePartEntity(pos, part.getBlockState()); clone.setLevel(level); clone.bind(part.binding());
		var fake = new MachineMenu(84, player.getInventory(), core, UUID.randomUUID(), clone);
		check(!fake.stillValid(player), "Unpublished interface instance gained authority");
		return menu;
	}
	private static MachineExchange.Result exchange(MachineMenu menu, ServerPlayer player, int slot, boolean install, int amount, boolean simulate) {
		return MachineExchange.exchange(menu, player, install ? MachineExchange.Action.UPGRADE_IN : MachineExchange.Action.UPGRADE_OUT, slot, 0, amount, simulate);
	}
	private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
	private MachinePbUpgradeChecks() { }
}
