package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 槽位实物和配置随网络根保存；端口方块不持第二份材料。 */
public record PortState(UUID id, Origin origin, Kind kind, long revision, long configuration,
        boolean enabled, UUID target, List<Stack> supplies, Map<Integer, ProductKey> filters) {
    public enum Kind { SUPPLY, INPUT, OUTPUT }
    public static final int SLOTS = 9, FLUID = 9, FLUID_LIMIT = 1000;
    public record Stack(ProductKey key, int count) {
        public static final Stack EMPTY = new Stack(null, 0);
        public Stack {
            if (key == null ? count != 0 : key.kind() != ProductKey.Kind.ITEM || count < 1 || count > 64)
                throw new IllegalArgumentException("Invalid finite supply stack");
        }
        public boolean empty() { return key == null; }
    }
    public PortState {
        Objects.requireNonNull(id); Objects.requireNonNull(origin); Objects.requireNonNull(kind);
        supplies = List.copyOf(supplies); filters = Map.copyOf(filters);
        if (revision < 0 || configuration < 0 || configuration > revision || supplies.size() != SLOTS)
            throw new IllegalArgumentException("Invalid port state");
        if (kind != Kind.SUPPLY && (target != null || supplies.stream().anyMatch(s -> !s.empty())))
            throw new IllegalArgumentException("Product port cannot own supply items");
        if (kind == Kind.SUPPLY && !filters.isEmpty()) throw new IllegalArgumentException("Supply port cannot filter products");
        for (var entry : filters.entrySet()) if (entry.getKey() < 0 || entry.getKey() > FLUID
                || entry.getValue().kind() != (entry.getKey() == FLUID ? ProductKey.Kind.FLUID : ProductKey.Kind.ITEM))
            throw new IllegalArgumentException("Invalid port filter");
    }
    public static UUID identity(NetworkIdentity network, Origin origin) {
        return UUID.nameUUIDFromBytes((network.networkId() + ":port:" + origin.dimension() + ":"
                + origin.x() + ":" + origin.y() + ":" + origin.z()).getBytes(StandardCharsets.UTF_8));
    }
    public static PortState empty(NetworkIdentity network, Origin origin, Kind kind) {
        return new PortState(identity(network, origin), origin, kind, 0, 0, false, null,
                Collections.nCopies(SLOTS, Stack.EMPTY), Map.of());
    }
    public void validateNetwork(NetworkIdentity network) {
        if (!network.origin().dimension().equals(origin.dimension()) || !id.equals(identity(network, origin)))
            throw new IllegalArgumentException("Foreign port anchor");
    }
    public void validateSuccessor(PortState next) {
        if (!id.equals(next.id) || !origin.equals(next.origin) || kind != next.kind
                || next.revision != Math.incrementExact(revision)
                || next.configuration < configuration || next.configuration > Math.incrementExact(configuration)
                || next.configuration == configuration && (enabled != next.enabled || !Objects.equals(target, next.target) || !filters.equals(next.filters)))
            throw new IllegalArgumentException("Invalid port successor");
    }
    public PortState stack(int slot, Stack stack) {
        if (kind != Kind.SUPPLY) throw new IllegalArgumentException("No supply slots");
        var next = new ArrayList<>(supplies); if (next.get(slot).equals(stack)) return this;
        next.set(slot, stack); return change(enabled, target, next, filters, false);
    }
    public PortState supplies(List<Stack> values) {
        if (kind != Kind.SUPPLY) throw new IllegalArgumentException("No supply slots");
        return supplies.equals(values) ? this : change(enabled, target, values, filters, false);
    }
    public PortState enabled(boolean value) { return enabled == value ? this : change(value, target, supplies, filters, true); }
    public PortState target(UUID value) {
        if (kind != Kind.SUPPLY) throw new IllegalArgumentException("No supply target");
        return Objects.equals(target, value) ? this : change(enabled, value, supplies, filters, true);
    }
    public PortState filter(int slot, ProductKey value) {
        if (kind == Kind.SUPPLY || slot < 0 || slot > FLUID) throw new IllegalArgumentException("Invalid filter slot");
        var next = new HashMap<>(filters);
        if (value == null) next.remove(slot); else next.put(slot, value);
        return next.equals(filters) ? this : change(enabled, target, supplies, next, true);
    }
    private PortState change(boolean active, UUID member, List<Stack> slots, Map<Integer, ProductKey> keys, boolean settings) {
        return new PortState(id, origin, kind, Math.incrementExact(revision), settings ? Math.incrementExact(configuration) : configuration,
                active, member, slots, keys);
    }
}
