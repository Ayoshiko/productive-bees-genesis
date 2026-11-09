package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** 资源批次向现有已付产物保管器的一次交接；后续路由、满载和恢复仍只有一个实现。 */
final class ApiaryRoutedOutput {
    private final TileEntityMekApiary tile;
    private final BeeProduceProcessor.Generated generated;
    private final ApiaryBatchUpgradeSnapshot upgrades;
    private boolean committed;

    ApiaryRoutedOutput(TileEntityMekApiary tile, BeeProduceProcessor.Generated generated, ApiaryBatchUpgradeSnapshot upgrades) {
        this.tile = tile;
        this.generated = generated;
        this.upgrades = upgrades;
    }

    CompoundTag context(HolderLookup.Provider provider) {
        var tag = new CompoundTag();
        // 缓存只复用精确相同的审计文本；保留原字段、完整组件、数量和预算校验。
        tag.putString("items_snbt", tile.getProduceProcessor().outputEvidence.encode(generated.items(), provider));
        if (generated.fluidAmount() > 0) {
            tag.put("fluid", generated.fluid().copyWithAmount(1).save(provider));
            tag.putLong("fluid_amount", generated.fluidAmount());
        }
        return tag;
    }

    BeeProduceProcessor.Generated generated() {
        return generated;
    }

    void commit() {
        var processor = tile.getProduceProcessor();
        if (committed || processor.production.suspended() || processor.production.hasFrozen())
            throw new IllegalStateException("Previous apiary output still owned");
        committed = true;
        processor.production.freeze(generated);
        processor.flushFrozen(upgrades);
        if (OutputCustody.of(tile).blocked()) throw new IllegalStateException("Resource apiary output outcome uncertain");
    }
}
