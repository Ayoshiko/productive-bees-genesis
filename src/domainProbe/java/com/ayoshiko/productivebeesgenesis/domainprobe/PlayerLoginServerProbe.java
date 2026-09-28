package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
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

/** 独立专服真实登录和正常 player.dat 恢复；不创建假玩家，不注入玩家加载数据。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class PlayerLoginServerProbe {
    static final String OWNER_NAME = "PbgLoginOwner";
    static final UUID OWNER = offline(OWNER_NAME), GUEST = offline("PbgLoginGuest");
    static final BlockPos POS = new BlockPos(8, 100, 8);
    private static final UUID JVM = UUID.randomUUID();
    private static NetworkCoreBlockEntity core;
    private static ItemStack expected;
    private static CompoundTag manifest;
    private static String failure;
    private static boolean initialized, verified, loggedOut;
    private static int logins, logouts;
    private static long started;
    private static UUID session;

    static UUID offline(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    private static boolean enabled() { return Boolean.getBoolean("pbg.login.server"); }
    private static boolean reader() { return "read".equals(System.getProperty("pbg.login.mode")); }
    private static Path manifestPath(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("player-login-probe.dat"); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!enabled()) return;
        started = System.nanoTime();
        try {
            require(event.getServer().isDedicatedServer(), "Requires a dedicated server");
            if (reader()) {
                manifest = NbtIo.readCompressed(manifestPath(event.getServer()), NbtAccounter.unlimitedHeap());
                require(manifest.getInt("schema") == 1 && manifest.getUUID("owner").equals(OWNER)
                        && manifest.getUUID("guest").equals(GUEST) && !manifest.getUUID("jvm").equals(JVM), "Wrong writer identity");
                require(Files.isRegularFile(playerFile(event.getServer())), "Normal player file missing before login");
            }
            var ready = new JsonObject(); ready.addProperty("ready", true);
            write("player-login-ready.json", ready);
        } catch (Exception error) { fail(event.getServer(), error); }
    }

    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled()) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            logins++;
            try {
                require(!(player instanceof FakePlayer) && player.getUUID().equals(OWNER)
                        && player.getGameProfile().getName().equals(OWNER_NAME), "Unexpected login identity");
            } catch (Exception error) { fail(player.server, error); }
        }
    }

    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!enabled()) return;
        if (event.getEntity() instanceof ServerPlayer player) {
            logouts++;
            try {
                require(verified && player.getUUID().equals(OWNER), "Disconnected before server verification");
                verifyInventory(player);
                require(core.guests(player).equals(java.util.List.of(GUEST)), "Grant lost before disconnect");
                if (!reader()) {
                    manifest = new CompoundTag(); manifest.putInt("schema", 1);
                    manifest.putUUID("owner", OWNER); manifest.putUUID("guest", GUEST);
                    manifest.putUUID("controller", core.controller()); manifest.putUUID("jvm", JVM);
                    manifest.putUUID("session", session); manifest.putLong("producerPid", ProcessHandle.current().pid());
                    manifest.put("stack", expected.save(player.registryAccess()));
                    NbtIo.writeCompressed(manifest, manifestPath(player.server));
                }
                loggedOut = true;
            } catch (Exception error) { fail(player.server, error); }
        }
    }

    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if (!enabled() || failure != null || started == 0) return;
        var server = event.getServer();
        try {
            require(System.nanoTime() - started < 180_000_000_000L, "Login server timed out");
            if (loggedOut && server.getPlayerList().getPlayerCount() == 0) { server.halt(false); return; }
            var player = server.getPlayerList().getPlayer(OWNER);
            if (player == null) return;
            require(logins == 1 && !(player instanceof FakePlayer), "Requires exactly one real login");
            if (!initialized) {
                ModConfig.SERVER.beeNetwork.enabled.set(true);
                var level = server.overworld(); level.setChunkForced(0, 0, true);
                if (reader()) {
                    core = (NetworkCoreBlockEntity) level.getBlockEntity(POS);
                    require(core != null && core.owner().equals(OWNER) && core.controller().equals(manifest.getUUID("controller")),
                            "Core identity did not restore from chunk");
                    expected = ItemStack.parseOptional(player.registryAccess(), manifest.getCompound("stack"));
                    require(!expected.isEmpty(), "Writer inventory oracle is invalid");
                    verifyInventory(player);
                    require(core.guests(player).equals(java.util.List.of(GUEST)), "Saved UUID grant did not restore");
                } else {
                    require(player.getInventory().isEmpty(), "Writer requires a fresh player");
                    for (int x = 6; x <= 10; x++) for (int z = 6; z <= 11; z++)
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(POS, NetworkContent.CORE.get().defaultBlockState());
                    core = (NetworkCoreBlockEntity) level.getBlockEntity(POS); core.initializeOwner(player.getUUID());
                    var marker = new CompoundTag(); marker.putUUID("pbg_login_marker", UUID.randomUUID());
                    expected = new ItemStack(Items.DIAMOND, 17); expected.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
                    player.getInventory().setItem(0, expected.copy());
                    player.teleportTo(8.5, 100, 10.5);
                }
                core.openTerminal(player);
                require(player.containerMenu instanceof NetworkCoreMenu, "Real login could not open core");
                session = ((NetworkCoreMenu) player.containerMenu).terminalSession();
                if (reader()) require(!session.equals(manifest.getUUID("session")), "Old terminal session revived");
                initialized = true; return;
            }
            verifyInventory(player);
            if (core.guests(player).equals(java.util.List.of(GUEST))) verified = true;
        } catch (Exception error) { fail(server, error); }
    }

    private static void verifyInventory(ServerPlayer player) {
        var inventory = player.getInventory();
        require(inventory.getItem(0).getCount() == 17 && ItemStack.isSameItemSameComponents(expected, inventory.getItem(0)),
                "Real player inventory differs in amount or components");
        for (int slot = 1; slot < inventory.getContainerSize(); slot++) require(inventory.getItem(slot).isEmpty(), "Unexpected inventory asset");
        require(player.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(8)).isEmpty(), "Fixture created dropped items");
    }

    private static Path playerFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(OWNER + ".dat");
    }

    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        if (!enabled()) return;
        var report = new JsonObject();
        report.addProperty("mode", reader() ? "read" : "write");
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        report.addProperty("owner", OWNER.toString()); report.addProperty("guest", GUEST.toString());
        report.addProperty("logins", logins); report.addProperty("logouts", logouts);
        report.addProperty("newJvm", reader());
        try {
            require(failure == null, failure);
            require(initialized && verified && loggedOut && logins == 1 && logouts == 1, "Incomplete login lifecycle");
            var saved = NbtIo.readCompressed(playerFile(event.getServer()), NbtAccounter.unlimitedHeap());
            var inventory = saved.getList("Inventory", Tag.TAG_COMPOUND);
            require(inventory.size() == 1, "Normal player file inventory size differs");
            var entry = inventory.getCompound(0);
            var stack = ItemStack.parseOptional(event.getServer().registryAccess(), entry);
            require(entry.getByte("Slot") == 0 && stack.getCount() == 17 && ItemStack.isSameItemSameComponents(stack, expected),
                    "Vanilla saved file differs from observed inventory");
            require(manifest != null && (reader() ? manifest.getLong("producerPid") != ProcessHandle.current().pid() : true),
                    "Reader reused writer process");
            report.addProperty("producerPid", manifest.getLong("producerPid"));
            report.addProperty("playerFile", playerFile(event.getServer()).toAbsolutePath().normalize().toString());
            report.addProperty("normalPlayerFileVerified", true);
            report.addProperty("coreIdentityAndGrantVerified", true);
            report.addProperty("passed", true);
        } catch (Exception error) { report.addProperty("passed", false); report.addProperty("failure", error.toString()); }
        report.addProperty("scope", "real local offline-mode TCP login, core grants and normal player file; no asset-exchange or multiplayer acceptance");
        try { write("player-login-server.json", report); }
        catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write login report", error); }
        core = null; expected = null; manifest = null;
    }

    private static void fail(MinecraftServer server, Exception error) {
        failure = error.toString(); com.mojang.logging.LogUtils.getLogger().error("PLAYER_LOGIN_FAILED", error); server.halt(false);
    }
    private static void write(String name, JsonObject report) throws java.io.IOException {
        Files.createDirectories(Path.of("results"));
        Files.writeString(Path.of("results", name), new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }
    private PlayerLoginServerProbe() {}
}
