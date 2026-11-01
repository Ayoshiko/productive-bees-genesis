package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.CentrifugeBlockEntityAccessor;
import com.ayoshiko.productivebeesgenesis.util.PbDataComponents;
import cy.jdkdigital.productivebees.ProductiveBeesConfig;
import cy.jdkdigital.productivebees.common.block.Centrifuge;
import cy.jdkdigital.productivebees.common.block.entity.CentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.HeatedCentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.PoweredCentrifugeBlockEntity;
import cy.jdkdigital.productivebees.common.block.entity.FluidTankBlockEntity;
import com.ayoshiko.productivebeesgenesis.port.ForgePbUpgrades;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandlerModifiable;

import java.util.List;

/** 仅接管扩展蜜脾输入；PB 原物品配方和流体分享继续走上游入口。 */
public final class PbResourceCentrifuge {
    public static IItemHandlerModifiable inventory(CentrifugeBlockEntity tile){return ((CentrifugeBlockEntityAccessor)tile).productivebeesgenesis$getInventoryHandler().orElseThrow(()->new IllegalStateException("Missing PB inventory"));}
	private PbResourceCentrifuge() {}

	public static boolean ready(CentrifugeBlockEntity entity) {
		return entity instanceof PbResourceCentrifugeBridge bridge
				&& (!(entity instanceof PoweredCentrifugeBlockEntity) || bridge.productivebeesgenesis$resourcePowerReady());
	}

	public static boolean managed(CentrifugeBlockEntity entity, ItemStack stack) {
		return entity.getLevel() instanceof ServerLevel level
				&& (ResourceRuntime.recipes(level.getServer()).centrifuge(stack) != null
				|| ResourceCentrifugeProcessor.hasPendingInput(entity, stack));
	}

	public static boolean accepts(CentrifugeBlockEntity entity, ItemStack stack) {
		if (!ready(entity) || (ResourceCentrifugeRecipe.unitsForItem(stack) == 4
				&& !(entity instanceof HeatedCentrifugeBlockEntity))) return false;
        return true;
	}

	/** 返回 true 表示已接管该 tick，外层电力 tick 也不得再对同一段工作扣费。 */
	public static boolean tick(CentrifugeBlockEntity entity) {
		if (!(entity.getLevel() instanceof ServerLevel level)) return false;
		var access = new Access(entity);
		var result = ResourceCentrifugeProcessor.tick(access, 1);
		if (result == ResourceCentrifugeProcessor.Result.UNMANAGED) return false;
		boolean working = result == ResourceCentrifugeProcessor.Result.WORKING;
		var state = entity.getBlockState();
		if (state.getValue(Centrifuge.RUNNING) != working) {
			level.setBlockAndUpdate(entity.getBlockPos(), state.setValue(Centrifuge.RUNNING, working));
		}
		// 旧蜂蜜的分享逻辑不参与新资源账本，也不生成任何资源掉落实体。
		FluidTankBlockEntity.tick(level, entity.getBlockPos(), entity.getBlockState(), entity);
		return true;
	}

	private record Access(CentrifugeBlockEntity tile) implements ResourceCentrifugeProcessor.Access {
		@Override public BlockEntity entity() { return tile; }
		@Override public int lane() { return 0; }
		@Override public ItemStack input() { return inventory(tile).getStackInSlot(1); }
		@Override public ItemStack consume(int count, boolean simulate) {
			var input = input();
			int extracted = Math.min(count, input.getCount());
			var result = input.copyWithCount(extracted);
			if (!simulate) ((IItemHandlerModifiable) inventory(tile)).setStackInSlot(1, input.copyWithCount(input.getCount() - extracted));
			return result;
		}
		@Override public long energy() { return tile instanceof PoweredCentrifugeBlockEntity powered ? powered.energyHandler.orElseThrow(()->new IllegalStateException("Missing PB energy")).getEnergyStored() : Long.MAX_VALUE; }
		@Override public long energyPerTick() {
			if (!(tile instanceof PoweredCentrifugeBlockEntity)) return 0;
			double cost = ProductiveBeesConfig.GENERAL.centrifugePowerUse.get()
					* ((PbResourceCentrifugeBridge) tile).productivebeesgenesis$resourceEnergyModifier();
			if (!Double.isFinite(cost) || cost < 0 || cost > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid PB energy cost");
			return (long) cost;
		}
		@Override public long consumeEnergy(long amount, boolean simulate) {
			if (!(tile instanceof PoweredCentrifugeBlockEntity powered)) return amount == 0 ? 0 : -1;
			return amount < 0 || amount > Integer.MAX_VALUE ? -1 : powered.energyHandler.orElseThrow(()->new IllegalStateException("Missing PB energy")).extractEnergy((int) amount, simulate);
		}
		@Override public int requiredTicks(int base) {
			double time = base * ((PbResourceCentrifugeBridge) tile).productivebeesgenesis$resourceTimeModifier();
			if (!Double.isFinite(time) || time > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid PB resource time");
			return Math.max(5, (int) time);
		}
		@Override public int parallel() { return 1; }
		@Override public int productivity() { return 1; }
		@Override public double stability() { return Math.min(1, (tile.getUpgradeCount(ForgePbUpgrades.UPGRADE_STABILITY.get()) + 1)
				* com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeConfig.stabilityChanceIncrease()); }
		@Override public boolean canOperate() { return accepts(tile, input()); }
		@Override public void progress(int paid, int required, boolean working) { tile.recipeProgress = required - paid; }
	}
}
