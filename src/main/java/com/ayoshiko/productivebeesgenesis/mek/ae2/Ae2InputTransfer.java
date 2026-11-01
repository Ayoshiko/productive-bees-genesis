package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务器线程上的单次输入交接。外部回调前先登记请求，保存时冻结完整组件指纹；
 * 成功路径复用状态且不编码指纹，未知结果只允许管理员核验后结算。
 */
public final class Ae2InputTransfer {
    private static final String NBT_KEY = "productivebeesgenesis_ae_input_transfer";
    private Supplier<String> fingerprintSource;
    private String fingerprint;
    private UUID request;
    private String phase;
    private int slot = -1;
    private long known;
    private long offered;
    private Tag unreadable;

    public boolean isPresent() {
        return phase != null || unreadable != null;
    }

    public long retainedAmount() {
        return Math.addExact(known, offered);
    }

    public long knownAmount() {
        return known;
    }

    public long offeredAmount() {
        return offered;
    }

    public String destination() {
        return unreadable == null ? phase + (slot < 0 ? "" : ":" + slot) : "invalid-data";
    }

    public UUID request() {
        if (!isPresent() || unreadable != null) return null;
        if (request == null) request = UUID.randomUUID();
        return request;
    }

    public String fingerprint() {
        if (fingerprint == null && fingerprintSource != null) {
            fingerprint = fingerprintSource.get();
            fingerprintSource = null;
        }
        return fingerprint;
    }

    public void beginExtract(Supplier<String> identity, long amount) {
        begin(identity, amount);
        phase = "extract";
        offered = amount;
    }

    public void beginSlotWithdrawal(Supplier<String> identity, long amount, int sourceSlot) {
        begin(identity, amount);
        phase = "withdraw-slot";
        slot = sourceSlot;
        offered = amount;
    }

    public void beginKnown(Supplier<String> identity, long amount) {
        begin(identity, amount);
        phase = "known";
        known = amount;
    }

    private void begin(Supplier<String> identity, long amount) {
        if (isPresent() || identity == null || amount <= 0 || amount > Integer.MAX_VALUE) {
            throw new IllegalStateException("Input transfer unavailable or invalid amount");
        }
        fingerprintSource = identity;
    }

    public void extracted(long amount) {
        if (!"extract".equals(phase) && !"withdraw-slot".equals(phase)) {
            throw new IllegalStateException("No extraction in flight");
        }
        checkResult(amount);
        known = amount;
        offered = 0;
        slot = -1;
        phase = "known";
    }

    public void offer(long amount, int targetSlot) {
        if (!"known".equals(phase) || amount <= 0 || amount > known) {
            throw new IllegalStateException("Invalid input delivery");
        }
        known -= amount;
        offered = amount;
        slot = targetSlot;
        phase = targetSlot < 0 ? "return" : "slot";
    }

    public void accepted(long amount) {
        if (!"slot".equals(phase) && !"return".equals(phase)) {
            throw new IllegalStateException("No input delivery in flight");
        }
        checkResult(amount);
        known += offered - amount;
        offered = 0;
        slot = -1;
        phase = "known";
    }

    private void checkResult(long amount) {
        if (amount < 0 || amount > offered) {
            throw new IllegalStateException("Invalid external transfer result: " + amount + "/" + offered);
        }
    }

    public void finish(Ae2PendingItemBuffer pending, long tick, boolean networkOnly) {
        finish(pending, tick, networkOnly, 0);
    }

    void finish(Ae2PendingItemBuffer pending, long tick, boolean networkOnly, int previousRetries) {
        finish(pending, tick, networkOnly, previousRetries, true);
    }

    void finish(Ae2PendingItemBuffer pending, long tick, boolean networkOnly, int previousRetries,
            boolean retryFailed) {
        if (!"known".equals(phase)) throw new IllegalStateException("Input result requires reconciliation");
        if (known > 0) {
            String identity = fingerprint();
            long retained = networkOnly ? pending.enqueueReturnToNetwork(identity, known, tick)
                    : pending.enqueue(identity, known, tick);
            known -= retained;
            if (known != 0) throw new IllegalStateException("Input pending capacity changed");
            if (retryFailed) pending.recordFailure(identity, tick, previousRetries);
            else pending.recordDeferred(identity, tick, previousRetries);
        }
        clear();
    }

    /** transferred 是实际扣出的数量（extract）或接收方实际接收的数量（slot/return）。 */
    public void resolve(UUID expected, long transferred, Ae2PendingItemBuffer pending, long tick) {
        if (unreadable != null || expected == null || !expected.equals(request())) {
            throw new IllegalArgumentException("Input request identity does not match");
        }
        checkResult(transferred);
        boolean withdrawal = "extract".equals(phase) || "withdraw-slot".equals(phase);
        long remaining = withdrawal ? transferred : known + offered - transferred;
        String identity = fingerprint();
        if (remaining > 0) {
            if (!pending.canAcceptReturn(identity, remaining)) {
                throw new IllegalStateException("Input pending capacity unavailable");
            }
            pending.enqueueReturnToNetwork(identity, remaining, tick);
        }
        clear();
    }

    public void save(CompoundTag parent) {
        if (unreadable != null) {
            parent.put(NBT_KEY, unreadable.copy());
        } else if (phase != null) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("version", 1);
            tag.putUUID("request", request());
            tag.putString("fingerprint", fingerprint());
            tag.putString("phase", phase);
            tag.putInt("slot", slot);
            tag.putLong("known", known);
            tag.putLong("offered", offered);
            parent.put(NBT_KEY, tag);
        } else {
            parent.remove(NBT_KEY);
        }
    }

    public void load(CompoundTag parent) {
        clear();
        Tag raw = parent.get(NBT_KEY);
        if (raw == null) return;
        if (!(raw instanceof CompoundTag tag) || !tag.contains("version", Tag.TAG_INT)
                || tag.getInt("version") != 1 || !tag.hasUUID("request")
                || !tag.contains("fingerprint", Tag.TAG_STRING) || tag.getString("fingerprint").isBlank()
                || !tag.contains("phase", Tag.TAG_STRING) || !tag.contains("slot", Tag.TAG_INT)
                || !tag.contains("known", Tag.TAG_LONG) || !tag.contains("offered", Tag.TAG_LONG)) {
            unreadable = raw.copy();
            return;
        }
        String savedPhase = tag.getString("phase");
        long savedKnown = tag.getLong("known");
        long savedOffered = tag.getLong("offered");
        int savedSlot = tag.getInt("slot");
        boolean validPhase = switch (savedPhase) {
            case "extract" -> savedKnown == 0 && savedOffered > 0 && savedSlot == -1;
            case "slot" -> savedOffered > 0 && savedSlot >= 0;
            case "withdraw-slot" -> savedKnown == 0 && savedOffered > 0 && savedSlot >= 0;
            case "return" -> savedOffered > 0 && savedSlot == -1;
            case "known" -> savedOffered == 0 && savedSlot == -1;
            default -> false;
        };
        if (!validPhase || savedKnown < 0 || savedOffered < 0 || savedKnown > Integer.MAX_VALUE
                || savedOffered > Integer.MAX_VALUE - savedKnown) {
            unreadable = raw.copy();
            return;
        }
        fingerprint = tag.getString("fingerprint");
        request = tag.getUUID("request");
        phase = savedPhase;
        known = savedKnown;
        offered = savedOffered;
        slot = savedSlot;
    }

    public void clear() {
        fingerprintSource = null;
        fingerprint = null;
        request = null;
        phase = null;
        slot = -1;
        known = 0;
        offered = 0;
        unreadable = null;
    }
}
