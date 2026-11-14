package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** 服务器线程独占的能量请求；未知外部结果和未结清的 J 都随宿主保存。 */
public final class Ae2EnergyTransfer {
    private static final String NBT_KEY = "productivebeesgenesis_ae_energy_transfer";
    private static final BigDecimal MAX_JOULES = BigDecimal.valueOf(Long.MAX_VALUE);
    private static final Set<String> SOURCES = Set.of("applied_flux_fe", "ae_native");
    // 最小正 AE 回执的精确尾数，加上转换率的十进制精度，必须可以随机器往返保存。
    private static final int MAX_SAVED_SCALE = new BigDecimal(Double.MIN_VALUE).scale()
            + BigDecimal.valueOf(Double.MIN_VALUE).scale();
    private static final int MAX_SAVED_ENERGY_LENGTH = 2048;
    private UUID request;
    private String phase;
    private String source = "";
    private long requestedFe;
    private double joulesPerFe;
    private BigDecimal knownJoules = BigDecimal.ZERO;
    private long beforeCredit;
    private long offeredCredit;
    private Tag unreadable;
    private CompoundTag legacyExtraction;

    public boolean isPresent() {
        return phase != null || unreadable != null;
    }

    public boolean requiresReconciliation() {
        return unreadable != null || "extract".equals(phase) || "credit".equals(phase);
    }

    public UUID request() {
        if (!isPresent() || unreadable != null) return null;
        if (request == null) request = UUID.randomUUID();
        return request;
    }

    public String phase() {
        return unreadable == null ? phase : "invalid-data";
    }

    public String source() {
        return source;
    }

    public long requestedFe() {
        return requestedFe;
    }

    public long knownJoules() {
        return knownJoules.signum() == 0 ? 0L : knownJoules.min(MAX_JOULES).longValue();
    }

    public String retainedJoules() {
        return knownJoules.toPlainString();
    }

    BigDecimal retainedEnergy() {
        return knownJoules;
    }

    public long offeredCredit() {
        return offeredCredit;
    }

    public UUID legacyRequest() {
        return legacyExtraction == null ? null : legacyExtraction.getUUID("request");
    }

    public long legacyRequestedFe() {
        return legacyExtraction == null ? 0L : legacyExtraction.getLong("requested_fe");
    }

    public void beginExtract(String source, long requestedFe, double conversion) {
        if (requiresReconciliation() || knownJoules() != 0 || !SOURCES.contains(source)
                || requestedFe <= 0 || !Double.isFinite(conversion) || conversion <= 0) {
            throw new IllegalStateException("Energy transfer unavailable or invalid request");
        }
        this.source = source;
        this.requestedFe = requestedFe;
        joulesPerFe = conversion;
        request = null;
        phase = "extract";
    }

    public void extracted(long amountFe) {
        extracted(BigDecimal.valueOf(amountFe));
    }

    public void extracted(double amountFe) {
        if (!Double.isFinite(amountFe)) {
            throw new IllegalStateException("Invalid energy receipt");
        }
        // 原生 AE 回执是实际 double 数值；valueOf 的最短字符串会改写大数低位。
        extracted(new BigDecimal(amountFe));
    }

    private void extracted(BigDecimal amountFe) {
        if (!"extract".equals(phase) || amountFe.signum() < 0
                || amountFe.compareTo(BigDecimal.valueOf(requestedFe)) > 0) {
            throw new IllegalStateException("Invalid extracted energy: " + amountFe + "/" + requestedFe);
        }
        knownJoules = knownJoules.add(convertedEnergy(amountFe, joulesPerFe));
        requestedFe = 0;
        phase = "known";
        finishIfEmpty();
    }

    public long beginCredit(long currentEnergy, long maxEnergy) {
        if (requiresReconciliation()) throw new IllegalStateException("Energy result requires reconciliation");
        long amount = Math.min(knownJoules(), Ae2EnergyMath.remainingCapacity(currentEnergy, maxEnergy));
        if (amount <= 0) return 0;
        beforeCredit = currentEnergy;
        offeredCredit = amount;
        phase = "credit";
        return amount;
    }

    public void credited(long currentEnergy) {
        if (!"credit".equals(phase) || currentEnergy < beforeCredit
                || currentEnergy - beforeCredit > offeredCredit) {
            throw new IllegalStateException("Invalid local energy receipt");
        }
        knownJoules = knownJoules.subtract(BigDecimal.valueOf(currentEnergy - beforeCredit));
        beforeCredit = 0;
        offeredCredit = 0;
        phase = "known";
        finishIfEmpty();
    }

    /** 核实提取时填实际 FE；核实本地入账时填实际新增的整数 J。 */
    public void resolve(UUID expected, BigDecimal transferred) {
        if (unreadable != null || expected == null || transferred == null || transferred.signum() < 0) {
            throw new IllegalArgumentException("Energy request identity or amount does not match");
        }
        if (expected.equals(legacyRequest())) {
            resolveLegacyExtraction(transferred);
            return;
        }
        if (!expected.equals(request())) {
            throw new IllegalArgumentException("Energy request identity or amount does not match");
        }
        if ("extract".equals(phase)) {
            if ("applied_flux_fe".equals(source) && transferred.stripTrailingZeros().scale() > 0) {
                throw new IllegalArgumentException("AppliedFlux receipt must contain whole FE");
            }
            extracted(transferred);
        } else if ("credit".equals(phase)) {
            long credited = transferred.longValueExact();
            if (credited > offeredCredit) throw new IllegalArgumentException("Energy credit exceeds request");
            credited(Math.addExact(beforeCredit, credited));
        } else {
            throw new IllegalStateException("Energy request is already known");
        }
    }

    private void resolveLegacyExtraction(BigDecimal transferred) {
        if (requiresReconciliation()) {
            throw new IllegalStateException("Resolve the current energy request before the legacy receipt");
        }
        BigDecimal maximum = new BigDecimal(legacyNativeRequest(legacyRequestedFe()) * 2.0);
        if (transferred.compareTo(maximum) > 0) {
            throw new IllegalArgumentException("Legacy energy receipt exceeds the original AE request");
        }
        double conversion = legacyExtraction.getDouble("joules_per_fe");
        BigDecimal confirmed = knownJoules.add(convertedEnergy(transferred, conversion));
        if (confirmed.compareTo(MAX_JOULES.add(BigDecimal.valueOf(conversion))) > 0) {
            throw new IllegalStateException("Credit the retained energy before adding this legacy receipt");
        }
        if (transferred.signum() > 0) {
            request = legacyRequest();
            source = "ae_native";
            joulesPerFe = conversion;
            knownJoules = confirmed;
            requestedFe = 0;
            phase = "known";
        }
        legacyExtraction = null;
        finishIfEmpty();
    }

    private static BigDecimal convertedEnergy(BigDecimal amountFe, double conversionRate) {
        BigDecimal conversion = BigDecimal.valueOf(conversionRate);
        if (amountFe.scale() > MAX_SAVED_SCALE - conversion.scale()) {
            throw new IllegalArgumentException("Energy receipt precision exceeds the saved format");
        }
        return amountFe.multiply(conversion);
    }

    public void save(CompoundTag parent) {
        if (unreadable != null) {
            parent.put(NBT_KEY, unreadable.copy());
        } else if (phase != null || legacyExtraction != null) {
            var tag = new CompoundTag();
            tag.putInt("version", 2);
            if (phase != null) {
                tag.putUUID("request", request());
                tag.putString("phase", phase);
                tag.putString("source", source);
                tag.putLong("requested_fe", requestedFe);
                tag.putDouble("joules_per_fe", joulesPerFe);
                tag.putString("known_joules", knownJoules.toPlainString());
                tag.putLong("before_credit", beforeCredit);
                tag.putLong("offered_credit", offeredCredit);
            }
            if (legacyExtraction != null) {
                tag.put("legacy_extract", legacyExtraction.copy());
            }
            parent.put(NBT_KEY, tag);
        } else {
            parent.remove(NBT_KEY);
        }
    }

    public void load(CompoundTag parent) {
        clear();
        Tag raw = parent.get(NBT_KEY);
        if (raw == null) return;
        try {
            if (!(raw instanceof CompoundTag tag) || !tag.contains("version", Tag.TAG_INT)
                    || tag.getInt("version") != 1 && tag.getInt("version") != 2) {
                throw new IllegalArgumentException("Invalid energy transfer version");
            }
            if (tag.contains("legacy_extract")) {
                if (tag.getInt("version") != 2 || !(tag.get("legacy_extract") instanceof CompoundTag legacy)
                        || !legacy.contains("version", Tag.TAG_INT) || legacy.getInt("version") != 1
                        || legacy.contains("legacy_extract")) {
                    throw new IllegalArgumentException("Invalid legacy energy transfer");
                }
                var previous = new Ae2EnergyTransfer();
                previous.loadState(legacy);
                if (!previous.hasLegacyRoundingRisk()) {
                    throw new IllegalArgumentException("Legacy energy transfer does not match the rounding migration");
                }
                legacyExtraction = legacy.copy();
            }
            if (tag.contains("phase")) {
                loadState(tag);
            } else if (legacyExtraction == null
                    || !Set.of("version", "legacy_extract").containsAll(tag.getAllKeys())) {
                throw new IllegalArgumentException("Missing active energy transfer");
            }
            if (tag.getInt("version") == 1 && hasLegacyRoundingRisk()) {
                // 旧格式没有实际回执。保管原请求，尾数继续归本机；新供能另行真实扣取。
                legacyExtraction = tag.copy();
                request = null;
                requestedFe = 0;
                phase = "known";
                finishIfEmpty();
            }
        } catch (RuntimeException error) {
            clear();
            unreadable = raw.copy();
        }
    }

    private void loadState(CompoundTag tag) {
        if (!tag.hasUUID("request") || !tag.contains("phase", Tag.TAG_STRING)
                || !tag.contains("source", Tag.TAG_STRING) || !SOURCES.contains(tag.getString("source"))
                || !tag.contains("requested_fe", Tag.TAG_LONG)
                || !tag.contains("joules_per_fe", Tag.TAG_DOUBLE)
                || !tag.contains("known_joules", Tag.TAG_STRING)
                || tag.getString("known_joules").length() > MAX_SAVED_ENERGY_LENGTH
                || !tag.contains("before_credit", Tag.TAG_LONG)
                || !tag.contains("offered_credit", Tag.TAG_LONG)) {
            throw new IllegalArgumentException("Invalid energy transfer header");
        }
        long savedRequest = tag.getLong("requested_fe");
        double savedConversion = tag.getDouble("joules_per_fe");
        BigDecimal savedKnown = new BigDecimal(tag.getString("known_joules"));
        long savedBefore = tag.getLong("before_credit");
        long savedCredit = tag.getLong("offered_credit");
        boolean validPhase = switch (tag.getString("phase")) {
            case "extract" -> savedRequest > 0 && savedKnown.compareTo(BigDecimal.ONE) < 0
                    && savedBefore == 0 && savedCredit == 0;
            case "known" -> savedRequest == 0 && savedKnown.signum() > 0
                    && savedBefore == 0 && savedCredit == 0;
            case "credit" -> savedRequest == 0 && savedCredit > 0 && savedBefore >= 0
                    && savedCredit <= Long.MAX_VALUE - savedBefore
                    && savedKnown.compareTo(BigDecimal.valueOf(savedCredit)) >= 0;
            default -> false;
        };
        if (!validPhase || !Double.isFinite(savedConversion) || savedConversion <= 0
                || savedKnown.signum() < 0 || savedKnown.scale() < 0 || savedKnown.scale() > MAX_SAVED_SCALE
                || savedKnown.compareTo(MAX_JOULES.add(BigDecimal.valueOf(savedConversion))) > 0) {
            throw new IllegalArgumentException("Invalid energy transfer state");
        }
        request = tag.getUUID("request");
        phase = tag.getString("phase");
        source = tag.getString("source");
        requestedFe = savedRequest;
        joulesPerFe = savedConversion;
        knownJoules = savedKnown;
        beforeCredit = savedBefore;
        offeredCredit = savedCredit;
    }

    private boolean hasLegacyRoundingRisk() {
        return "extract".equals(phase) && "ae_native".equals(source)
                && BigDecimal.valueOf(legacyNativeRequest(requestedFe) * 2.0)
                        .compareTo(BigDecimal.valueOf(requestedFe)) > 0;
    }

    /** 重现旧版请求收紧与回执字符串转换，只用于识别一次性迁移范围。 */
    private static double legacyNativeRequest(long requestedFe) {
        double requestedAe = requestedFe / 2.0;
        if (BigDecimal.valueOf(requestedAe).multiply(BigDecimal.valueOf(2))
                .compareTo(BigDecimal.valueOf(requestedFe)) > 0) {
            requestedAe = Math.nextDown(requestedAe);
        }
        return requestedAe;
    }

    private void finishIfEmpty() {
        if (knownJoules.signum() == 0) clearActive();
    }

    public void clear() {
        clearActive();
        legacyExtraction = null;
    }

    private void clearActive() {
        request = null;
        phase = null;
        source = "";
        requestedFe = 0;
        joulesPerFe = 0;
        knownJoules = BigDecimal.ZERO;
        beforeCredit = 0;
        offeredCredit = 0;
        unreadable = null;
    }
}
