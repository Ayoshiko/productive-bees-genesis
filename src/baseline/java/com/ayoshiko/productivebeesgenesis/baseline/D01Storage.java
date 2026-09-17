package com.ayoshiko.productivebeesgenesis.baseline;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.core.HolderLookup;

/** 统计真实 AE2 存储元件调用，不用丢弃产物的黑洞替代库存。 */
final class D01Storage implements MEStorage {
    private final MEStorage items = StorageCells.getCellInventory(AEItems.ITEM_CELL_256K.stack(), null);
    private final MEStorage fluids = StorageCells.getCellInventory(AEItems.FLUID_CELL_256K.stack(), null);
    private final HolderLookup.Provider registries;
    private long insertCalls;
    private long extractCalls;
    private long enumerationCalls;
    private long insertedItems;
    private long extractedItems;
    private long insertedFluidMb;
    private long extractedFluidMb;

    D01Storage(HolderLookup.Provider registries) {
        this.registries = registries;
        if (items == null || fluids == null) throw new IllegalStateException("AE2 cell handlers unavailable");
    }

    @Override
    public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
        insertCalls++;
        long accepted = storage(key).insert(key, amount, mode, source);
        if (mode == Actionable.MODULATE) {
            if (key instanceof AEFluidKey) insertedFluidMb += accepted;
            else insertedItems += accepted;
        }
        return accepted;
    }

    @Override
    public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
        extractCalls++;
        long accepted = storage(key).extract(key, amount, mode, source);
        if (mode == Actionable.MODULATE) {
            if (key instanceof AEFluidKey) extractedFluidMb += accepted;
            else extractedItems += accepted;
        }
        return accepted;
    }

    @Override
    public void getAvailableStacks(KeyCounter out) {
        enumerationCalls++;
        items.getAvailableStacks(out);
        fluids.getAvailableStacks(out);
    }

    @Override
    public Component getDescription() { return Component.literal("D01 real 256k cells"); }

    void resetCounters() {
        insertCalls = extractCalls = enumerationCalls = 0;
        insertedItems = extractedItems = insertedFluidMb = extractedFluidMb = 0;
    }

    boolean hasMeasuredProduction() { return insertedItems > 0 && extractedItems > 0 && insertedFluidMb > 0; }

    JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.addProperty("insertCalls", insertCalls);
        result.addProperty("extractCalls", extractCalls);
        result.addProperty("enumerationCalls", enumerationCalls);
        result.addProperty("insertedItems", insertedItems);
        result.addProperty("extractedItems", extractedItems);
        result.addProperty("insertedFluidMb", insertedFluidMb);
        result.addProperty("extractedFluidMb", extractedFluidMb);
        KeyCounter inventory = new KeyCounter();
        items.getAvailableStacks(inventory);
        fluids.getAvailableStacks(inventory);
        JsonObject contents = new JsonObject();
        for (var entry : inventory) contents.addProperty(entry.getKey().toTagGeneric(registries).toString(), entry.getLongValue());
        result.add("contents", contents);
        return result;
    }

    private MEStorage storage(AEKey key) { return key instanceof AEFluidKey ? fluids : items; }
}
