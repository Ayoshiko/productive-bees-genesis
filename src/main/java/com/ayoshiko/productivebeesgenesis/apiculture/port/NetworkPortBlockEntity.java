package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 实物留在网络。重新放置后须由原网络所有者打开，显式重接同位置的保管账户。 */
public final class NetworkPortBlockEntity extends BlockEntity {
    private UUID owner;
    private NetworkIdentity network;
    private Tag invalidData;
    private Object token = new Object();
    private boolean watched, failed;
    private int result;
    public NetworkPortBlockEntity(BlockPos pos, BlockState state) { super(NetworkContent.PORT_TILE.get(), pos, state); }
    public PortState.Kind kind() { return ((NetworkPortBlock) getBlockState().getBlock()).kind(); }
    public UUID owner() { return owner; }
    public Object token() { return token; }
    public NetworkIdentity network() { return network; }
    public void initializeOwner(UUID value) { if (owner == null && invalidData == null) { owner = value; setChanged(); } }
    public Origin origin() {
        return new Origin(level.dimension().location().toString(), worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }
    public static boolean live(BlockEntity tile) {
        if (tile == null || tile.isRemoved() || !(tile.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
        var chunk = level.getChunkSource().getChunkNow(tile.getBlockPos().getX() >> 4, tile.getBlockPos().getZ() >> 4);
        return chunk != null && chunk.getBlockEntity(tile.getBlockPos()) == tile;
    }
    /** 六邻格只允许一个核心，包括其它所有者的核心也算歧义，不强制加载区块。 */
    public NetworkCoreBlockEntity core() {
        var found = attachedCore();
        return found != null && found.topology() != null && found.topology().valid() && found.ownership().readyAuthority() != null ? found : null;
    }
    public NetworkCoreBlockEntity attachedCore() {
        if (!live(this) || owner == null || invalidData != null || failed || !ModConfig.SERVER.beeNetwork.enabled.get()) return null;
        var server = (ServerLevel) level; NetworkCoreBlockEntity found = null;
        for (var side : Direction.values()) {
            var pos = worldPosition.relative(side); if (server.isOutsideBuildHeight(pos)) continue;
            var chunk = server.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4); if (chunk == null) return null;
            if (chunk.getBlockEntity(pos) instanceof NetworkCoreBlockEntity candidate) {
                if (found != null) return null; found = candidate;
            }
        }
        return found != null && owner.equals(found.owner()) && live(found) && found.validNetworkReference() && found.network() != null
                && (network == null || network.equals(found.network())) ? found : null;
    }
    public PortState state() {
        var core = core(); if (core == null || network == null) return null;
        var state = core.ownership().readyAuthority().checkpoint().ports().get(PortState.identity(network, origin()));
        return state != null && state.kind() == kind() && state.origin().equals(origin()) ? state : null;
    }
    public boolean allowed(ServerPlayer player) {
        return live(this) && player.level() == level && player.isAlive() && !player.isSpectator()
                && player.getUUID().equals(owner) && player.distanceToSqr(worldPosition.getCenter()) <= 64
                && level.mayInteract(player, worldPosition);
    }
    public boolean open(ServerPlayer player) {
        if (!allowed(player)) return false;
        var core = core();
        if (core == null || !level.mayInteract(player, core.getBlockPos())) { player.displayClientMessage(NetworkPortMenu.text("status.1"), true); return false; }
        var authority = core.ownership().readyAuthority(); var before = authority.checkpoint();
        var empty = PortState.empty(core.network(), origin(), kind()); var stored = before.ports().get(empty.id());
        if (stored != null && stored.kind() != kind()) { player.displayClientMessage(NetworkPortMenu.text("wrong_kind"), true); return false; }
        if (stored == null) authority.publish(before.withPort(null, empty));
        network = core.network(); connectionChanged(); setChanged();
        var session = UUID.randomUUID();
        player.openMenu(new SimpleMenuProvider((id, inventory, viewer) ->
                new NetworkPortMenu(id, inventory, this, core, session), NetworkPortMenu.text("title." + kind().name().toLowerCase(Locale.ROOT))),
                buffer -> { buffer.writeBlockPos(worldPosition); buffer.writeUUID(session); buffer.writeEnum(kind()); });
        PortWorkService.watch(this); return true;
    }
    public boolean failed() { return failed; }
    public int result() { return result; }
    public void result(int value) { result = value; }
    public void connectionChanged() {
        token = new Object(); invalidateCapabilities(); watched = false;
    }
    public void serverTick() {
        if (!watched && !failed && live(this) && network != null) { watched = true; PortWorkService.watch(this); }
        else if (!failed && network != null && kind() == PortState.Kind.INPUT && level instanceof ServerLevel server
                && com.ayoshiko.productivebeesgenesis.apiculture.runtime.RuntimeProductPolicies.peek(server) == null) PortWorkService.watch(this);
    }
    public void isolate(RuntimeException failure) {
        if (!failed) com.mojang.logging.LogUtils.getLogger().error("Network port isolated at {}", worldPosition, failure);
        failed = true; result = 9; PortWorkService.remove(this); connectionChanged(); setChanged();
    }
    @Override public void setRemoved() { PortWorkService.remove(this); connectionChanged(); super.setRemoved(); }
    @Override public void onChunkUnloaded() { PortWorkService.remove(this); connectionChanged(); super.onChunkUnloaded(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (invalidData != null) { tag.put("port", invalidData.copy()); return; }
        if (owner == null) return;
        var data = new CompoundTag(); data.putInt("schema", 1); data.putUUID("owner", owner); data.putBoolean("failed", failed);
        data.put("network", network == null ? new CompoundTag() : NetworkCheckpointCodec.identity(network)); tag.put("port", data);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); connectionChanged(); owner = null; network = null; invalidData = null; failed = false;
        if (!tag.contains("port")) return;
        try {
            var data = StrictNbt.compound(tag, "port");
            if (StrictNbt.integer(data, "schema") != 1 || !data.getAllKeys().equals(Set.of("schema", "owner", "failed", "network")))
                throw new IllegalArgumentException("Invalid port reference");
            owner = StrictNbt.uuid(data, "owner"); failed = StrictNbt.bool(data, "failed");
            var reference = StrictNbt.compound(data, "network"); if (!reference.isEmpty()) network = NetworkCheckpointCodec.readIdentity(reference);
            if (network != null && !owner.equals(network.ownerId())) throw new IllegalArgumentException("Foreign port owner");
        } catch (RuntimeException failure) { invalidData = tag.get("port").copy(); isolate(failure); }
    }
}
