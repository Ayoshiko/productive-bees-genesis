package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.network.PayloadRateLimiter;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** 配置窗口专用查询；不发送账户身份、余额或生产统计。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceSideConfigSync {
    private ResourceSideConfigSync() {}
    private record Session(Object menu, UUID id, long sequence, ResourceSideConfig published) {}
    private static final Map<ServerPlayer, Session> SESSIONS = new WeakHashMap<>();
    public record Query(ResourceMenuSync.Request request) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_sides_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = ResourceMenuSync.Request.CODEC.map(Query::new, Query::request);
        public Type<Query> type() { return TYPE; }
    }
    public record Reply(ResourceMenuSync.Request request, long revision, CompoundTag config, boolean editable) implements CustomPacketPayload {
        public Reply {
            if (revision < 1 || config != null && config.sizeInBytes() > 1024) throw new IllegalArgumentException("Invalid side reply");
            config = config == null ? null : config.copy();
        }
        public static final Type<Reply> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_sides_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = new StreamCodec<>() {
            public Reply decode(RegistryFriendlyByteBuf b) {
                var request = ResourceMenuSync.Request.CODEC.decode(b);
                long revision = b.readVarLong();
                CompoundTag config = null;
                if (b.readBoolean()) {
                    var raw = b.readNbt(NbtAccounter.create(1024));
                    if (!(raw instanceof CompoundTag tag)) throw new IllegalArgumentException("Invalid side configuration packet");
                    config = tag;
                }
                return new Reply(request, revision, config, b.readBoolean());
            }
            public void encode(RegistryFriendlyByteBuf b, Reply value) {
                ResourceMenuSync.Request.CODEC.encode(b, value.request);
                b.writeVarLong(value.revision);
                b.writeBoolean(value.config != null);
                if (value.config != null) b.writeNbt(value.config);
                b.writeBoolean(value.editable);
            }
        };
        public Type<Reply> type() { return TYPE; }
    }
    public record Change(ResourceMenuSync.Request request, int typeId, int mask, boolean eject) implements CustomPacketPayload {
        public Change {
            if (typeId < 0 || typeId >= ResourceNativeType.values().length || mask < 0 || mask > 63) throw new IllegalArgumentException("Invalid side change");
        }
        public static final Type<Change> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_sides_change"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Change> CODEC = new StreamCodec<>() {
            public Change decode(RegistryFriendlyByteBuf b) {
                return new Change(ResourceMenuSync.Request.CODEC.decode(b), b.readVarInt(), b.readUnsignedByte(), b.readBoolean());
            }
            public void encode(RegistryFriendlyByteBuf b, Change value) {
                ResourceMenuSync.Request.CODEC.encode(b, value.request);
                b.writeVarInt(value.typeId);
                b.writeByte(value.mask);
                b.writeBoolean(value.eject);
            }
        };
        public Type<Change> type() { return TYPE; }
    }
    public record Close(ResourceMenuSync.Request request) implements CustomPacketPayload {
        public static final Type<Close> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_sides_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Close> CODEC = ResourceMenuSync.Request.CODEC.map(Close::new, Close::request);
        public Type<Close> type() { return TYPE; }
    }
    private static boolean editable(ServerPlayer player, ResourceHiveHost state) {
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        return state.nativeConfigValid() && (player.hasPermissions(2)
                || tile instanceof mekanism.common.lib.security.ISecurityTile security
                && player.getUUID().equals(security.getOwnerUUID()));
    }
    private static boolean session(ServerPlayer player, ResourceMenuSync.Request request, boolean open) {
        if (!ResourceMenuSync.validSession(player, request)) return false;
        var previous = SESSIONS.get(player);
        boolean same = previous != null && previous.menu == player.containerMenu && previous.id.equals(request.session());
        if ((!open && !same) || (same && request.sequence() <= previous.sequence)) return false;
        SESSIONS.put(player, new Session(player.containerMenu, request.session(), request.sequence(), same ? previous.published : null));
        return true;
    }
    public static boolean query(ServerPlayer player, ResourceMenuSync.Request request) {
        if (!session(player, request, true)) return false;
        reply(player, request);
        return true;
    }
    public static boolean close(ServerPlayer player, ResourceMenuSync.Request request) {
        if (!session(player, request, false)) return false;
        SESSIONS.remove(player);
        return true;
    }
    private static void reply(ServerPlayer player, ResourceMenuSync.Request request) {
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var state = tile.getData(ResourceHiveHost.TYPE);
        boolean valid = state.nativeConfigValid();
        CompoundTag config = valid ? state.nativeConfig().save() : new CompoundTag();
        var session = SESSIONS.get(player);
        boolean unchanged = valid && session != null && session.published == state.nativeConfig()
                && request.revision() == state.nativeConfig().revision();
        PacketDistributor.sendToPlayer(player, new Reply(request, state.nativeConfig().revision(),
                unchanged ? null : config, valid && editable(player, state)));
        SESSIONS.put(player, new Session(player.containerMenu, request.session(), request.sequence(), state.nativeConfig()));
    }
    public static boolean change(ServerPlayer player, Change action) {
        if (!session(player, action.request, false)) return false;
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var state = tile.getData(ResourceHiveHost.TYPE);
        if (ResourceNativeType.values()[action.typeId] == ResourceNativeType.ENERGY
                || !editable(player, state) || action.request.revision() != state.nativeConfig().revision()
                || SESSIONS.get(player).published != state.nativeConfig()) return false;
        state.setNativeOutput(tile, ResourceNativeType.values()[action.typeId], action.mask, action.eject);
        reply(player, action.request);
        return true;
    }
    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Query.TYPE, Query.CODEC, (value, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_sides", 200)) {
                query(player, value.request);
            }
        }));
        registrar.playToServer(Change.TYPE, Change.CODEC, (value, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_sides_change", 100)) {
                try { change(player, value); }
                catch (IllegalArgumentException | IllegalStateException changed) {
                    ProductiveBeesGenesis.LOGGER.debug("Resource side change rejected", changed);
                }
            }
        }));
        registrar.playToServer(Close.TYPE, Close.CODEC, (value, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_sides_close", 200)) {
                close(player, value.request);
            }
        }));
        registrar.playToClient(Reply.TYPE, Reply.CODEC, (value, context) -> context.enqueueWork(() ->
                com.ayoshiko.productivebeesgenesis.client.resource.ResourceMachinePanel.acceptSides(value)));
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity());
    }
    @SubscribeEvent public static void closed(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        SESSIONS.remove(event.getEntity());
    }
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SESSIONS.keySet().removeIf(player -> player.getServer() == event.getServer());
    }
}
