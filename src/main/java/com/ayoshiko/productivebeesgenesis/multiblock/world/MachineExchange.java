package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.core.CoreInventorySync;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** 单个玩家槽与单机资产根交换；准备双方结果后在服务器线程内一次提交。 */
final class MachineExchange {
	enum Action { CAGE_IN, CAGE_OUT, FEED_IN, FEED_OUT, UPGRADE_IN, UPGRADE_OUT }
	enum Status { IDLE, MOVED, NO_SPACE, INVALID, STALE, DRAIN_FIRST, UNAVAILABLE, LIMIT, CONFLICT }
	record Result(Status status, int moved) { }
	static Result exchange(MachineMenu menu, ServerPlayer player, Action action, int slot, int inventorySlot, int amount, boolean simulate) {
		var core = menu.controller(player);
		if (core == null) return result(Status.UNAVAILABLE);
		if (action == null || slot < 0 || slot >= (action == Action.UPGRADE_IN || action == Action.UPGRADE_OUT ? MachineUpgrades.SLOTS : 6) || inventorySlot < 0 || inventorySlot >= 36 || amount < 1 || amount > 64) return result(Status.INVALID);
		var access = MachineWorkService.access(core).orElse(null); if (access == null) return result(Status.UNAVAILABLE);
		var work = access.work(); var level = player.serverLevel();
		var inventory = player.getInventory().items.get(inventorySlot).copy();
		CombinedMachineWork.Change change; ItemStack received;
		try {
			switch (action) {
				case CAGE_IN -> {
					if (work.bees().stream().anyMatch(bee -> bee.slot() == slot)) return result(Status.NO_SPACE);
					var policy = RuntimeProductPolicies.peek(level); if (policy == null) return result(Status.UNAVAILABLE);
					var bee = StaticApiaryAdapter.caged(level, MachineUpgradeProfiles.apiary(work.upgrades()), slot, work.beeSlots(), VerifiedCageProjection.contents(inventory), policy.revision());
					received = VerifiedCageProjection.afterRelease(inventory);
					change = work.insertBee(slot, bee.original(), bee.plan());
				}
				case CAGE_OUT -> {
					var bee = work.bees().stream().filter(value -> value.slot() == slot).findFirst().orElse(null);
					if (bee == null) return result(Status.NO_SPACE);
					if (!bee.drained()) return result(Status.DRAIN_FIRST);
					received = VerifiedCageProjection.fill(inventory, bee); change = work.extractBee(slot, bee.id());
				}
				case FEED_IN -> {
					if (inventory.isEmpty()) return result(Status.NO_SPACE);
					change = work.depositFeeding(slot, StaticFeedingAdapter.fromStack(inventory, level.registryAccess()), Math.min(amount, inventory.getCount()));
					received = inventory.copyWithCount(inventory.getCount() - (int) change.moved());
				}
				case FEED_OUT -> {
					var source = work.feeding().get(slot); if (source.item() == null) return result(Status.NO_SPACE);
					var unit = StaticFeedingAdapter.toStack(source.item(), 1, level.registryAccess());
					if (!inventory.isEmpty() && !ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.NO_SPACE);
					int count = inventory.getCount(), space = Math.min(64, unit.getMaxStackSize()) - count;
					if (space <= 0) return result(Status.NO_SPACE);
					change = work.withdrawFeeding(slot, Math.min(amount, space)); received = unit.copyWithCount(count + (int) change.moved());
				}
				case UPGRADE_IN, UPGRADE_OUT -> {
					var unit = MachineUpgradeProfiles.unit(slot); int installed = work.upgrades().count(slot), moved;
					if (unit.isEmpty()) return result(Status.INVALID);
					if (action == Action.UPGRADE_IN) {
						if (inventory.isEmpty() || !ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.INVALID);
						var pb = MachineUpgrades.pbType(slot);
						if (pb != null && !BalanceConfig.canInstall(pb, work.upgrades().pbCounts(MachineUpgrades.apiarySlot(slot)))) return result(Status.CONFLICT);
						moved = Math.min(Math.min(amount, inventory.getCount()), MachineUpgradeProfiles.limit(slot) - installed);
						if (moved <= 0) return result(Status.LIMIT);
						received = inventory.copyWithCount(inventory.getCount() - moved);
					} else {
						if (!inventory.isEmpty() && !ItemStack.isSameItemSameComponents(inventory, unit)) return result(Status.NO_SPACE);
						int space = Math.min(64, unit.getMaxStackSize()) - inventory.getCount();
						moved = Math.min(Math.min(amount, installed), space);
						if (moved <= 0) return result(Status.NO_SPACE);
						received = unit.copyWithCount(inventory.getCount() + moved);
					}
					change = work.exchangeUpgrade(slot, action == Action.UPGRADE_IN ? moved : -moved);
					// 配置无效时拒绝安装；取回实物始终不依赖当前公式是否可计算。
					if (action == Action.UPGRADE_IN) {
						var candidate = change.apply(work);
						MachineUpgradeProfiles.apiary(candidate.upgrades()); MachineUpgradeProfiles.centrifuge(candidate.upgrades());
					}
				}
				default -> throw new IllegalArgumentException("Unknown machine exchange");
			}
		} catch (IllegalArgumentException invalid) { return result(Status.INVALID); }
		catch (RuntimeException failure) {
			com.mojang.logging.LogUtils.getLogger().warn("Cannot prepare machine exchange at {}", core.getBlockPos(), failure);
			return result(Status.INVALID);
		}
		if (!change.changed()) return result(Status.NO_SPACE);
		if (menu.controller(player) != core || !access.current() || !ItemStack.matches(inventory, player.getInventory().items.get(inventorySlot))) return result(Status.STALE);
		if (!simulate) {
			// 两次写入间不触发外部能力、实体生成或保存；发送失败也不能退回并重复交付。
			if (!MachineWorkService.commit(access, change)) return result(Status.STALE);
			player.getInventory().items.set(inventorySlot, received); player.getInventory().setChanged();
			CoreInventorySync.committed(player, inventorySlot, received);
		}
		return new Result(Status.MOVED, (int) change.moved());
	}
	private static Result result(Status status) { return new Result(status, 0); }
	private MachineExchange() { }
}
