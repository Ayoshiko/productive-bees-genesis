package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 三个独立真实登录按角色顺序验收；reader 不注入资产或重建世界。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class PlayerExchangeServerProbe {
    static final BlockPos POS = PlayerLoginServerProbe.POS;
    static final UUID OWNER = PlayerLoginServerProbe.OWNER, GUEST = PlayerLoginServerProbe.GUEST;
    static final UUID STRANGER = PlayerLoginServerProbe.offline("PbgLoginStranger");
    private static final UUID JVM = UUID.randomUUID();
    private static final List<UUID> ROLES = List.of(OWNER, GUEST, STRANGER);
    private static final Set<UUID> initialized = new HashSet<>();
    private static NetworkCoreBlockEntity core;
    private static CompoundTag manifest, checkpoint;
    private static GuestExchangeAssertions oracle;
    private static String failure;
    private static int logins, logouts;
    private static long started;
    private static boolean guestVerified, strangerDenied;
    private static UUID guestSession;
    private static boolean enabled() { return Boolean.getBoolean("pbg.login.server") && Boolean.getBoolean("pbg.exchange.enabled"); }
    private static boolean reader() { return "read".equals(System.getProperty("pbg.login.mode")); }
    private static Path manifestPath(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("player-login-probe.dat"); }
    private static Path playerFile(MinecraftServer server, UUID id) { return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat"); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!enabled()) return;
        started = System.nanoTime();
        try {
            require(event.getServer().isDedicatedServer(), "Exchange needs dedicated TCP server");
            ModConfig.SERVER.beeNetwork.enabled.set(true);
            if (reader()) {
                manifest = NbtIo.readCompressed(manifestPath(event.getServer()), NbtAccounter.unlimitedHeap());
                require(manifest.getInt("schema") == 2 && !manifest.getUUID("jvm").equals(JVM)
                        && manifest.getUUID("guest").equals(GUEST), "Wrong exchange writer");
                checkpoint = manifest.getCompound("checkpoint");
                require(Files.isRegularFile(playerFile(event.getServer(), GUEST)), "Guest player file absent");
            }
            var ready = new JsonObject(); ready.addProperty("ready", true); write("player-login-ready.json", ready);
        } catch (Exception error) { fail(event.getServer(), error); }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || failure != null || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            require(!(player instanceof FakePlayer) && logins == logouts && logins < ROLES.size()
                    && player.getUUID().equals(ROLES.get(logins)), "Unexpected real login order");
            logins++;
        } catch (Exception error) { fail(player.server, error); }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || failure != null || started == 0) return;
        var server = event.getServer();
        try {
            require(System.nanoTime() - started < 600_000_000_000L, "Exchange server timeout");
            if (logouts == 3 && server.getPlayerList().getPlayerCount() == 0) { server.halt(false); return; }
            if (server.getPlayerList().getPlayerCount() == 0) return;
            require(server.getPlayerList().getPlayerCount() == 1, "Competition belongs to a separate gate");
            var player = server.getPlayerList().getPlayers().getFirst();
            if (initialized.contains(player.getUUID())) return;
            var level = server.overworld();
            if (core == null) {
                level.setChunkForced(0, 0, true);
                if (reader()) {
                    core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
                    require(core != null && core.owner().equals(OWNER)
                            && core.controller().equals(manifest.getUUID("controller")), "Core identity lost");
                } else {
                    require(player.getUUID().equals(OWNER) && player.getInventory().isEmpty(), "Requires fresh owner");
                    for (int x = 6; x <= 11; x++) for (int z = 6; z <= 11; z++)
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
                    core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(OWNER);
                    level.setBlockAndUpdate(POS.east(), ModBlocks.MEK_APIARY.get().defaultBlockState());
                    var hive = (TileEntityMekApiary) level.getBlockEntity(POS.east());
                    hive.setOwnerUUID(OWNER); hive.setFeederConversionEnabled(false);
                }
            }
            if (core.topology() == null || !core.topology().valid()) return;
            if (reader() || !player.getUUID().equals(OWNER)) {
                if (core.ownership().readyAuthority() == null) return;
            }
            player.teleportTo(8.5, 100, 10.5);
            if (player.getUUID().equals(OWNER)) {
                require(player.getInventory().isEmpty(), "Owner acquired guest assets");
                if (reader()) {
                    require(core.guests(player).equals(List.of(GUEST)), "Restored access table differs");
                    verifyRestoredCore(player); verifyCheckpoint();
                }
            } else if (player.getUUID().equals(GUEST)) {
                require(core.allowed(player) && !core.ownerAllowed(player), "Guest lacks least-privilege access");
                if (reader()) {
                    verifyGuestInventory(player); verifyCheckpoint();
                } else {
                    require(player.getInventory().isEmpty(), "Requires fresh guest");
                    ClientTerminalFixture.requested = true;
                    ClientTerminalFixture.tick(core, player);
                    if (!ClientTerminalFixture.ready) return;
                    oracle = new GuestExchangeAssertions(core, player);
                }
            } else {
                require(!core.allowed(player) && core.createMenu(77, player.getInventory(), player) == null, "Stranger menu allowed");
                verifyCheckpoint(); strangerDenied = true;
            }
            core.openTerminal(player);
            if (player.getUUID().equals(STRANGER)) require(!(player.containerMenu instanceof NetworkCoreMenu), "Stranger opened core");
            else {
                require(player.containerMenu instanceof NetworkCoreMenu, "Authorized login did not open core");
                if (player.getUUID().equals(GUEST)) {
                    guestSession = ((NetworkCoreMenu) player.containerMenu).terminalSession();
                    if (reader()) require(!guestSession.equals(manifest.getUUID("session")), "Guest session revived");
                }
            }
            initialized.add(player.getUUID());
        } catch (Exception error) { fail(server, error); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!enabled() || failure != null || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            require(initialized.contains(player.getUUID()) && player.getUUID().equals(ROLES.get(logouts)), "Early or unordered disconnect");
            noDrops(player.server);
            if (player.getUUID().equals(OWNER)) {
                require(core.ownership().readyAuthority() != null && core.guests(player).equals(List.of(GUEST))
                        && !core.productionRunning() && player.getInventory().isEmpty(), "Owner setup incomplete");
            } else if (player.getUUID().equals(GUEST)) {
                if (reader()) { verifyGuestInventory(player); verifyCheckpoint(); }
                else {
                    oracle.verify(core, player);
                    checkpoint = NetworkCheckpointCodec.encode(core.ownership().readyAuthority().checkpoint());
                    manifest = new CompoundTag(); manifest.putInt("schema", 2);
                    manifest.putUUID("jvm", JVM); manifest.putUUID("guest", GUEST);
                    manifest.putUUID("controller", core.controller()); manifest.putUUID("session", guestSession);
                    manifest.putLong("producerPid", ProcessHandle.current().pid());
                    manifest.put("inventory", player.getInventory().save(new ListTag()));
                    manifest.put("checkpoint", checkpoint.copy());
                    manifest.put("core", core.saveWithoutMetadata(player.registryAccess()));
                    NbtIo.writeCompressed(manifest, manifestPath(player.server));
                }
                guestVerified = true;
            } else {
                require(strangerDenied && player.getInventory().isEmpty(), "Stranger acquired inventory");
                verifyCheckpoint();
            }
            logouts++;
        } catch (Exception error) { fail(player.server, error); }
    }
    private static void verifyRestoredCore(ServerPlayer player) {
        var actual = core.saveWithoutMetadata(player.registryAccess()); var expected = manifest.getCompound("core");
        for (String field : List.of("owner", "controller", "network", "access", "productionMode", "closedFaces"))
            require(Objects.equals(actual.get(field), expected.get(field)), "Restored core differs: " + field);
    }
    private static void verifyGuestInventory(ServerPlayer player) {
        require(player.getInventory().save(new ListTag()).equals(manifest.getList("inventory", Tag.TAG_COMPOUND)), "Guest full player inventory differs");
    }
    private static void verifyCheckpoint() {
        var actual = NetworkCheckpointCodec.encode(core.ownership().readyAuthority().checkpoint());
        if (checkpoint == null || !sameCheckpoint(actual)) {
            try {
                var wrapper = new CompoundTag(); wrapper.put("data", actual);
                NbtIo.writeCompressed(wrapper, Path.of("results/checkpoint-observed.dat"));
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            throw new IllegalStateException("Network exact checkpoint differs from independent verified exchange");
        }
    }
    private static boolean sameCheckpoint(CompoundTag actual) {
        // 余额是无序映射；跨 JVM 的序列化列表顺序不是资产身份。
        // 严格解码后仍比较全部完整组件、数量、revision、蜂位、食物及调度状态。
        var codec = NetworkCheckpointCodec.forRegistries(core.getLevel().registryAccess());
        return codec.decode(checkpoint).equals(codec.decode(actual));
    }
    private static void noDrops(MinecraftServer server) {
        require(server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(16)).isEmpty(), "Exchange created item entities");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (!enabled()) return;
        var server = event.getServer(); var report = new JsonObject();
        report.addProperty("mode", reader() ? "read" : "write"); report.addProperty("guestExchange", true);
        report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        report.addProperty("pid", ProcessHandle.current().pid()); report.addProperty("guest", GUEST.toString());
        report.addProperty("logins", logins); report.addProperty("logouts", logouts);
        try {
            require(failure == null, failure);
            require(guestVerified && strangerDenied && logins == 3 && logouts == 3, "Incomplete three-role exchange");
            var player = NbtIo.readCompressed(playerFile(server, GUEST), NbtAccounter.unlimitedHeap());
            require(player.getList("Inventory", Tag.TAG_COMPOUND).equals(manifest.getList("inventory", Tag.TAG_COMPOUND)), "Vanilla guest file differs");
            for (UUID id : List.of(OWNER, STRANGER))
                require(NbtIo.readCompressed(playerFile(server, id), NbtAccounter.unlimitedHeap()).getList("Inventory", Tag.TAG_COMPOUND).isEmpty(),
                        "Non-guest player file acquired assets");
            var domainFile = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_"
                    + core.network().networkId() + ".dat");
            require(sameCheckpoint(NbtIo.readCompressed(domainFile, NbtAccounter.unlimitedHeap()).getCompound("data")), "Stopped network file differs");
            report.addProperty("playerFile", playerFile(server, GUEST).toAbsolutePath().toString());
            report.addProperty("domainFile", domainFile.toAbsolutePath().toString());
            report.addProperty("producerPid", manifest.getLong("producerPid"));
            report.addProperty("normalPlayerFileVerified", true); report.addProperty("threeWayConservation", true);
            report.addProperty("strangerDenied", true); report.addProperty("newJvm", reader());
            report.addProperty("guestSession", guestSession.toString()); report.addProperty("passed", true);
        } catch (Exception error) { report.addProperty("passed", false); report.addProperty("failure", error.toString()); }
        try { write("player-login-server.json", report); }
        catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write exchange report", error); }
        core = null; manifest = null; checkpoint = null; oracle = null; initialized.clear();
    }
    private static void fail(MinecraftServer server, Exception error) {
        failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("PLAYER_EXCHANGE_FAILED", error); server.halt(false);
    }
    private static void write(String name, JsonObject report) throws java.io.IOException {
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results", name), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private PlayerExchangeServerProbe() {}
}
