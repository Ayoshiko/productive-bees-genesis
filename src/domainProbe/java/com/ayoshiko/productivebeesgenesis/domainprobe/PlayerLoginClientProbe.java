package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.google.gson.*;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 独立客户端通过 TCP 登录专服，使用正式授权命令并正常断线。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class PlayerLoginClientProbe {
    private static int step, settled;
    private static long started;
    private static boolean finished, advancing;
    private static String session, marker;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("pbg.login.client") || finished || advancing) return;
        advancing = true; var client = Minecraft.getInstance();
        try {
            if (started == 0) started = System.nanoTime();
            require(System.nanoTime() - started < 120_000_000_000L, "Login client timeout at " + step);
            if (step == 0) {
                if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                client.options.pauseOnLostFocus = false;
                String address = "127.0.0.1:" + Integer.getInteger("pbg.login.port", 25582);
                ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                        new ServerData("PBG login probe", address, ServerData.Type.OTHER), false, null);
                step = 1; return;
            }
            if (client.player == null) return;
            require(client.getSingleplayerServer() == null && client.getConnection() != null
                    && !client.getConnection().getConnection().isMemoryConnection(), "Not a dedicated TCP login");
            require(client.player.getUUID().equals(PlayerLoginServerProbe.OWNER), "Client login UUID differs");
            if (step == 1) {
                if (!(client.screen instanceof NetworkCoreScreen)
                        || !(client.player.containerMenu instanceof NetworkCoreMenu menu) || !menu.canManage()) return;
                var stack = client.player.getInventory().getItem(0);
                if (!stack.is(Items.DIAMOND) || stack.getCount() != 17 || !stack.has(DataComponents.CUSTOM_DATA)) return;
                var data = stack.get(DataComponents.CUSTOM_DATA).copyTag();
                require(data.hasUUID("pbg_login_marker"), "Full inventory component did not synchronize");
                marker = data.getUUID("pbg_login_marker").toString(); session = menu.terminalSession().toString();
                if (++settled < 10) return;
                Files.createDirectories(Path.of("results"));
                try (var screenshot = Screenshot.takeScreenshot(client.getMainRenderTarget())) {
                    screenshot.writeToFile(Path.of("results/player-login.png"));
                }
                if ("write".equals(System.getProperty("pbg.login.mode"))) {
                    var pos = PlayerLoginServerProbe.POS;
                    client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " "
                            + pos.getZ() + " grant " + PlayerLoginServerProbe.GUEST);
                }
                step = 2; settled = 0; return;
            }
            if (step == 2 && ++settled >= 40) {
                if ("write".equals(System.getProperty("pbg.login.mode")))
                    require(!(client.player.containerMenu instanceof NetworkCoreMenu), "Grant did not revoke the old menu");
                client.player.closeContainer();
                client.level.disconnect(); client.disconnect(new TitleScreen());
                require(client.level == null && client.getSingleplayerServer() == null, "Client did not disconnect normally");
                finish(client, null);
            }
        } catch (Exception error) { finish(client, error); }
        finally { advancing = false; }
    }
    private static void finish(Minecraft client, Exception failure) {
        finished = true;
        var report = new JsonObject();
        report.addProperty("passed", failure == null); report.addProperty("mode", System.getProperty("pbg.login.mode"));
        report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        report.addProperty("owner", PlayerLoginServerProbe.OWNER.toString());
        report.addProperty("terminalSession", session); report.addProperty("inventoryMarker", marker);
        report.addProperty("dedicatedTcpLoginAndNormalDisconnect", failure == null);
        if (failure != null) {
            report.addProperty("failure", failure.toString());
            com.mojang.logging.LogUtils.getLogger().error("PLAYER_LOGIN_CLIENT_FAILED", failure);
        }
        try {
            Files.createDirectories(Path.of("results"));
            Files.writeString(Path.of("results/player-login-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write login client report", error); }
        client.stop();
    }
    private PlayerLoginClientProbe() {}
}
