package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** 管理员命令在服务器线程读取的有界生产快照，不改变资产或暴露蜜蜂原始 NBT。 */
public record ApiaryOutputDiagnostics(int pendingCycles, int frozenItemKinds, long frozenFluidAmount,
        long pendingFluidAmount, int bufferGroups, int bufferLimit, boolean invalidBuffer,
        int occupiedOutputSlots, int outputSlots, int tankAmount, int tankCapacity, List<Slot> slots,
        List<Retained> retained) {
    public record Slot(int index, BeeState state, ResourceLocation bee, int paidCycles, ResourceLocation paidBee) {}
    public record Retained(int sourceSlot, ResourceLocation bee, int cycles) {}

    public ApiaryOutputDiagnostics {
        slots = List.copyOf(slots);
        retained = List.copyOf(retained);
    }

    public static ApiaryOutputDiagnostics capture(TileEntityMekApiary tile) {
        var production = tile.productionState();
        var buffer = tile.getOutputBuffer();
        var bees = tile.getBeeSlots();
        var slots = new ArrayList<Slot>();
        for (int i = 0; i < bees.length; i++) {
            var bee = bees[i];
            int paid = production.counts[i];
            if (!bee.isEmpty() || paid > 0) {
                slots.add(new Slot(i, bee.getState(), BeeNbtHelper.resolveBeeTypeKey(bee.getBeeData()),
                        paid, BeeNbtHelper.resolveBeeTypeKey(production.bees[i].getBeeData())));
            }
        }
        var outputs = tile.getOutputSlots();
        int occupied = 0;
        for (var output : outputs) {
            if (!output.isEmpty()) occupied++;
        }
        var tank = tile.getFluidTank();
        var retained = new ArrayList<Retained>();
        var held = production.retained();
        if (held != null) {
            for (int i = 0; i < held.counts.length; i++) {
                if (held.counts[i] > 0) {
                    retained.add(new Retained(held.sourceSlot(i), BeeNbtHelper.resolveBeeTypeKey(held.bees[i].getBeeData()),
                            held.counts[i]));
                }
            }
        }
        return new ApiaryOutputDiagnostics(production.total(), production.items().size(), production.fluidAmount(),
                tile.getPendingHoneyFluidAmount(), buffer.getBufferedGroupCount(), ApiaryOutputBuffer.MAX_BUFFER_GROUPS,
                buffer.hasInvalidData(), occupied, outputs.size(), tank.getFluidAmount(), tank.getCapacity(), slots, retained);
    }
}
