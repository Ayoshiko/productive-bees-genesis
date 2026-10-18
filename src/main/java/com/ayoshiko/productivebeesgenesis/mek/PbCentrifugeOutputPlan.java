package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.resource.ResourceCentrifugeProcessor;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import net.minecraft.nbt.CompoundTag;

/** 原离心聚合器的准备／交接边界；账户只持有资源，剩余物品和流体由原 pending 独占。 */
final class PbCentrifugeOutputPlan implements ResourceCentrifugeProcessor.LocalPlan {
    private final PbRecipeCompleter owner;
    private final CentrifugeRecipe recipe;
    private final int lane;

    PbCentrifugeOutputPlan(PbRecipeCompleter owner, CentrifugeRecipe recipe, int lane) {
        this.owner = owner;
        this.recipe = recipe;
        this.lane = lane;
    }

    @Override public int maximumInputs(int limit) {
        if (owner.hasPendingOutputs() || owner.invalidPending()) return 0;
        if (limit <= 0) return 0;
        if (fits(limit)) return limit;
        if (limit == 1 || !fits(1)) return 0;
        int low = 1, high = limit - 1;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (fits(middle)) low = middle; else high = middle - 1;
        }
        return low;
    }

    private boolean fits(int inputs) { return owner.fitsMaximum(recipe, lane, inputs); }

    @Override public ResourceCentrifugeProcessor.LocalBatch prepare(int inputs, long seed) {
        // 物品和资源使用分离的随机流，不把两个独立概率意外绑定到相同随机序列。
        var batch = owner.prepare(recipe, inputs, seed ^ 0x9E3779B97F4A7C15L, false);
        // 上界和实际结果共用算法；提交前仍按当前槽位重验。
        if (!batch.fitsPrepared(lane, inputs)) throw new IllegalStateException("Local centrifuge output capacity changed");
        return new ResourceCentrifugeProcessor.LocalBatch() {
            private boolean committed;

            @Override public CompoundTag snapshot() {
                var saved = batch.saveCommittedPending(owner.getContext().level().registryAccess());
                return saved == null ? new CompoundTag() : saved;
            }

            @Override public void commit() {
                if (committed) throw new IllegalStateException("Local batch already handed off");
                committed = true;
                owner.acceptPrepared(batch);
                owner.flushPendingPbOutputs(lane);
                if (OutputCustody.of(owner.getContext()).blocked())
                    throw new IllegalStateException("Combined centrifuge output outcome uncertain");
            }
        };
    }
}
