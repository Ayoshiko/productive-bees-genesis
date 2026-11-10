package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.function.LongConsumer;

/** 已付流体先交入本地罐，再按原路由有界排空；冻结与遗留余量共用真实刻预算。 */
final class ApiaryFluidOutput {
    // 60 槽创造蜂箱的常规三刻批次为 15 满罐；更大的批次保留余量分刻处理。
    static final int MAX_PRESSURE_DRAINS_PER_TICK = 16;

    enum Result {
        COMPLETE,
        BLOCKED,
        DEFERRED
    }

    private final TileEntityMekApiary tile;
    private long pressureDrainTick = Long.MIN_VALUE;
    private int pressureDrains;
    private boolean pressureDrainStopped;
    private boolean flushing;

    ApiaryFluidOutput(TileEntityMekApiary tile) {
        this.tile = tile;
    }

    Result flush(FluidStack template, long amount, boolean directAeOutputEnabled, LongConsumer debit) {
        if (amount <= 0) {
            return Result.COMPLETE;
        }
        OutputCustody custody = OutputCustody.of(tile);
        if (flushing || custody.blocked() || tile.hasInvalidFluidData()
                || template == null || template.isEmpty()) {
            return Result.BLOCKED;
        }
        flushing = true;
        try {
            long remaining = amount;
            if (directAeOutputEnabled) {
                long handed = tile.pushGeneratedFluidToAe(template, remaining);
                if (handed < 0 || handed > remaining) {
                    throw new IllegalStateException("Invalid generated-fluid receipt: " + handed);
                }
                if (handed > 0) {
                    debit.accept(handed);
                    remaining -= handed;
                }
            }
            IExtendedFluidTank tank = tile.getFluidTank();
            while (remaining > 0 && !custody.blocked()) {
                int offeredAmount = (int) Math.min(remaining,
                        Math.max(0L, (long) tank.getCapacity() - tank.getFluidAmount()));
                if (offeredAmount > 0) {
                    FluidStack offered = template.copyWithAmount(offeredAmount);
                    long handed = custody.handoffFluid(offered, offeredAmount, "apiary-tank", () ->
                            offeredAmount - tank.insert(offered.copy(), Action.EXECUTE,
                                    AutomationType.INTERNAL).getAmount());
                    if (handed > 0) {
                        // 对外排空之前先扣原所有者；后续异常不能把已交入罐的前缀再次输出。
                        debit.accept(handed);
                        remaining -= handed;
                    }
                }
                if (remaining == 0 || custody.blocked() || directAeOutputEnabled) {
                    break;
                }
                Result drain = drainLocalTank(tank, custody);
                if (drain != Result.COMPLETE) {
                    return drain;
                }
            }
            return remaining == 0 && !custody.blocked() ? Result.COMPLETE : Result.BLOCKED;
        } finally {
            flushing = false;
        }
    }

    private Result drainLocalTank(IExtendedFluidTank tank, OutputCustody custody) {
        var level = tile.getLevel();
        if (level == null || level.isClientSide || tank.isEmpty()) {
            return Result.BLOCKED;
        }
        long gameTick = level.getGameTime();
        if (gameTick != pressureDrainTick) {
            pressureDrainTick = gameTick;
            pressureDrains = 0;
            pressureDrainStopped = false;
        }
        if (pressureDrainStopped) {
            return Result.BLOCKED;
        }
        if (pressureDrains >= MAX_PRESSURE_DRAINS_PER_TICK) {
            return Result.DEFERRED;
        }
        pressureDrains++;
        // 无进展、部分接收或异常均终止本刻续传；不能因调用方再次刷新而重领额度。
        pressureDrainStopped = true;
        tile.productivebeesgenesis$onLocalFluidOutputCommitted();
        if (custody.blocked() || !tank.isEmpty()) {
            return Result.BLOCKED;
        }
        pressureDrainStopped = false;
        return Result.COMPLETE;
    }
}
