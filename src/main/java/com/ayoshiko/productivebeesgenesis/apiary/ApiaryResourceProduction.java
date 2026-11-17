package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceCatalog;
import com.ayoshiko.productivebeesgenesis.resource.ResourceAccount;
import com.ayoshiko.productivebeesgenesis.resource.ResourceExactAmounts;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRollPlan;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionSnapshot;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.init.ModEntities;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import net.minecraft.nbt.CompoundTag;
import java.math.BigInteger;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/** 机械蜂箱资源结算；混合产物共用同一事务，纯资源不进入默认蜂蜜路径。 */
final class ApiaryResourceProduction {
	private ApiaryResourceProduction() {}

    record Prepared(ResourceRollPlan plan, ApiaryMixedProduction mixed, CompoundTag bee,
            ApiaryBatchUpgradeSnapshot upgrades, int flowerVersion, long recipeVersion) {
        boolean valid(ApiaryBatchUpgradeSnapshot current, int currentFlowerVersion, BeeSlot slot) {
            return slot.getBeeData() == bee && recipeVersion == ProductiveBeesGenesis.RECIPE_VERSION.get()
                    && flowerVersion == currentFlowerVersion && upgrades.equals(current);
        }
    }

    static Prepared prepare(TileEntityMekApiary tile, PbResourceCatalog.Spec spec,
            ApiaryUpgradeHandler upgrades, BeeSlot slot, int index, ResourceLocation type, Prepared previous) {
        return prepare(tile, spec, upgrades, slot, index, type, previous, ApiaryBatchUpgradeSnapshot.capture(tile, upgrades),
                tile.getFeederSlotManager().getFlowerCacheVersion());
    }

    static Prepared prepare(TileEntityMekApiary tile, PbResourceCatalog.Spec spec,
            ApiaryUpgradeHandler upgrades, BeeSlot slot, int index, ResourceLocation type, Prepared previous,
            ApiaryBatchUpgradeSnapshot snapshot, int flowerVersion) {
        if (!spec.validForMechanical() || slot.getBeeData() == null) {
            slot.setState(BeeState.WAITING_RESOURCE);
            return null;
        }
        boolean reuse = previous != null && previous.valid(snapshot, flowerVersion, slot);
        // 正式入箱保留蜂笼的 entity 字段，旧居住数据使用 id；共用已有解析规则。
        if (!reuse && !spec.dynamic()
                && BeeNbtHelper.resolveEntityType(slot.getBeeData()) != ModEntities.CONFIGURABLE_BEE.get()) {
            slot.setState(BeeState.WAITING_RESOURCE);
            return null;
        }
        ResourceHiveHost host = null;
        try {
            host = ResourceHiveHost.ensureMechanical(tile);
            if (host == null) {
                slot.setState(BeeState.WAITING_RESOURCE);
                return null;
            }
            host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_RESOURCE,
                    tile.getLevel().getGameTime());
            var account = host.account(tile);
            if (!account.canExtract(host.reference()) || account.laneSuspended(index)) {
                slot.setState(BeeState.WAITING_RESOURCE);
                return null;
            }
            Prepared prepared = previous;
            if (!reuse) {
                var plan = new ResourceRollPlan(spec.outputs(), upgrades.getProductivityMultiplier(), slot.getProductivityLevel(), 0);
                var mixed = (spec.mixed() || spec.conversions() || spec.dynamic() || upgrades.getGeneSamplerCount() > 0) && tile.getLevel() instanceof ServerLevel level
                        ? new ApiaryMixedProduction(tile, level, type, spec, upgrades, slot) : null;
                prepared = new Prepared(plan, mixed, slot.getBeeData(), snapshot,
                        flowerVersion, ProductiveBeesGenesis.RECIPE_VERSION.get());
            }
            var singleCycle = prepared.plan().maximumExact(upgrades.getStackProductionCount());
            var mixed = prepared.mixed();
            if (mixed != null && !spec.conversions() && mixed.maximumCycles(upgrades.getStackProductionCount()) == 0) {
                slot.setState(BeeState.WAITING_OUTPUT);
                host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_OUTPUT,
                        tile.getLevel().getGameTime());
                return null;
            }
            if (!singleCycle.isEmpty() && !host.fitsExact(account, singleCycle)) {
                slot.setState(BeeState.WAITING_RESOURCE);
                return null;
            }
            host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_WORK,
                    tile.getLevel().getGameTime());
            return prepared;
        } catch (RuntimeException | LinkageError failure) {
            preparationFailure(tile, host, slot, index, type, spec, failure);
            return null;
        }
    }

    static boolean eligible(TileEntityMekApiary tile, PbResourceCatalog.Spec spec,
            ApiaryUpgradeHandler upgrades, BeeSlot slot, int index, ResourceLocation type) {
        return prepare(tile, spec, upgrades, slot, index, type, null) != null;
    }

	static BeeState advance(TileEntityMekApiary tile, ServerLevel level, BeeSlot slot, int index,
			ResourceLocation type, PbResourceCatalog catalog, PbResourceCatalog.Spec spec,
			int allocatedTicks, float timeMultiplier, boolean creative, int baseTime,
			ApiaryUpgradeHandler upgrades, IEnergyContainer energy, long energyPerTick, long legacyEnergyReserved, Prepared prepared,
			ApiaryConversionProcessor conversionProcessor, long conversionBatch, ApiaryBatchUpgradeSnapshot snapshot) {
		var host = ResourceHiveHost.existing(tile);
		if (host == null) return BeeState.WAITING_RESOURCE;
		host.productionMonitor().observe(index, spec.outputs(),
				ResourceProductionSnapshot.State.WAITING_RESOURCE, level.getGameTime());
		ResourceAccount account = null;
		ResourceAccount.Ticket ticket = null;
		try {
			account = host.account(tile);
			if (!account.canExtract(host.reference()) || account.laneSuspended(index)) return BeeState.WAITING_RESOURCE;
			if (tile.getProduceProcessor().production.hasInvalidData()) return BeeState.PRODUCTION_SUSPENDED;
			if (tile.getProduceProcessor().production.hasFrozen()) return BeeState.WAITING_FROZEN_OUTPUT;
			int stack = upgrades.getStackProductionCount();
			if (stack < 1) throw new IllegalArgumentException("Invalid STACK multiplier");
			if (prepared == null || !prepared.valid(snapshot, tile.getFeederSlotManager().getFlowerCacheVersion(), slot))
				return BeeState.WAITING_RESOURCE;
            var plan = prepared.plan();
            long seed = level.getRandom().nextLong();
			var perCycle = plan.maximumExact(stack);
			long capacityCycles = Long.MAX_VALUE;
			var capacityUsed = account.reservedCapacity();
			for (var entry : perCycle.entrySet()) {
				BigInteger limit = ResourceTypes.EMC.equals(entry.getKey())
						? ResourceExactAmounts.MAX_VALUE : BigInteger.valueOf(host.capacity());
                BigInteger room = limit.subtract(capacityUsed.getOrDefault(entry.getKey(), BigInteger.ZERO));
                long cycles = room.max(BigInteger.ZERO).divide(entry.getValue()).min(BigInteger.valueOf(Long.MAX_VALUE)).longValueExact();
                capacityCycles = Math.min(capacityCycles, cycles);
			}
			if (capacityCycles == 0 || (!perCycle.isEmpty() && !host.fitsExact(account, perCycle))) {
				return BeeState.WAITING_RESOURCE;
			}
			int required = ApiaryProgressAdvancer.requiredTicks(slot, baseTime, timeMultiplier, creative);
			int previousTicks = Math.max(0, slot.getTicksInHive());
			var mixed = prepared.mixed();
			var flower = spec.conversions() ? conversionProcessor.prepareResource(type, level, index, conversionBatch) : null;
			if (mixed != null) {
				long possibleCycles = ((long) previousTicks + Math.max(0, allocatedTicks)) / required;
				capacityCycles = Math.min(capacityCycles, mixed.maximumCycles(Math.multiplyExact(Math.max(1, possibleCycles), stack), flower) / stack);
				if (capacityCycles == 0) {
					host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_OUTPUT,
							level.getGameTime());
					return BeeState.WAITING_OUTPUT;
				}
			}
			host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_WORK, level.getGameTime());
			var progress = ResourceProgressPlan.plan(previousTicks, required, Math.max(0, allocatedTicks), capacityCycles);
			if (progress.advancedTicks() == 0 && progress.completedCycles() == 0) return BeeState.WAITING_ENERGY;
			long cost = Math.multiplyExact(energyPerTick, progress.advancedTicks());
			if (cost < 0 || cost > Math.max(0, energy.getEnergy() - legacyEnergyReserved)
					|| energy.extract(cost, Action.SIMULATE, AutomationType.INTERNAL) != cost) {
				return BeeState.WAITING_ENERGY;
			}
			long count = Math.multiplyExact(progress.completedCycles(), stack);
			if (count == 0) flower = null;
			else if (flower != null) {
				flower.sample(level);
				if (flower.occupiesCycle()) count--;
			}
			var maximum = count == 0 ? perCycle : plan.maximumExact(count);
			var sampled = plan.sampleWithRolls(count, seed);
			var result = sampled.exactAmounts();
			var local = mixed == null ? null : mixed.sample(count, sampled.rolls(), level.getRandom().nextLong(),
					flower == null ? java.util.List.of() : flower.output());
			var beeSnapshot = slot.getBeeData();
			boolean localOutput = local != null
					&& (!local.generated().items().isEmpty() || local.generated().fluidAmount() > 0);
			long energyBefore = 0;
			if (!maximum.isEmpty() || localOutput || cost > 0 || flower != null) {
				energyBefore = energy.getEnergy();
				var context = new CompoundTag();
				context.putString("host", "mek_apiary");
				context.putString("bee", type.toString());
				context.put("bee_data", beeSnapshot.copy());
				context.putInt("slot", index);
				context.putInt("previous_ticks", previousTicks);
				context.putInt("next_ticks", progress.remainingTicks());
				context.putLong("cycles", count);
				context.putLong("energy_before", energyBefore);
				context.putLong("energy_cost", cost);
				if (local != null) context.put("mixed_local", local.context(level.registryAccess()));
				if (flower != null) context.put("flower", flower.context(level.registryAccess()));
				ticket = account.reserve(host.reference(), catalog.revision(), context, maximum);
				account.start(ticket);
				account.freeze(ticket, result);
			}
			Throwable energyNotificationFailure = null;
			if (cost > 0) {
				try {
					if (energy.extract(cost, Action.EXECUTE, AutomationType.INTERNAL) != cost) {
						throw new IllegalStateException("Mechanical resource energy debit was incomplete");
					}
				} catch (RuntimeException | LinkageError failure) {
					// 原生容器先改余额再通知；同一调用内全额已扣时继续原冻结结果，不重复收费。
					if (!(energy instanceof BasicEnergyContainerAccessor committed)
							|| energyBefore - committed.productivebeesgenesis$getStored() != cost) {
						throw failure;
					}
					energyNotificationFailure = failure;
				}
			}
			if (host.account(tile) != account || slot.getBeeData() != beeSnapshot || slot.getTicksInHive() != previousTicks) {
				throw new IllegalStateException("Mechanical resource source changed during commit");
			}
			if (flower != null) flower.commit();
			if (localOutput) local.commit();
			if (host.account(tile) != account || slot.getBeeData() != beeSnapshot || slot.getTicksInHive() != previousTicks) {
				throw new IllegalStateException("Mechanical resource source changed during local output");
			}
			slot.setTicksInHive(progress.remainingTicks());
			slot.setMinOccupationTicks(required);
			slot.setProgress(Math.min(1, (float) progress.remainingTicks() / required));
			tile.setChanged();
			host.account(tile);
			if (ticket != null) {
				account.commit(ticket);
				ticket = null;
			}
			host.productionMonitor().record(result);
			host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.PRODUCING, level.getGameTime());
			host.status("ready");
			if (energyNotificationFailure != null) {
				LogThrottle.infoWithCooldown("apiary_resource_energy_recovered", 60_000L,
						"机械蜂箱费用通知异常已按实际扣量自动结清，位置 {}，槽位 {}，蜂种 {}，费用 {}",
						tile.getBlockPos(), index, type, cost, energyNotificationFailure);
			}
			return BeeState.WORKING;
		} catch (RuntimeException | LinkageError failure) {
			if (ticket == null) {
				preparationFailure(tile, host, slot, index, type, spec, failure);
				return BeeState.RESOURCE_PREPARATION_FAILED;
			}
			try {
				account.suspendProduction(ticket, "Mechanical resource outcome uncertain: " + failure.getClass().getSimpleName());
			} catch (RuntimeException isolationFailure) {
				failure.addSuppressed(isolationFailure);
			}
			LogThrottle.error("apiary_resource_uncertain",
					"机械蜂箱资源结算结果未知，账户 {} 请求 {}，位置 {}，槽位 {}，蜂种 {}",
					ticket.source().sourceId(), ticket.sequence(), tile.getBlockPos(), index, type, failure);
			host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.WAITING_RESOURCE,
					level.getGameTime());
			host.status("production_uncertain");
			return BeeState.WAITING_RESOURCE;
		}
	}
    private static void preparationFailure(TileEntityMekApiary tile, ResourceHiveHost host, BeeSlot slot,
            int index, ResourceLocation type, PbResourceCatalog.Spec spec, Throwable failure) {
        boolean firstFailure = slot.getState() != BeeState.RESOURCE_PREPARATION_FAILED;
        slot.setState(BeeState.RESOURCE_PREPARATION_FAILED);
        if (host != null) {
            host.productionMonitor().observe(index, spec.outputs(), ResourceProductionSnapshot.State.PREPARATION_FAILED,
                    tile.getLevel().getGameTime());
        }
        if (firstFailure) {
            LogThrottle.warn("apiary_resource_preparation",
                    "扩展资源准备失败，位置 {}，槽位 {}，蜂种 {}",
                    tile.getBlockPos(), index, type, failure);
        }
    }
}
