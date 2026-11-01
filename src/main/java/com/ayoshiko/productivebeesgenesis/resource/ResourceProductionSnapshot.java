package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.NbtAccounter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 只读生产监控；数量是窗口内实际结算量，不是可提取资产。 */
public record ResourceProductionSnapshot(long windowMillis, List<Entry> entries) {
    public static final ResourceProductionSnapshot EMPTY = new ResourceProductionSnapshot(5000, List.of());
    public enum State { PRODUCING, WAITING_OUTPUT, WAITING_WORK, RECENT }
    public record Entry(ResourceKey key, BigInteger produced, State state) {
        public Entry {
            if (key == null || produced == null || produced.signum() < 0
                    || produced.compareTo(ResourceExactAmounts.MAX_VALUE) > 0 || state == null) {
                throw new IllegalArgumentException("Invalid production display");
            }
        }
        public BigInteger perSecond(long windowMillis) {
            return produced.multiply(BigInteger.valueOf(1000)).divide(BigInteger.valueOf(windowMillis));
        }
        public String rate(long windowMillis) {
            return new java.math.BigDecimal(produced).multiply(java.math.BigDecimal.valueOf(1000))
                    .divide(java.math.BigDecimal.valueOf(windowMillis), 2, java.math.RoundingMode.DOWN)
                    .stripTrailingZeros().toPlainString();
        }
    }
    public ResourceProductionSnapshot {
        if (windowMillis < 1 || windowMillis > 5000 || entries.size() > ResourceAmounts.MAX_KEYS) {
            throw new IllegalArgumentException("Invalid production window");
        }
        entries = List.copyOf(entries);
        var keys = new HashSet<ResourceKey>();
        for (var entry : entries) if (!keys.add(entry.key())) throw new IllegalArgumentException("Duplicate production identity");
    }
    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarLong(windowMillis);
        buffer.writeVarInt(entries.size());
        for (var entry : entries) {
            buffer.writeNbt(entry.key().save());
            buffer.writeUtf(entry.produced().toString(), ResourceExactAmounts.MAX_DECIMAL_DIGITS);
            buffer.writeEnum(entry.state());
        }
    }
    public static ResourceProductionSnapshot decode(FriendlyByteBuf buffer) {
        long window = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > ResourceAmounts.MAX_KEYS) throw new IllegalArgumentException("Production directory exceeds budget");
        var entries = new ArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            var raw = buffer.readNbt(new NbtAccounter(4096));
            if (raw==null) throw new IllegalArgumentException("Missing production identity"); var tag=raw;
            var key = ResourceKey.load(tag);
            var decimal = buffer.readUtf(ResourceExactAmounts.MAX_DECIMAL_DIGITS);
            var amount = decimal.equals("0") ? BigInteger.ZERO : ResourceExactAmounts.parse(decimal);
            entries.add(new Entry(key, amount, buffer.readEnum(State.class)));
        }
        return new ResourceProductionSnapshot(window, entries);
    }
}
