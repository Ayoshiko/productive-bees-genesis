package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicEnergyContainerAccessor;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.BasicInventorySlotAccessor;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.api.recipes.cache.OneInputCachedRecipe;
import mekanism.api.recipes.ingredients.InputIngredient;
import mekanism.api.recipes.inputs.IInputHandler;
import mekanism.api.recipes.outputs.IOutputHandler;
import net.minecraft.world.item.ItemStack;

import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.LongSupplier;

/** 原生熔炼计算与加速保持不变；周期交接异常只保管对应车道，不重跑已扣除的输入。 */
final class CentrifugeSmeltingCachedRecipe
        extends OneInputCachedRecipe<ItemStack, ItemStack, ItemStackToItemStackRecipe> {
    private final PbRecipeProcessor processor;
    private final PbRecipeContext context;
    private final int lane;
    private final Cycle cycle;
    private final IntConsumer progress;
    private boolean enteredPayment;
    private boolean completed;
    private boolean notificationFailed;
    private LongSupplier energyPerTick;

    CentrifugeSmeltingCachedRecipe(ItemStackToItemStackRecipe recipe, BooleanSupplier errors,
            IInputHandler<ItemStack> input, IOutputHandler<ItemStack> output,
            PbRecipeProcessor processor, PbRecipeContext context, int lane, IntConsumer progress) {
        this(recipe, errors, new Cycle(input, output), processor, context, lane, progress);
    }

    private CentrifugeSmeltingCachedRecipe(ItemStackToItemStackRecipe recipe, BooleanSupplier errors,
            Cycle cycle, PbRecipeProcessor processor, PbRecipeContext context, int lane, IntConsumer progress) {
        super(recipe, errors, cycle.inputView, cycle.outputView, recipe::getInput, recipe::getOutput,
                ItemStack::isEmpty, ItemStack::isEmpty);
        this.processor = processor;
        this.context = context;
        this.lane = lane;
        this.cycle = cycle;
        this.progress = progress;
    }

    @Override
    public void process() {
        if (!processor.canProcessSmeltingLane(lane)) return;
        enteredPayment = false;
        completed = false;
        notificationFailed = false;
        try {
            super.process();
            if (!notificationFailed) processor.completeLaneRecovery(lane);
        } catch (RuntimeException | LinkageError failure) {
            resetCache();
            progress.accept(getOperatingTicks());
            if (completed) {
                processor.reportLaneFailure(lane, "熔炼完成通知", failure);
            } else if (enteredPayment) {
                if (!processor.smeltingWork().hasLane(lane)) {
                    processor.smeltingWork().retainUnknownProcess(lane, "processing", failure);
                }
            } else {
                processor.reportLaneFailure(lane, "熔炼准备", failure);
            }
        }
    }

    @Override
    public CentrifugeSmeltingCachedRecipe setOnFinish(Runnable onFinish) {
        super.setOnFinish(() -> {
            try {
                onFinish.run();
            } catch (RuntimeException | LinkageError failure) {
                notificationFailed = true;
                processor.reportLaneFailure(lane, "熔炼完成通知", failure);
            }
        });
        return this;
    }

    @Override
    public CentrifugeSmeltingCachedRecipe setEnergyRequirements(LongSupplier energyPerTick, IEnergyContainer container) {
        this.energyPerTick = energyPerTick;
        super.setEnergyRequirements(energyPerTick, container);
        return this;
    }

    @Override
    protected void useEnergy(int operations) {
        if (BatchEnergyLedger.active() != null) {
            enteredPayment = true;
            super.useEnergy(operations);
            return;
        }
        var container = context.energyContainer();
        long before = ((BasicEnergyContainerAccessor) container).productivebeesgenesis$getStored();
        enteredPayment = true;
        try {
            super.useEnergy(operations);
        } catch (RuntimeException | LinkageError failure) {
            long requested = MekCentrifugeEnergyScaling.batchEnergyCost(energyPerTick.getAsLong(), operations, 1);
            long charged = before - ((BasicEnergyContainerAccessor) container).productivebeesgenesis$getStored();
            if (charged < 0 || charged > requested) {
                processor.smeltingWork().retainEnergy(requested, true, failure);
            } else if (charged < requested) {
                processor.smeltingWork().retainEnergy(requested - charged, false, failure);
            } else {
                notificationFailed = true;
                processor.reportLaneFailure(lane, "熔炼能量扣账通知已结算", failure);
            }
        }
    }

    @Override
    protected void finishProcessing(int operations) {
        enteredPayment = true;
        ItemStack input = cycle.recipeInput;
        ItemStack output = cycle.recipeOutput;
        int inputs = Math.multiplyExact(input.getCount(), operations);
        int outputs = Math.multiplyExact(output.getCount(), operations);
        var inputSlot = context.inputSlot(lane);
        int beforeInputs = ((BasicInventorySlotAccessor) inputSlot).productivebeesgenesis$getCurrent().getCount();
        int consumed;
        try {
            consumed = inputSlot.shrinkStack(inputs, Action.EXECUTE);
        } catch (RuntimeException | LinkageError failure) {
            try {
                consumed = SmeltingWorkState.removed(beforeInputs,
                        ((BasicInventorySlotAccessor) inputSlot).productivebeesgenesis$getCurrent(), input);
                if (consumed < 0 || consumed > inputs) throw new IllegalStateException("Smelting input debit cannot be reconciled");
            } catch (RuntimeException | LinkageError unknown) {
                failure.addSuppressed(unknown);
                processor.smeltingWork().retain(lane, input, inputs, output, outputs, true, "input", failure);
                throw failure;
            }
            if (consumed < inputs) {
                processor.smeltingWork().retain(lane, input, inputs - consumed, output, outputs, false, "input", failure);
                throw failure;
            }
            processor.reportLaneFailure(lane, "熔炼输入扣账通知已结算", failure);
        }
        if (consumed < inputs) {
            processor.smeltingWork().retain(lane, input, inputs - consumed, output, outputs, false, "input",
                    new IllegalStateException("Smelting input was only partly debited"));
            return;
        }
        var outputSlot = context.primaryOutputSlot(lane);
        int beforeOutputs = ((BasicInventorySlotAccessor) outputSlot).productivebeesgenesis$getCurrent().getCount();
        long accepted;
        try {
            ItemStack remainder = outputSlot.insertItem(output.copyWithCount(outputs), Action.EXECUTE,
                    AutomationType.INTERNAL);
            accepted = outputs - remainder.getCount();
        } catch (RuntimeException | LinkageError failure) {
            try {
                accepted = SmeltingWorkState.accepted(beforeOutputs,
                        ((BasicInventorySlotAccessor) outputSlot).productivebeesgenesis$getCurrent(), output);
                if (accepted < 0 || accepted > outputs) throw new IllegalStateException("Smelting output receipt cannot be reconciled");
            } catch (RuntimeException | LinkageError unknown) {
                failure.addSuppressed(unknown);
                processor.smeltingWork().retain(lane, input, 0, output, outputs, true, "output", failure);
                throw failure;
            }
            if (accepted < outputs) {
                processor.smeltingWork().retain(lane, input, 0, output, outputs - accepted, false, "output", failure);
                throw failure;
            }
            processor.reportLaneFailure(lane, "熔炼产物接收通知已结算", failure);
        }
        if (accepted < outputs) {
            processor.smeltingWork().retain(lane, input, 0, output, outputs - accepted, false, "output",
                    new IllegalStateException("Smelting output was only partly accepted"));
            return;
        }
        completed = true;
    }

    /** 原生计算已返回的模板供完成点使用，不增加第二次配方查询或逐虚拟刻快照。 */
    private static final class Cycle {
        final IInputHandler<ItemStack> input;
        final IOutputHandler<ItemStack> output;
        final IInputHandler<ItemStack> inputView = new IInputHandler<>() {
            @Override
            public ItemStack getInput() {
                return input.getInput();
            }

            @Override
            public ItemStack getRecipeInput(InputIngredient<ItemStack> ingredient) {
                recipeInput = input.getRecipeInput(ingredient);
                return recipeInput;
            }

            @Override
            public void use(ItemStack stack, int operations) {
                input.use(stack, operations);
            }

            @Override
            public void calculateOperationsCanSupport(CachedRecipe.OperationTracker tracker, ItemStack stack, int decrement) {
                input.calculateOperationsCanSupport(tracker, stack, decrement);
            }
        };
        final IOutputHandler<ItemStack> outputView = new IOutputHandler<>() {
            @Override
            public void handleOutput(ItemStack stack, int operations) {
                output.handleOutput(stack, operations);
            }

            @Override
            public void calculateOperationsCanSupport(CachedRecipe.OperationTracker tracker, ItemStack stack) {
                recipeOutput = stack;
                output.calculateOperationsCanSupport(tracker, stack);
            }
        };
        ItemStack recipeInput = ItemStack.EMPTY;
        ItemStack recipeOutput = ItemStack.EMPTY;

        Cycle(IInputHandler<ItemStack> input, IOutputHandler<ItemStack> output) {
            this.input = input;
            this.output = output;
        }
    }
}
