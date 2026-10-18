package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

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
        var items = new ListTag();
        for (var stack : generated.items()) {
            if (stack.isEmpty()) continue;
            var item = (CompoundTag) stack.copyWithCount(1).save(provider);
            item.putInt("generated_count", stack.getCount());
            items.add(item);
        }
        // 可读 SNBT 保留完整组件和数量，避免逐条 NBT 对象开销占满账户的审计上下文预算。
        tag.putString("items_snbt", items.toString());
        if (generated.fluidAmount() > 0) {
            tag.put("fluid", generated.fluid().copyWithAmount(1).save(provider));
            tag.putLong("fluid_amount", generated.fluidAmount());
        }
        return tag;
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
