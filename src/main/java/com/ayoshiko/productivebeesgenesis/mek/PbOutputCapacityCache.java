package com.ayoshiko.productivebeesgenesis.mek;

import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;

/** 单车道的只读产出上界与可表示批量；容量和接收结果始终实时核验。 */
final class PbOutputCapacityCache {
    private static final int MAX_PREPARED_COUNTS = 32;

    private final PbRecipeCompleter owner;
    private final Int2ObjectLinkedOpenHashMap<PbRecipeCompleter> prepared = new Int2ObjectLinkedOpenHashMap<>();
    private CentrifugeRecipe recipe;
    private Level level;
    private HolderLookup.Provider provider;
    private int productivity;
    private int stability;
    private int upgrades;
    private long revision;
    private int representableInputs = -1;

    PbOutputCapacityCache(PbRecipeCompleter owner) {
        this.owner = owner;
    }

    boolean fits(CentrifugeRecipe currentRecipe, int lane, int inputs) {
        refresh(currentRecipe);
        PbRecipeCompleter batch = prepare(inputs);
        return batch != null && batch.fitsPrepared(lane, inputs);
    }

    int maximumRepresentableInputs(CentrifugeRecipe currentRecipe) {
        refresh(currentRecipe);
        if (representableInputs >= 0) return representableInputs;
        if (prepare(Integer.MAX_VALUE) != null) {
            representableInputs = Integer.MAX_VALUE;
            return representableInputs;
        }
        int low = 0;
        int high = Integer.MAX_VALUE - 1;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (prepare(middle) != null) low = middle;
            else high = middle - 1;
        }
        representableInputs = low;
        return representableInputs;
    }

    private void refresh(CentrifugeRecipe currentRecipe) {
        PbRecipeCompleter.OutputSettings settings = owner.outputSettings();
        Level currentLevel = settings.level();
        HolderLookup.Provider currentProvider = settings.provider();
        int currentProductivity = settings.productivity();
        int currentStability = Float.floatToIntBits(settings.stability());
        int currentUpgrades = settings.upgrades();
        long currentRevision = settings.revision();
        if (recipe != currentRecipe || level != currentLevel || provider != currentProvider
                || productivity != currentProductivity || stability != currentStability
                || upgrades != currentUpgrades || revision != currentRevision) {
            prepared.clear();
            representableInputs = -1;
            recipe = currentRecipe;
            level = currentLevel;
            provider = currentProvider;
            productivity = currentProductivity;
            stability = currentStability;
            upgrades = currentUpgrades;
            revision = currentRevision;
        }
    }

    private PbRecipeCompleter prepare(int inputs) {
        PbRecipeCompleter batch = prepared.getAndMoveToLast(inputs);
        if (batch == null) {
            try {
                batch = owner.prepare(recipe, inputs, 0, true);
            } catch (ArithmeticException oversizedBatch) {
                // 上界尚未付费或生成资产；数量超界与容量不足一样，交回调用方缩批。
                return null;
            }
            if (prepared.size() >= MAX_PREPARED_COUNTS) prepared.removeFirst();
            prepared.put(inputs, batch);
        }
        return batch;
    }
}
