package com.ayoshiko.productivebeesgenesis.inventory;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.IContentsListener;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** 自有输出罐在外部扣量提交后保留原生回执；通知错误不丢失已抽出的流体。 */
public final class OutputFluidTank extends BasicFluidTank {
    private boolean externalExtraction;
    private boolean notificationFailed;

    public OutputFluidTank(int capacity, BiPredicate<FluidStack, AutomationType> canExtract,
            BiPredicate<FluidStack, AutomationType> canInsert, Predicate<FluidStack> validator,
            IContentsListener listener) {
        super(capacity, canExtract, canInsert, validator, listener);
    }

    @Override
    public FluidStack extract(int amount, Action action, AutomationType automationType) {
        externalExtraction = action.execute() && automationType == AutomationType.EXTERNAL;
        try {
            return super.extract(amount, action, automationType);
        } finally {
            externalExtraction = false;
        }
    }

    /** ME 存储桥已核对身份与可提取位置；保留原来的无分配精确扣量。 */
    public int extractAmount(int amount) {
        externalExtraction = true;
        try {
            return shrinkStack(amount, Action.EXECUTE);
        } finally {
            externalExtraction = false;
        }
    }

    @Override
    public void onContentsChanged() {
        if (!externalExtraction) {
            super.onContentsChanged();
            return;
        }
        try {
            super.onContentsChanged();
            if (notificationFailed) {
                notificationFailed = false;
                ProductiveBeesGenesis.LOGGER.info("流体外部扣量通知已恢复，tank={}", this);
            }
        } catch (RuntimeException | LinkageError failure) {
            if (!notificationFailed) {
                notificationFailed = true;
                ProductiveBeesGenesis.LOGGER.error("流体外部扣量已提交，保留实际抽取回执；通知待恢复，tank={} remaining={}",
                        this, stored.getAmount(), failure);
            }
        }
    }
}
