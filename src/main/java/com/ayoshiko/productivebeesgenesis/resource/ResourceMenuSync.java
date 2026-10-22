package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.network.PayloadRateLimiter;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import java.util.UUID;

/** 机器资源窗口的有界查询与显式弹空；客户端不能写余额或生成物品。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ResourceMenuSync {
    private ResourceMenuSync() {}
    private static final java.util.Map<ServerPlayer, Session> SESSIONS = new java.util.WeakHashMap<>();
    private record Session(Object menu, UUID id, long sequence, ResourceAccount.Reference source,
            ResourceInventoryDelta.Publisher publisher, ResourceProductionDelta.Publisher productionPublisher) {}
    private static Session session(ServerPlayer player, Request request) {
        var previous = SESSIONS.get(player);
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var host = ResourceHiveHost.existing(tile);
        var source = host == null ? null : host.reference();
        var publisher = previous != null && previous.menu == player.containerMenu && previous.id.equals(request.session)
                && java.util.Objects.equals(previous.source, source) ? previous.publisher : new ResourceInventoryDelta.Publisher();
        var productionPublisher = previous != null && previous.publisher == publisher
                ? previous.productionPublisher : new ResourceProductionDelta.Publisher();
        var next = new Session(player.containerMenu, request.session, request.sequence, source, publisher, productionPublisher);
        SESSIONS.put(player, next);
        return next;
    }

    public record Request(int menu, BlockPos pos, UUID session, long sequence, int page, long revision, long productionRevision) implements CustomPacketPayload {
        public Request(int menu, BlockPos pos, UUID session, long sequence, int page, long revision) {
            this(menu, pos, session, sequence, page, revision, 0);
        }
        public Request(int menu, BlockPos pos, UUID session, long sequence) { this(menu, pos, session, sequence, 0, 0); }
        public Request(int menu, BlockPos pos, UUID session, long sequence, int page) { this(menu, pos, session, sequence, page, 0); }
        public Request { if (page < 0 || page > 255 || sequence < 0 || revision < 0 || productionRevision < 0) throw new IllegalArgumentException("Invalid resource page"); }
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_menu_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(RegistryFriendlyByteBuf b) { return new Request(b.readVarInt(), b.readBlockPos(), b.readUUID(), b.readVarLong(), b.readVarInt(), b.readVarLong(), b.readVarLong()); }
            public void encode(RegistryFriendlyByteBuf b, Request p) { b.writeVarInt(p.menu); b.writeBlockPos(p.pos); b.writeUUID(p.session); b.writeVarLong(p.sequence); b.writeVarInt(p.page); b.writeVarLong(p.revision); b.writeVarLong(p.productionRevision); }
        };
        public Type<Request> type() { return TYPE; }
    }
    public record EmcRecipient(UUID player, String name, String state, boolean selectable) {
        public static final EmcRecipient NONE = new EmcRecipient(null, "", "unavailable", false);
        public EmcRecipient {
            if (name.length() > 64 || !java.util.Set.of("ready", "offline", "not_in_team", "unavailable").contains(state)) {
                throw new IllegalArgumentException("Invalid EMC recipient display");
            }
        }
    }
    public record CloseWindow(Request request) implements CustomPacketPayload {
        public static final Type<CloseWindow> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_window_close"));
        public static final StreamCodec<RegistryFriendlyByteBuf, CloseWindow> CODEC = Request.CODEC
                .map(CloseWindow::new, CloseWindow::request);
        public Type<CloseWindow> type() { return TYPE; }
    }
    public static boolean closeWindow(ServerPlayer player, Request request) {
        if (!validSession(player, request)) return false;
        var session = SESSIONS.get(player);
        if (session == null || session.menu != player.containerMenu || !session.id.equals(request.session)
                || request.sequence <= session.sequence) return false;
        SESSIONS.remove(player);
        return true;
    }
    public record Reply(Request request, ResourceOutputStatus status, ResourceAccount.Reference source, long capacity,
            ResourceInventoryDelta inventory, int processes, EmcRecipient emcRecipient,
            ResourceProductionDelta production) implements CustomPacketPayload {
        public Reply(Request request, ResourceOutputStatus status, ResourceAccount.Reference source, long capacity,
                ResourceInventoryDelta inventory, int processes, EmcRecipient emcRecipient) {
            this(request, status, source, capacity, inventory, processes, emcRecipient, ResourceProductionDelta.empty());
        }
        public Reply(Request request, ResourceOutputStatus status) { this(request, status, null, ResourceHiveHost.DEFAULT_CAPACITY); }
        public Reply(Request request, ResourceOutputStatus status, ResourceAccount.Reference source, long capacity) {
            this(request, status, source, capacity, ResourceInventoryDelta.empty(), 1, EmcRecipient.NONE);
        }
        public static final Type<Reply> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_menu_reply"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Reply> CODEC = new StreamCodec<>() {
            public Reply decode(RegistryFriendlyByteBuf b) {
                var request = Request.CODEC.decode(b);
                var tag = b.readNbt(net.minecraft.nbt.NbtAccounter.create(8192));
                if (!(tag instanceof net.minecraft.nbt.CompoundTag data)) throw new IllegalArgumentException("Invalid resource summary");
                var source = b.readBoolean() ? new ResourceAccount.Reference(b.readUUID(), b.readVarLong()) : null;
                long capacity = b.readVarLong();
                if (capacity < 0) throw new IllegalArgumentException("Invalid resource capacity");
                var inventory = ResourceInventoryDelta.decode(b); int processes = b.readVarInt();
                if (processes < 0 || processes > 256) throw new IllegalArgumentException("Invalid process count");
                var recipient = b.readBoolean() ? new EmcRecipient(b.readUUID(), b.readUtf(64), b.readUtf(32), b.readBoolean()) : EmcRecipient.NONE;
                var production = ResourceProductionDelta.decode(b);
                return new Reply(request, ResourceOutputStatus.load(data), source, capacity, inventory, processes, recipient, production);
            }
            public void encode(RegistryFriendlyByteBuf b, Reply p) {
                Request.CODEC.encode(b, p.request); b.writeNbt(p.status.save()); b.writeBoolean(p.source != null);
                if (p.source != null) { b.writeUUID(p.source.sourceId()); b.writeVarLong(p.source.generation()); }
                b.writeVarLong(p.capacity); p.inventory.encode(b); b.writeVarInt(p.processes);
                b.writeBoolean(p.emcRecipient.player != null);
                if (p.emcRecipient.player != null) {
                    b.writeUUID(p.emcRecipient.player);
                    b.writeUtf(p.emcRecipient.name, 64);
                    b.writeUtf(p.emcRecipient.state, 32);
                    b.writeBoolean(p.emcRecipient.selectable);
                }
                p.production.encode(b);
            }
        };
        public Type<Reply> type() { return TYPE; }
    }
    public record Action(Request request, ResourceAccount.Reference source, ResourceKey key, boolean toggleMe) implements CustomPacketPayload {
        public static final Type<Action> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_menu_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = new StreamCodec<>() {
            public Action decode(RegistryFriendlyByteBuf b) {
                var request = Request.CODEC.decode(b); var source = new ResourceAccount.Reference(b.readUUID(), b.readVarLong());
                boolean toggle = b.readBoolean();
                ResourceKey key = null;
                if (!toggle) {
                    var raw = b.readNbt(net.minecraft.nbt.NbtAccounter.create(4096));
                    if (!(raw instanceof net.minecraft.nbt.CompoundTag tag)) throw new IllegalArgumentException("Invalid resource key");
                    key = ResourceKey.load(tag);
                }
                return new Action(request, source, key, toggle);
            }
            public void encode(RegistryFriendlyByteBuf b, Action p) {
                Request.CODEC.encode(b, p.request); b.writeUUID(p.source.sourceId()); b.writeVarLong(p.source.generation());
                b.writeBoolean(p.toggleMe); if (!p.toggleMe) b.writeNbt(p.key.save());
            }
        };
        public Type<Action> type() { return TYPE; }
    }

    public record SelectEmcRecipient(Request request, ResourceAccount.Reference source, UUID expected) implements CustomPacketPayload {
        public static final Type<SelectEmcRecipient> TYPE = new Type<>(ResourceLocation.parse("productivebeesgenesis:resource_emc_recipient"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SelectEmcRecipient> CODEC = new StreamCodec<>() {
            public SelectEmcRecipient decode(RegistryFriendlyByteBuf b) {
                return new SelectEmcRecipient(Request.CODEC.decode(b), new ResourceAccount.Reference(b.readUUID(), b.readVarLong()), b.readUUID());
            }
            public void encode(RegistryFriendlyByteBuf b, SelectEmcRecipient p) {
                Request.CODEC.encode(b, p.request);
                b.writeUUID(p.source.sourceId());
                b.writeVarLong(p.source.generation());
                b.writeUUID(p.expected);
            }
        };
        public Type<SelectEmcRecipient> type() { return TYPE; }
    }

    public static boolean validSession(ServerPlayer player, Request request) {
        if (player.isSpectator() || !(player.containerMenu instanceof MekanismTileContainer<?> menu)
                || menu.containerId != request.menu) return false;
        var tile = menu.getTileEntity();
        return tile != null && (tile instanceof TileEntityMekApiary || ResourceHiveHost.isMekCentrifuge(tile))
                && !tile.isRemoved() && tile.getLevel() == player.serverLevel() && tile.getBlockPos().equals(request.pos)
                && player.serverLevel().getChunkSource().hasChunk(request.pos.getX() >> 4, request.pos.getZ() >> 4)
                && player.serverLevel().getBlockEntity(request.pos) == tile && menu.stillValid(player)
                && player.distanceToSqr(request.pos.getCenter()) <= 64 && ResourceHiveHost.canAccess(player, tile);
    }

    private static void reply(ServerPlayer player, Request request) {
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var status = ResourceOutputStatus.capture(tile, player, -1);
        var state = ResourceHiveHost.existing(tile);
		boolean privateState = !status.accountReadable();
        var currentSession = session(player, request);
        var balances = state == null || privateState || state.reference() == null ? java.util.Map.<ResourceKey, java.math.BigInteger>of() : state.account(tile).availableExact();
        var delta = currentSession.publisher.update(balances, request.revision);
        // Jade 保留四项摘要；菜单数量走目录增量，不重复传完整 key。
        var header = new ResourceOutputStatus(status.state(), status.meEnabled(), status.totalKinds(), java.util.List.of());
        int processes = tile instanceof TileEntityMekApiary apiary ? apiary.getBeeSlotCount()
                : tile instanceof com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext factory ? factory.processes() : 1;
        var recipient = state == null || privateState || state.reference() == null || !net.neoforged.fml.ModList.get().isLoaded("projecte")
                ? EmcRecipient.NONE : com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.snapshot(tile, player);
        PacketDistributor.sendToPlayer(player, new Reply(request, header, state == null || privateState ? null : state.reference(),
                state == null || privateState ? 0 : state.capacity(), delta, processes, recipient,
                currentSession.productionPublisher.update(state == null || privateState ? ResourceProductionSnapshot.EMPTY
                        : state.productionMonitor().snapshot(tile.getLevel().getGameTime()), request.productionRevision)));
    }

    /** 测试及网络入口共用；会话、源代际和所有者通过后才允许尝试输出。 */
    public static boolean act(ServerPlayer player, Action action) {
        var request = action.request;
        if (!validSession(player, request)) return false;
        var session = SESSIONS.get(player);
        if (session == null || session.menu != player.containerMenu || !session.id.equals(request.session)
                || request.sequence <= session.sequence) return false;
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var state = ResourceHiveHost.existing(tile);
        if (state == null || !action.source.equals(state.reference())) return false;
        var account = state.account(tile);
        if (!account.owner().equals(player.getUUID()) && !player.hasPermissions(2)) return false;
        if (!account.canExtract(state.reference())) return false;
        session(player, request);
        if (action.toggleMe) state.setMeOutputEnabled(tile, !state.meOutputEnabled());
        else if (action.key != null && account.availableExact().containsKey(action.key)) ResourceManualOutput.eject(tile, action.key);
        reply(player, request);
        return true;
    }

    public static boolean selectEmcRecipient(ServerPlayer player, SelectEmcRecipient action) {
        var request = action.request;
        if (!validSession(player, request) || !net.neoforged.fml.ModList.get().isLoaded("projecte")) return false;
        var session = SESSIONS.get(player);
        if (session == null || session.menu != player.containerMenu || !session.id.equals(request.session)
                || request.sequence <= session.sequence) return false;
        var tile = ((MekanismTileContainer<?>) player.containerMenu).getTileEntity();
        var state = ResourceHiveHost.existing(tile);
        if (state == null || !action.source.equals(state.reference())) return false;
        var account = state.account(tile);
        if ((!account.owner().equals(player.getUUID()) && !player.hasPermissions(2))
                || !account.canExtract(state.reference(), ResourceTypes.EMC)) return false;
        session(player, request);
        boolean changed = com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput.cycle(tile, action.expected);
        reply(player, request);
        return changed;
    }

    @SubscribeEvent public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("5");
        registrar.playToServer(CloseWindow.TYPE, CloseWindow.CODEC, (close, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_window_close", 200)) {
                closeWindow(player, close.request());
            }
        }));
        registrar.playToServer(Request.TYPE, Request.CODEC, (request, context) -> context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !validSession(player, request)
                    || !PayloadRateLimiter.tryAccept(player, "resource_menu", 200)) return;
            var previous = SESSIONS.get(player);
            if (previous != null && previous.menu == player.containerMenu && previous.id.equals(request.session)
                    && request.sequence <= previous.sequence) return;
            session(player, request);
            reply(player, request);
        }));
        registrar.playToServer(Action.TYPE, Action.CODEC, (action, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_menu_action", 200)) {
                try { act(player, action); }
                catch (IllegalStateException | IllegalArgumentException unavailable) { /* 状态已变化或未知结果已由账户保管。 */ }
            }
        }));
        registrar.playToClient(Reply.TYPE, Reply.CODEC, (reply, context) -> context.enqueueWork(() ->
                com.ayoshiko.productivebeesgenesis.client.resource.ResourceMachinePanel.accept(reply)));
        registrar.playToServer(SelectEmcRecipient.TYPE, SelectEmcRecipient.CODEC, (action, context) -> context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player && PayloadRateLimiter.tryAccept(player, "resource_emc_recipient", 200)) {
                try { selectEmcRecipient(player, action); }
                catch (IllegalStateException | IllegalArgumentException unavailable) {
                    ProductiveBeesGenesis.LOGGER.debug("EMC recipient selection rejected after state change", unavailable);
                }
            }
        }));
    }
    @SubscribeEvent public static void logout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity());
    }
    @SubscribeEvent public static void close(net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Close event) {
        SESSIONS.remove(event.getEntity());
    }
    @SubscribeEvent public static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        SESSIONS.keySet().removeIf(player -> player.getServer() == event.getServer());
    }
}
