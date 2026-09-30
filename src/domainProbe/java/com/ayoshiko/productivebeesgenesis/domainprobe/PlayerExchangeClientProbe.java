package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalReply;
import com.google.gson.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.Util;
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
import static com.ayoshiko.productivebeesgenesis.domainprobe.ClientTerminalProbe.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 沿正式 GUI 控件发送请求；不从客户端访问服务器世界或共享静态完成标记。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class PlayerExchangeClientProbe {
    private static int step, settled;
    private static long started, nextAt;
    private static boolean finished, advancing;
    private static String session;
    private static final String ROLE = System.getProperty("pbg.exchange.role", "owner");
    private static boolean reader() { return "read".equals(System.getProperty("pbg.login.mode")); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("pbg.login.client") || !Boolean.getBoolean("pbg.exchange.enabled") || finished || advancing) return;
        advancing = true; var client = Minecraft.getInstance();
        try {
            if (started == 0) started = System.nanoTime();
            require(System.nanoTime() - started < 150_000_000_000L, "Exchange client timeout: " + ROLE + "/" + step);
            if (step == 0) {
                if (!(client.screen instanceof TitleScreen) || client.getOverlay() != null) return;
                client.options.pauseOnLostFocus = false;
                String address = "127.0.0.1:" + Integer.getInteger("pbg.login.port", 25582);
                ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                        new ServerData("PBG exchange probe", address, ServerData.Type.OTHER), false, null);
                step = 1; return;
            }
            require(!(client.screen instanceof DisconnectedScreen), "Server disconnected during exchange: " + ROLE + "/" + step);
            if (client.player == null) return;
            UUID expected = ROLE.equals("owner") ? PlayerExchangeServerProbe.OWNER
                    : ROLE.equals("guest") ? PlayerExchangeServerProbe.GUEST : PlayerExchangeServerProbe.STRANGER;
            require(client.player.getUUID().equals(expected) && client.getSingleplayerServer() == null
                    && !client.getConnection().getConnection().isMemoryConnection(), "Not the expected real TCP player");
            if (ROLE.equals("stranger")) {
                require(!(client.player.containerMenu instanceof NetworkCoreMenu), "Stranger received a core menu");
                if (++settled >= 60) finish(client, null);
                return;
            }
            if (ROLE.equals("owner") && step == 3) {
                // 等待正式授权使旧菜单关闭；命令与客户端状态同步可能跨多个 tick。
                if (client.player.containerMenu instanceof NetworkCoreMenu) return;
                if (++settled < 10) return;
                finish(client, null); return;
            }
            if (!(client.screen instanceof NetworkCoreScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return;
            if (menu.canManage() != ROLE.equals("owner")) return;
            session = menu.terminalSession().toString();
            if (ROLE.equals("owner")) {
                if (reader()) { if (++settled >= 20) finish(client, null); return; }
                if (step == 1) { if (menu.value(1) != 1) return; press(screen, "join"); step = 2; return; }
                if (menu.ownershipStatus() != CoreOwnershipController.Status.MANAGED.ordinal()) return;
                if (step == 2) { press(screen, "start"); step = 4; settled = 0; return; }
                if (step == 4) {
                    if (!menu.productionRunning() || ++settled < 40) return;
                    press(screen, "pause"); step = 5; return;
                }
                if (menu.productionRunning()) return;
                var pos = PlayerExchangeServerProbe.POS;
                client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " "
                        + pos.getZ() + " grant " + PlayerExchangeServerProbe.GUEST);
                step = 3; settled = 0; return;
            }
            if (reader()) {
                var inventory = client.player.getInventory();
                if (inventory.getItem(0).getCount() != 63 || !inventory.getItem(2).is(Items.WATER_BUCKET)) return;
                require(inventory.getItem(4).getCount() == 64 && inventory.getItem(5).getCount() == 2
                        && !inventory.getItem(1).has(DataComponents.CUSTOM_DATA), "Recovered client inventory differs");
                if (++settled >= 20) finish(client, null);
                return;
            }
            if (step == 1) {
                if (!client.player.getInventory().getItem(0).is(Items.IRON_BLOCK)) return;
                if (!advance(client, screen, menu, true)) return;
                step = 2; nextAt = Util.getMillis() + 350; return;
            }
            if (Util.getMillis() < nextAt || menu.clientState().waiting() || !menu.clientState().ready(Util.getMillis())) return;
            nextAt = Util.getMillis() + 350;
            switch (step) {
                case 2 -> { press(screen, "tab.1"); step++; }
                case 3 -> { if (!member(screen, menu)) return; chooseSlot(screen, menu, 0); press(screen, "feed_in"); step++; }
                case 4 -> { result(menu, TerminalReply.Status.MOVED, 1); press(screen, "refresh"); step++; }
                case 5 -> { if (!member(screen, menu)) return; chooseSlot(screen, menu, 1); press(screen, "cage_in"); step++; }
                case 6 -> { result(menu, TerminalReply.Status.MOVED, 1); press(screen, "tab.0"); step++; }
                case 7 -> { if (++settled >= 10) finish(client, null); }
            }
        } catch (Exception error) { finish(client, error); }
        finally { advancing = false; }
    }
    private static void finish(Minecraft client, Exception failure) {
        finished = true; var report = new JsonObject();
        report.addProperty("passed", failure == null); report.addProperty("role", ROLE);
        report.addProperty("mode", System.getProperty("pbg.login.mode")); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        report.addProperty("guestSession", session); report.addProperty("dedicatedTcpLoginAndNormalDisconnect", failure == null);
        report.addProperty("guestUiExchanges", failure == null && ROLE.equals("guest") && !reader() && step == 7);
        try {
            Files.createDirectories(Path.of("results"));
            if (failure == null) {
                try (var shot = Screenshot.takeScreenshot(client.getMainRenderTarget())) { shot.writeToFile(Path.of("results/player-login.png")); }
                client.player.closeContainer(); client.level.disconnect(); client.disconnect(new TitleScreen());
                require(client.level == null, "Client did not disconnect normally");
            }
        } catch (Exception error) { failure = error; }
        if (failure != null) {
            report.addProperty("passed", false); report.addProperty("failure", failure.toString());
            com.mojang.logging.LogUtils.getLogger().error("PLAYER_EXCHANGE_CLIENT_FAILED", failure);
        }
        try { Files.writeString(Path.of("results/player-login-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write client report", error); }
        client.stop();
    }
    private PlayerExchangeClientProbe() {}
}
