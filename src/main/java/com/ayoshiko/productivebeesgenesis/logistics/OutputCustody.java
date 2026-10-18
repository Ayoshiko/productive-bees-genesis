package com.ayoshiko.productivebeesgenesis.logistics;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.UUID;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 普通输出的单请求保管。返回值是已交给目标或本保管器的数量，不是假定的目标接收量。
 * 调用方必须扣除返回的交接量；未知量只存在本记录中，不能作为库存自动重试。
 * 服务器线程独占；组件负责保存与升级交接。跨文件断电原子性不在此类保证范围内。
 */
public final class OutputCustody {
    private final Runnable changed;
    private UUID sequence;
    private ItemStack item = ItemStack.EMPTY;
    private FluidStack fluid = FluidStack.EMPTY;
    private long amount;
    private String destination = "";
    private String failure = "";
    private boolean executing;
    private boolean resolved;
    private CompoundTag invalid;

    public OutputCustody(Runnable changed) { this.changed = changed; }

    public static OutputCustody of(Object host) {
        if (host instanceof TileEntityMekanism tile) {
            for (var component : tile.getComponents()) {
                if (component instanceof GenesisTileComponentEjector ejector) return ejector.outputCustody();
            }
        }
        throw new IllegalStateException("Output owner has no custody component");
    }

    public boolean blocked() { return executing || sequence != null || invalid != null; }
    /** 成功交付通常不需恢复 ID；执行中保存／查询和异常保管仍得到同一个稳定身份。 */
    public UUID sequence() {
        if (sequence == null && executing && amount > 0) sequence = UUID.randomUUID();
        return sequence;
    }
    public long amount() { return amount; }
    public String failure() { return failure; }
    public String destination() { return destination; }

    /** stack 仍由调用方持有；只交接返回的数量，零表示完全没有执行。 */
    public int handoffItem(ItemStack stack, String target, LongSupplier insert) {
        if (blocked() || stack.isEmpty()) return 0;
        return (int) handoff(stack.copyWithCount(1), FluidStack.EMPTY, stack.getCount(), target, insert);
    }

    public long handoffItem(ItemStack template, long requested, String target, LongSupplier insert) {
        if (blocked() || template.isEmpty() || requested <= 0) return 0;
        return handoff(template.copyWithCount(1), FluidStack.EMPTY, requested, target, insert);
    }

    public long handoffFluid(FluidStack template, long requested, String target, LongSupplier insert) {
        if (blocked() || template.isEmpty() || requested <= 0) return 0;
        return handoff(ItemStack.EMPTY, template.copyWithAmount(1), requested, target, insert);
    }

    private long handoff(ItemStack template, FluidStack fluidTemplate, long requested,
            String target, LongSupplier insert) {
        item = template;
        fluid = fluidTemplate;
        amount = requested;
        destination = target;
        failure = "";
        resolved = false;
        executing = true;
        changed.run();
        try {
            long accepted = insert.getAsLong();
            if (accepted < 0 || accepted > requested) throw new IllegalStateException("Invalid output receipt: " + accepted);
            clear();
            return accepted;
        } catch (RuntimeException | LinkageError exception) {
            sequence();
            failure = exception.getClass().getSimpleName() + ": " + String.valueOf(exception.getMessage());
            if (failure.length() > 512) failure = failure.substring(0, 512);
            changed.run();
            ProductiveBeesGenesis.LOGGER.error("Output suspended: request={}, target={}, amount={}",
                    sequence, destination, amount, exception);
            // 外部可能已写入；把整份请求从原库存移到未决记录，不退回、不再次调用接收方。
            return requested;
        } finally {
            executing = false;
        }
    }

    /** 已抽取但尚未送出的已知余量。回填不足时由本记录保管。 */
    public int quarantineItem(ItemStack stack, String target, RuntimeException error) {
        return handoffItem(stack, target, () -> { throw error; });
    }

    public void retainItem(ItemStack stack, String reason) {
        if (stack.isEmpty()) return;
        if (blocked()) throw new IllegalStateException("Output custody already occupied");
        sequence = UUID.randomUUID();
        item = stack.copyWithCount(1);
        fluid = FluidStack.EMPTY;
        amount = stack.getCount();
        destination = reason;
        failure = "";
        resolved = true;
        changed.run();
    }

    public void retainFluid(FluidStack template, long retained, String reason) {
        if (retained <= 0) return;
        if (blocked()) throw new IllegalStateException("Output custody already occupied");
        sequence = UUID.randomUUID();
        item = ItemStack.EMPTY;
        fluid = template.copyWithAmount(1);
        amount = retained;
        destination = reason;
        failure = "";
        resolved = true;
        changed.run();
    }

    /** 管理员已在两端核验实际接收量；剩余仍留本机，不能由命令直接发给玩家或生成掉落。 */
    public void resolve(UUID expected, long accepted) {
        if (executing || invalid != null || sequence == null || !sequence.equals(expected)
                || resolved || accepted < 0 || accepted > amount) {
            throw new IllegalStateException("Stale or invalid output resolution");
        }
        amount -= accepted;
        resolved = true;
        failure = "";
        if (amount == 0) clear();
        changed.run();
    }

    public ItemStack recoveredItem() {
        return invalid == null && resolved && !item.isEmpty() ? item.copyWithCount((int) Math.min(Integer.MAX_VALUE, amount)) : ItemStack.EMPTY;
    }

    public FluidStack recoveredFluid() {
        return invalid == null && resolved && !fluid.isEmpty() ? fluid.copyWithAmount((int) Math.min(Integer.MAX_VALUE, amount)) : FluidStack.EMPTY;
    }

    /** 已知余量尝试回到本机；回填执行抛错后同样转为未知，不反复补偿。 */
    public void recoverItem(java.util.function.ToIntFunction<ItemStack> insert) {
        ItemStack offered = recoveredItem();
        if (offered.isEmpty()) return;
        try { consumeRecovered(insert.applyAsInt(offered)); }
        catch (RuntimeException error) { resolved = false; failure = error.toString(); changed.run(); }
    }

    public void recoverFluid(java.util.function.ToIntFunction<FluidStack> insert) {
        FluidStack offered = recoveredFluid();
        if (offered.isEmpty()) return;
        try { consumeRecovered(insert.applyAsInt(offered)); }
        catch (RuntimeException error) { resolved = false; failure = error.toString(); changed.run(); }
    }

    public void retryDecode(HolderLookup.Provider provider) {
        if (invalid != null) load(provider, invalid.copy());
    }

    public void consumeRecovered(long consumed) {
        if (!resolved || consumed < 0 || consumed > amount) throw new IllegalStateException("Invalid recovered debit");
        amount -= consumed;
        if (amount == 0) clear();
        changed.run();
    }

    private void clear() {
        sequence = null;
        item = ItemStack.EMPTY;
        fluid = FluidStack.EMPTY;
        amount = 0;
        failure = "";
        destination = "";
        resolved = false;
        changed.run();
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        if (invalid != null) return invalid.copy();
        var tag = new CompoundTag();
        if (sequence() == null) return tag;
        tag.putInt("version", 1);
        tag.putUUID("sequence", sequence);
        tag.putLong("amount", amount);
        tag.putString("destination", destination);
        tag.putString("failure", failure);
        tag.putBoolean("resolved", resolved);
        if (!item.isEmpty()) tag.put("item", item.save(provider));
        if (!fluid.isEmpty()) tag.put("fluid", fluid.save(provider));
        return tag;
    }

    /** 外层字段类型错误时保留原始标签，防止 getCompound 将其隐式替换为空记录。 */
    public void loadRaw(HolderLookup.Provider provider, Tag raw) {
        if (raw == null || raw instanceof CompoundTag) {
            load(provider, raw == null ? new CompoundTag() : (CompoundTag) raw);
        } else {
            CompoundTag preserved = new CompoundTag();
            preserved.put("invalid_data", raw.copy());
            load(provider, preserved);
        }
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        invalid = null;
        clear();
        if (tag.isEmpty()) return;
        try {
            if (!Set.of("version", "sequence", "amount", "destination", "failure", "resolved", "item", "fluid")
                    .containsAll(tag.getAllKeys()) || !tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1
                    || !tag.hasUUID("sequence") || !tag.contains("amount", Tag.TAG_LONG) || tag.getLong("amount") <= 0
                    || !tag.contains("destination", Tag.TAG_STRING) || !tag.contains("failure", Tag.TAG_STRING)
                    || !tag.contains("resolved", Tag.TAG_BYTE) || tag.getByte("resolved") < 0 || tag.getByte("resolved") > 1
                    || tag.contains("item") == tag.contains("fluid")
                    || tag.contains("item") && !tag.contains("item", Tag.TAG_COMPOUND)
                    || tag.contains("fluid") && !tag.contains("fluid", Tag.TAG_COMPOUND)) {
                throw new IllegalArgumentException("Invalid custody header");
            }
            var loadedItem = tag.contains("item") ? ItemStack.parse(provider, tag.getCompound("item")).orElseThrow() : ItemStack.EMPTY;
            var loadedFluid = tag.contains("fluid") ? FluidStack.parseOptional(provider, tag.getCompound("fluid")) : FluidStack.EMPTY;
            if (loadedItem.isEmpty() == loadedFluid.isEmpty()) {
                throw new IllegalArgumentException("Invalid custody asset");
            }
            item = loadedItem;
            fluid = loadedFluid;
            amount = tag.getLong("amount");
            sequence = tag.getUUID("sequence");
            destination = tag.getString("destination");
            failure = tag.getString("failure");
            resolved = tag.getBoolean("resolved");
        } catch (RuntimeException exception) {
            invalid = tag.copy();
            failure = "Invalid output custody; original data preserved";
        }
    }
}
