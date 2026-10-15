package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;

/** 能力固定方块与首次可用核心；失效后绝不自动转绑。 */
final class PortAccess {
    record Snapshot(NetworkCoreBlockEntity core, NetworkSavedData authority, NetworkCheckpoint root, PortState state) { }
    final NetworkPortBlockEntity port;
    private final Object token;
    private NetworkCoreBlockEntity bound;
    private Long configuration;
    private Long topologyEpoch;
    private boolean revoked;
    PortAccess(NetworkPortBlockEntity port) { this.port = port; token = port.token(); }
    Snapshot get(boolean automated) {
        if (revoked || token != port.token() || !NetworkPortBlockEntity.live(port)) return null;
        var core = port.core();
        if (core == null || port.network() == null) {
            if (bound != null) revoke();
            return null;
        }
        if (bound != null && bound != core) { revoke(); return null; }
        long epoch = core.topology().epoch();
        if (topologyEpoch != null && topologyEpoch != epoch) { revoke(); return null; }
        var authority = core.ownership().readyAuthority(); if (authority == null) return null;
        var root = authority.checkpoint(); var state = root.ports().get(PortState.identity(port.network(), port.origin()));
        if (state == null || state.kind() != port.kind() || !state.origin().equals(port.origin())) return null;
        if (configuration != null && configuration != state.configuration()) { revoke(); return null; }
        configuration = state.configuration(); topologyEpoch = epoch; bound = core;
        return automated && !state.enabled() ? null : new Snapshot(core, authority, root, state);
    }
    private void revoke() { revoked = true; port.invalidateCapabilities(); }
    boolean commit(Snapshot old, NetworkCheckpoint next, boolean simulate) {
        var current = get(false);
        if (current == null || current.authority != old.authority || current.root != old.root) return false;
        if (simulate) return true;
        try { old.authority.publish(next); }
        catch (RuntimeException failure) {
            if (old.authority.checkpoint() != next) throw failure;
            // 根已提交时必须返回真实接受量，不能让调用方以零接受重试。
            com.mojang.logging.LogUtils.getLogger().error("Port transfer committed before index update failed at {}", port.getBlockPos(), failure);
        }
        return true;
    }
}
