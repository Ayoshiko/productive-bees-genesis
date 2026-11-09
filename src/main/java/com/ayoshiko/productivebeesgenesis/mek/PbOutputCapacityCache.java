package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.common.recipe.CentrifugeRecipe;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.Level;

/** 单车道的只读产出上界；输入数量影响转换余料，容量和接收结果始终实时核验。 */
final class PbOutputCapacityCache {
    private static final int MAX_PREPARED_COUNTS = 32;

    private final PbRecipeCompleter owner;
    private final PbRecipeContext context;
    private final Int2ObjectLinkedOpenHashMap<PbRecipeCompleter> prepared = new Int2ObjectLinkedOpenHashMap<>();
    private CentrifugeRecipe recipe;
    private Level level;
    private HolderLookup.Provider provider;
    private int productivity;
    private int stability;
    private int upgrades;
    private long revision;

    PbOutputCapacityCache(PbRecipeCompleter owner, PbRecipeContext context) {
        this.owner = owner;
        this.context = context;
    }

    boolean fits(CentrifugeRecipe currentRecipe, int lane, int inputs) {
        Level currentLevel = context.level();
        HolderLookup.Provider currentProvider = currentLevel == null ? null : currentLevel.registryAccess();
        int currentProductivity = Math.max(1, context.productivityModifier());
        int currentStability = Float.floatToIntBits(context.stabilityBonus());
        int currentUpgrades = (context.suppressesUselessByproducts() ? 1 : 0)
                | (context.productivebeesgenesis$hasEssenceConversionUpgrade() ? 2 : 0)
                | (context.productivebeesgenesis$hasRawOreSmeltingUpgrade() ? 4 : 0);
        long currentRevision = ProductiveBeesGenesis.RECIPE_VERSION.get();
        if (recipe != currentRecipe || level != currentLevel || provider != currentProvider
                || productivity != currentProductivity || stability != currentStability
                || upgrades != currentUpgrades || revision != currentRevision) {
            prepared.clear();
            recipe = currentRecipe;
            level = currentLevel;
            provider = currentProvider;
            productivity = currentProductivity;
            stability = currentStability;
            upgrades = currentUpgrades;
            revision = currentRevision;
        }
        PbRecipeCompleter batch = prepared.getAndMoveToLast(inputs);
        if (batch == null) {
            batch = owner.prepare(currentRecipe, inputs, 0, true);
            if (prepared.size() >= MAX_PREPARED_COUNTS) prepared.removeFirst();
            prepared.put(inputs, batch);
        }
        return batch.fitsPrepared(lane, inputs);
    }
}
