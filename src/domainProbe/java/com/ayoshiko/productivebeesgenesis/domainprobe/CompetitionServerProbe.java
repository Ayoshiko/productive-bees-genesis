package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
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
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 两名真实玩家的快照屏障、实际提交与独立资产核算；所有世界访问在专服线程。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class CompetitionServerProbe {
    static final UUID OWNER = PlayerLoginServerProbe.OWNER, GUEST = PlayerLoginServerProbe.GUEST;
    private static final List<UUID> IDS = List.of(OWNER, GUEST);
    private static final UUID JVM = UUID.randomUUID();
    private static final Map<UUID, CompetitionSignal> acks = new HashMap<>();
    private static final Map<UUID, UUID> previousSessions = new HashMap<>();
    private static final JsonArray cases = new JsonArray();
    private static NetworkCoreBlockEntity core;
    private static NetworkCheckpoint before;
    private static Map<UUID, ListTag> beforeInventory, finalInventory;
    private static CompetitionAssets.Total initial;
    private static CompoundTag manifest;
    private static String failure;
    private static int stage = -1, maxConcurrent, logins, logouts, replays;
    private static long started, stageAt;
    private static boolean disconnected, reconnected, revoked, regranted, closing;
    private static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.server"); }
    private static boolean reader() { return "read".equals(System.getProperty("pbg.concurrent.mode")); }
    private static Path manifestPath(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("concurrent-probe.dat"); }
    private static List<ServerPlayer> players(MinecraftServer server) {
        return IDS.stream().map(id -> Objects.requireNonNull(server.getPlayerList().getPlayer(id), "Missing concurrent player")).toList();
    }
    private static NetworkCheckpoint checkpoint() { return core.ownership().readyAuthority().checkpoint(); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!enabled()) return;
        started = System.nanoTime(); CompetitionSignal.server = CompetitionServerProbe::receive;
        try {
            require(event.getServer().isDedicatedServer(), "Requires dedicated server");
            ModConfig.SERVER.beeNetwork.enabled.set(true);
            if (reader()) {
                manifest = NbtIo.readCompressed(manifestPath(event.getServer()), NbtAccounter.unlimitedHeap());
                require(manifest.getInt("schema") == 1 && !manifest.getUUID("jvm").equals(JVM), "Wrong concurrent writer");
            }
            var ready = new JsonObject(); ready.addProperty("ready", true); write("concurrent-ready.json", ready);
        } catch (Exception error) { fail(event.getServer(), error); }
    }
    private static void receive(ServerPlayer player, CompetitionSignal signal) {
        if (!enabled() || failure != null) return;
        try {
            require(IDS.contains(player.getUUID()) && player.server.getPlayerList().getPlayer(player.getUUID()) == player
                    && player.server.isSameThread(), "Foreign or off-thread probe acknowledgment");
            if (signal.status() == -3) throw new IllegalStateException("Client probe failed: " + player.getGameProfile().getName());
            if (signal.stage() == stage) require(acks.putIfAbsent(player.getUUID(), signal) == null, "Duplicate stage acknowledgment");
        } catch (Exception error) { fail(player.server, error); }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player) || failure != null) return;
        try {
            require(!(player instanceof FakePlayer) && IDS.contains(player.getUUID()), "Unexpected login");
            if (WirelessProbe.visualOnly()) player.connection.teleport(player.getUUID().equals(OWNER) ? 8.5 : 9.5, 100, 10.5, 0, 0);
            logins++;
            if (logins > 2) require(!reader() && player.getUUID().equals(GUEST) && disconnected && !reconnected, "Unexpected repeat login");
        } catch (Exception error) { fail(player.server, error); }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || failure != null) return;
        var server = event.getServer();
        try {
            require(System.nanoTime() - started < 600_000_000_000L, "Concurrent server timeout at " + stage);
            int count = server.getPlayerList().getPlayerCount(); maxConcurrent = Math.max(maxConcurrent, count);
            require(count <= 2, "Unexpected third player");
            if (closing) { if (count == 0) server.halt(false); return; }
            if (stage == 51 && server.getPlayerList().getPlayer(GUEST) == null) { begin(server, 52); return; }
            if (stage == 52) {
                if (count != 2) return;
                var guest = server.getPlayerList().getPlayer(GUEST);
                require(beforeInventory.get(GUEST).equals(guest.getInventory().save(new ListTag())), "Reconnect changed guest inventory");
                core.openTerminal(guest); require(guest.containerMenu instanceof NetworkCoreMenu, "Reconnect could not open core");
                require(!((NetworkCoreMenu) guest.containerMenu).terminalSession().equals(previousSessions.get(GUEST)), "Reconnect revived old session");
                reconnected = true; begin(server, 53); return;
            }
            if (count != 2) return;
            var players = players(server); var owner = players.getFirst();
            if (stage == -1) {
                if (core == null) { initialize(server, players); return; }
                if (core.topology() == null || !core.topology().valid()) return;
                if (reader()) {
                    if (core.ownership().readyAuthority() == null || MeBridgeProbe.enabled() && !MeBridgeProbe.recoveryReady(core)) return;
                    verifyRecovered(server, players);
                    if (CraftingProbe.enabled()) CraftingProbe.recovered(core, manifest);
                    if (MeBridgeProbe.enabled()) MeBridgeProbe.recovered(core, players, manifest);
                    if (UpgradeCompetitionProbe.enabled()) UpgradeCompetitionRecovery.resume(core, players);
                    if (CraftingProbe.enabled()) CraftingProbe.open(core, players, true); else if (MeBridgeProbe.enabled()) CraftingProbe.open(core, players, false); else openBoth(players);
                    for (var p : players) require(!((NetworkCoreMenu) p.containerMenu).terminalSession().equals(manifest.getUUID("session-" + p.getUUID())), "Restart revived a menu");
                    if (WirelessProbe.visualOnly()) { WirelessProbe.advance(core, players, 348, Map.of()); begin(server, 349); return; }
                    begin(server, 80);
                } else { core.openTerminal(owner); begin(server, 0); }
                return;
            }
            require(server.overworld().getGameTime() - stageAt < 1200, "Stage timeout: " + stage + "; acknowledgments=" + acks);
            if (stage == 0) {
                if (!acks.containsKey(OWNER) || core.ownership().readyAuthority() == null || !core.allowed(players.get(1))) return;
                require(!core.productionRunning() && checkpoint().ownedMachines().values().stream().anyMatch(r -> r.bees() != null), "Setup did not activate then pause");
                if (MeBridgeProbe.enabled()) { MeBridgeProbe.seed(core, players); begin(server, 500); return; }
                if (CraftingProbe.enabled()) { CraftingProbe.seed(core, players); begin(server, 300); return; }
                CompetitionAssets.seed(core, players); initial = CompetitionAssets.capture(core, players); openBoth(players); begin(server, 1); return;
            }
            if (acks.size() != 2) return;
            if (MeCraftingProbe.enabled() && stage >= 600) {
                if (!MeCraftingProbe.ready(stage)) return;
                int next = MeCraftingProbe.advance(core, players, stage); noDrops(server);
                if (next < 0) finish(server); else begin(server, next); return;
            }
            if (MeBridgeProbe.enabled() && stage >= 500) {
                if (!MeBridgeProbe.ready(stage)) return;
                int next = MeBridgeProbe.advance(core, players, stage); noDrops(server);
                if (next < 0) finish(server); else begin(server, next); return;
            }
            if (RecipeFillProbe.enabled() && stage >= 400) {
                int next = RecipeFillProbe.advance(core, players, stage, acks); noDrops(server);
                if (next < 0) finish(server); else begin(server, next); return;
            }
            if (WirelessProbe.enabled() && stage >= 330) {
                if (!WirelessProbe.ready(stage)) return;
                int next = WirelessProbe.advance(core, players, stage, acks); noDrops(server);
                if (next < 0) finish(server); else begin(server, next); return;
            }
            if (CraftingProbe.enabled() && stage >= 300) {
                int next = CraftingProbe.advance(core, players, stage, acks); noDrops(server);
                if (next < 0) finish(server); else begin(server, next); return;
            }
            if (TerminalPermissionProbe.enabled() && stage >= 200) {
                if (!TerminalPermissionProbe.ready(core, players, stage)) return;
                int next = TerminalPermissionProbe.advance(core, players, stage, acks, before, beforeInventory);
                noDrops(server);
                if (next < 0) finish(server); else begin(server, next);
                return;
            }
            if (UpgradeCompetitionProbe.enabled() && stage >= 100) {
                if (!UpgradeCompetitionProbe.ready(core, players, stage)) return;
                int next = UpgradeCompetitionProbe.advance(core, players, stage, acks, before, beforeInventory);
                noDrops(server);
                if (next < 0) finish(server); else begin(server, next);
                return;
            }
            if (stage == 1) {
                conserved(server); require(networkBees() == 1 && networkFood() == 1, "UI preparation incomplete");
                begin(server, 10); return;
            }
            if (stage >= 10 && stage <= 33) {
                int round = (stage - 10) / 3, part = (stage - 10) % 3;
                if (part == 0) { unchanged(server); begin(server, stage + 1); }
                else if (part == 1) { verifyCase(server, round); begin(server, stage + 1); }
                else {
                    unchanged(server); replays++;
                    if (round == 6) begin(server, 40);
                    else if (round == 7) begin(server, 50);
                    else begin(server, stage + 1);
                }
                return;
            }
            switch (stage) {
                case 40 -> { unchanged(server); saveSessions(players); begin(server, 41); }
                case 41 -> {
                    if (core.allowed(players.get(1))) return;
                    unchanged(server); revoked = true; begin(server, 42);
                }
                case 42 -> { unchanged(server); begin(server, 43); }
                case 43 -> {
                    if (!core.allowed(players.get(1))) return;
                    unchanged(server); begin(server, 44);
                }
                case 44 -> {
                    unchanged(server); regranted = true; openBoth(players);
                    for (var player : players) require(!((NetworkCoreMenu) player.containerMenu).terminalSession().equals(previousSessions.get(player.getUUID())), "Regrant revived a menu");
                    begin(server, 31);
                }
                case 50 -> { unchanged(server); saveSessions(players); begin(server, 51); }
                case 53 -> { unchanged(server); begin(server, 54); }
                case 54 -> { unchanged(server); begin(server, 55); }
                case 55 -> {
                    verifyCase(server, 8);
                    if (TerminalPermissionProbe.enabled()) { TerminalPermissionProbe.seed(core, players); begin(server, 200); }
                    else if (UpgradeCompetitionProbe.enabled()) { UpgradeCompetitionProbe.seed(core, players); begin(server, 100); }
                    else finish(server);
                }
                case 80 -> { unchanged(server); finish(server); }
                default -> throw new IllegalStateException("Unexpected stage " + stage);
            }
        } catch (Exception error) { fail(server, error); }
    }
    private static void initialize(MinecraftServer server, List<ServerPlayer> players) {
        var level = server.overworld(); var pos = PlayerLoginServerProbe.POS;
        level.setChunkForced(0, 0, true);
        if (reader()) {
            core = (NetworkCoreBlockEntity) level.getBlockEntity(pos);
            require(core != null && core.owner().equals(OWNER) && core.controller().equals(manifest.getUUID("controller")), "Core identity lost");
        } else {
            require(players.stream().allMatch(p -> p.getInventory().isEmpty()), "Writer needs fresh players");
            for (int x = 6; x <= 11; x++) for (int z = 6; z <= 11; z++) level.setBlockAndUpdate(new net.minecraft.core.BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
            level.setBlockAndUpdate(pos, NetworkContent.CORE.get().defaultBlockState());
            core = (NetworkCoreBlockEntity) level.getBlockEntity(pos); core.initializeOwner(OWNER);
            level.setBlockAndUpdate(pos.east(), ModBlocks.MEK_APIARY.get().defaultBlockState());
            var hive = (TileEntityMekApiary) level.getBlockEntity(pos.east()); hive.setOwnerUUID(OWNER); hive.setFeederConversionEnabled(false);
            if (UpgradeCompetitionProbe.enabled() || TerminalPermissionProbe.enabled()) {
                level.setBlockAndUpdate(pos.east(2), ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
                ((com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge) level.getBlockEntity(pos.east(2))).setOwnerUUID(OWNER);
            }
            if (TerminalPermissionProbe.enabled()) TerminalPermissionProbe.place(core);
            if (CraftingProbe.enabled() || MeBridgeProbe.enabled()) CraftingProbe.place(core);
        }
        for (var player : players) player.teleportTo(8.5, 100, 10.5);
    }
    private static void begin(MinecraftServer server, int next) {
        stage = next; stageAt = server.overworld().getGameTime(); acks.clear();
        if (UpgradeCompetitionProbe.enabled() && next >= 100) com.mojang.logging.LogUtils.getLogger().info("UPGRADE_COMPETITION_STAGE {}", next);
        if (TerminalPermissionProbe.enabled() && next >= 200) com.mojang.logging.LogUtils.getLogger().info("TERMINAL_PERMISSION_STAGE {}", next);
        if (MeBridgeProbe.enabled() && next >= 500) com.mojang.logging.LogUtils.getLogger().info("ME_BRIDGE_STAGE {}", next);
        if (CraftingProbe.enabled() && next >= 300) com.mojang.logging.LogUtils.getLogger().info("TERMINAL_CRAFTING_STAGE {}", next);
        if (core.ownership().readyAuthority() != null && server.getPlayerList().getPlayerCount() == 2) {
            before = checkpoint(); beforeInventory = CompetitionAssets.inventories(players(server));
        }
        for (var player : server.getPlayerList().getPlayers()) PacketDistributor.sendToPlayer(player, new CompetitionSignal(stage, 0, -1));
    }
    private static void openBoth(List<ServerPlayer> players) { for (var player : players) core.openTerminal(player); }
    private static void saveSessions(List<ServerPlayer> players) {
        for (var player : players) previousSessions.put(player.getUUID(), ((NetworkCoreMenu) player.containerMenu).terminalSession());
    }
    private static void unchanged(MinecraftServer server) {
        require(checkpoint() == before && CompetitionAssets.inventories(players(server)).equals(beforeInventory), "Rejected/query command changed assets at " + stage);
        noDrops(server);
    }
    private static void conserved(MinecraftServer server) {
        require(initial.equals(CompetitionAssets.capture(core, players(server))), "Player/network asset conservation failed at " + stage);
        require(checkpoint().energy().equals(before.energy()) && checkpoint().scheduler().equals(before.scheduler())
                && checkpoint().transfers().equals(before.transfers()), "Exchange changed paid work or FE");
        noDrops(server);
    }
    private static int networkBees() {
        int count = 0; for (var r : checkpoint().ownedMachines().activeValues()) if (r.bees() != null) count += r.bees().bees().size(); return count;
    }
    private static int networkFood() {
        int count = 0; for (var r : checkpoint().ownedMachines().activeValues()) if (r.bees() != null) for (var s : r.bees().feeding().slots()) count += s.count(); return count;
    }
    private static void verifyCase(MinecraftServer server, int round) {
        var test = CompetitionSignal.Case.values()[round]; conserved(server);
        int moved = acks.values().stream().mapToInt(CompetitionSignal::moved).sum();
        require(moved == test.moved, "Wrong actual replies for " + test + ": " + acks);
        for (var result : acks.values()) {
            require(result.status() >= 0 && result.status() < TerminalReply.Status.values().length, "Missing terminal result");
            require((result.status() == TerminalReply.Status.MOVED.ordinal()) == (result.moved() > 0), "Reply status/amount inconsistent");
        }
        if (test.operation == com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.TAKE_PRODUCT) {
            var target = before.ledger().balances().keySet().stream().filter(k -> k.id().toString().equals(test.id)
                    && (test.variant.equals("__plain__") ? !k.hasComponent("minecraft:custom_name") : k.componentPreview().contains(test.variant)))
                    .findFirst().orElseThrow();
            var expected = new HashMap<>(before.ledger().balances());
            var remaining = expected.get(target).subtract(com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(test.moved));
            if (remaining.isZero()) expected.remove(target); else expected.put(target, remaining);
            require(checkpoint().ledger().balances().equals(expected), "Wrong product key or remainder for " + test);
        } else require(checkpoint().ledger().equals(before.ledger()), "Food/cage changed product ledger");
        if (round == 1 || round == 7) require(acks.values().stream().allMatch(a -> a.moved() == 1), "Both partial destinations must receive one");
        if (round == 2) require(acks.values().stream().allMatch(a -> a.status() == TerminalReply.Status.NO_SPACE.ordinal()), "Full inventory did not refuse");
        if (round == 5) require(networkFood() == 0, "Food retained in network");
        if (round == 6) require(networkBees() == 0, "Bee retained in network");
        var record = new JsonObject(); record.addProperty("case", test.name()); record.addProperty("moved", moved);
        record.addProperty("twoPlayersOnline", server.getPlayerList().getPlayerCount() == 2);
        record.addProperty("ownerMoved", acks.get(OWNER).moved()); record.addProperty("guestMoved", acks.get(GUEST).moved()); cases.add(record);
    }
    private static void finish(MinecraftServer server) throws Exception {
        var players = players(server); finalInventory = CompetitionAssets.inventories(players);
        if (!reader()) {
            if (!CraftingProbe.enabled() && !MeBridgeProbe.enabled()) require(cases.size() == 9 && replays == 8 && revoked && regranted && reconnected, "Incomplete competition matrix");
            manifest = new CompoundTag(); manifest.putInt("schema", 1); manifest.putUUID("jvm", JVM);
            manifest.putUUID("controller", core.controller()); manifest.putLong("producerPid", ProcessHandle.current().pid());
            manifest.put("checkpoint", NetworkCheckpointCodec.encode(checkpoint())); manifest.put("core", core.saveWithoutMetadata(server.registryAccess()));
            if (CraftingProbe.enabled()) CraftingProbe.capture(core, manifest);
            if (MeBridgeProbe.enabled()) MeBridgeProbe.capture(core, manifest);
            for (var player : players) {
                manifest.put(player.getUUID().toString(), finalInventory.get(player.getUUID()));
                manifest.putUUID("session-" + player.getUUID(), ((NetworkCoreMenu) player.containerMenu).terminalSession());
            }
            NbtIo.writeCompressed(manifest, manifestPath(server));
        }
        if (reader() && UpgradeCompetitionProbe.enabled()) manifest.put("checkpoint", NetworkCheckpointCodec.encode(checkpoint()));
        closing = true; begin(server, 90);
    }
    private static void verifyRecovered(MinecraftServer server, List<ServerPlayer> players) {
        var codec = NetworkCheckpointCodec.forRegistries(server.registryAccess());
        require(codec.decode(manifest.getCompound("checkpoint")).equals(checkpoint()), "Restored full network differs");
        var saved = core.saveWithoutMetadata(server.registryAccess()); var expected = manifest.getCompound("core");
        for (String key : List.of("owner", "controller", "network", "access", "productionMode"))
            require(Objects.equals(saved.get(key), expected.get(key)), "Restored core differs: " + key);
        require(core.guests(players.getFirst()).equals(List.of(GUEST)), "Restored grant differs");
        for (var player : players) require(player.getInventory().save(new ListTag()).equals(manifest.getList(player.getUUID().toString(), Tag.TAG_COMPOUND)), "Restored player file differs");
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!enabled() || failure != null || !(event.getEntity() instanceof ServerPlayer player)) return;
        try {
            logouts++;
            if (!closing) {
                require(stage == 51 && player.getUUID().equals(GUEST) && !disconnected, "Unexpected disconnect at " + stage);
                require(beforeInventory.get(GUEST).equals(player.getInventory().save(new ListTag())), "Disconnect lost inventory");
                disconnected = true; return;
            }
            require(finalInventory.get(player.getUUID()).equals(player.getInventory().save(new ListTag())), "Inventory changed while closing");
            noDrops(player.server);
        } catch (Exception error) { fail(player.server, error); }
    }
    private static void noDrops(MinecraftServer server) {
        require(server.overworld().getEntitiesOfClass(ItemEntity.class, new AABB(PlayerLoginServerProbe.POS).inflate(16)).isEmpty(), "Competition created dropped items");
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (!enabled()) return;
        var server = event.getServer(); var report = new JsonObject();
        report.addProperty("mode", reader() ? "read" : "write"); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        report.addProperty("ae2Version", ModList.get().getModContainerById("ae2").map(container -> container.getModInfo().getVersion().toString()).orElse(""));
        report.addProperty("pid", ProcessHandle.current().pid()); report.addProperty("maxConcurrent", maxConcurrent);
        report.addProperty("logins", logins); report.addProperty("logouts", logouts); report.add("cases", cases);
        try {
            require(failure == null, failure);
            require(closing && maxConcurrent == 2 && logins == (reader() || CraftingProbe.enabled() || MeBridgeProbe.enabled() ? 2 : 3) && logouts == logins, "Incomplete real player lifecycle");
            var paths = new JsonObject();
            for (UUID id : IDS) {
                var path = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
                require(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getList("Inventory", Tag.TAG_COMPOUND).equals(finalInventory.get(id)), "Saved player differs");
                paths.addProperty(id.equals(OWNER) ? "owner" : "guest", path.toAbsolutePath().toString());
            }
            var path = server.getWorldPath(LevelResource.ROOT).resolve("data/productivebeesgenesis_network_" + core.network().networkId() + ".dat");
            var codec = NetworkCheckpointCodec.forRegistries(server.registryAccess());
            require(codec.decode(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data")).equals(codec.decode(manifest.getCompound("checkpoint"))), "Saved domain differs");
            report.add("playerFiles", paths); report.addProperty("domainFile", path.toAbsolutePath().toString());
            report.addProperty("producerPid", manifest.getLong("producerPid")); report.addProperty("normalPlayerFilesVerified", true);
            report.addProperty("replays", replays); report.addProperty("revocationAndRegrant", revoked && regranted);
            report.addProperty("reconnectAndOldSessionRejected", disconnected && reconnected);
            if (UpgradeCompetitionProbe.enabled()) {
                UpgradeCompetitionProbe.report(report); UpgradeCompetitionRecovery.report(report, reader());
            }
            if (TerminalPermissionProbe.enabled()) {
                if (reader()) report.addProperty("terminalCheckpointAndPlayerRecovery", true);
                else TerminalPermissionProbe.report(report);
            }
            if (CraftingProbe.enabled()) CraftingProbe.report(server, core, manifest, report, reader());
            if (MeBridgeProbe.enabled()) MeBridgeProbe.report(report, reader());
            if (MeCraftingProbe.enabled()) MeCraftingProbe.report(report, reader());
            report.addProperty("passed", true);
        } catch (Exception error) { report.addProperty("passed", false); report.addProperty("failure", error.toString()); }
        try { write("concurrent-server.json", report); } catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write competition report", error); }
        CompetitionSignal.server = null; core = null; before = null; beforeInventory = null; finalInventory = null; initial = null; manifest = null; acks.clear(); previousSessions.clear();
    }
    private static void fail(MinecraftServer server, Exception error) {
        failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("CONCURRENT_SERVER_FAILED at {}", stage, error); server.halt(false);
    }
    private static void write(String name, JsonObject report) throws java.io.IOException {
        Files.createDirectories(Path.of("results")); Files.writeString(Path.of("results", name), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private CompetitionServerProbe() {}
}
