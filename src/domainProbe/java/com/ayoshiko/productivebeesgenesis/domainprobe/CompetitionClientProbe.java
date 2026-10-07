package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkCoreScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.CoreOwnershipController;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.ClientTerminalProbe.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 两个独立客户端通过真实 GUI 准备／提交；仅重放与越权场景主动发送保留的旧正式请求。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class CompetitionClientProbe {
    private static final boolean OWNER = "owner".equals(System.getProperty("pbg.concurrent.role"));
    private static int stage = -1, step, settled, connections;
    private static long started, nextAt, reconnectAt;
    private static boolean finished, advancing, connecting, observed, reconnecting, acknowledged;
    private static TerminalRequest pending, old;
    private static String session;
    private static CompetitionSignal.Case prepared;
    private static boolean reader() { return "read".equals(System.getProperty("pbg.concurrent.mode")); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("pbg.concurrent.client") || finished || advancing) return;
        advancing = true; var client = Minecraft.getInstance();
        try {
            if (started == 0) {
                started = System.nanoTime();
                CompetitionSignal.client = signal -> {
                    stage = signal.stage(); step = 0; settled = 0; acknowledged = false; nextAt = Util.getMillis() + 300;
                };
            }
            require(System.nanoTime() - started < 480_000_000_000L, "Concurrent client timeout at " + stage + "/" + step);
            if ((!connecting || reconnecting) && client.screen instanceof TitleScreen && client.getOverlay() == null && Util.getMillis() >= reconnectAt) {
                connect(client); reconnecting = false; connecting = true; return;
            }
            require(!(client.screen instanceof DisconnectedScreen), "Unexpected server disconnect");
            if (client.player == null) { observed = false; return; }
            if (!observed) { connections++; observed = true; }
            UUID id = OWNER ? CompetitionServerProbe.OWNER : CompetitionServerProbe.GUEST;
            require(client.player.getUUID().equals(id) && client.getSingleplayerServer() == null
                    && !client.getConnection().getConnection().isMemoryConnection(), "Wrong real TCP player");
            if (stage < 0 || acknowledged || Util.getMillis() < nextAt) return;
            nextAt = Util.getMillis() + 300;
            if (stage == 90) { finish(client, null); return; }
            if (NativeSlotsProbe.enabled() && (stage >= 800 || stage == 80)) {
                if (client.player.containerMenu instanceof NetworkCoreMenu menu) session = menu.terminalSession().toString();
                var reply = NativeSlotsClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (MachineWorkspaceProbe.enabled() && stage >= 750) {
                var reply = MachineWorkspaceClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (WorkspaceProbe.enabled() && stage >= 700) {
                var reply = WorkspaceClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (MeCraftingProbe.enabled() && stage >= 600) {
                var reply = MeCraftingClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (MeBridgeProbe.enabled() && (stage >= 500 || stage == 80)) {
                if (client.player.containerMenu instanceof NetworkCoreMenu menu) session = menu.terminalSession().toString();
                var reply = MeBridgeClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (RecipeFillProbe.enabled() && stage >= 400) {
                var reply = RecipeFillClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (WirelessProbe.enabled() && stage >= 330) {
                var reply = WirelessClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (CraftingProbe.enabled() && (stage >= 300 || stage == 80)) {
                if (client.player.containerMenu instanceof NetworkCoreMenu menu) session = menu.terminalSession().toString();
                var reply = CraftingClient.advance(client, stage, OWNER); if (reply != null) ack(reply.moved(), reply.status()); return;
            }
            if (TerminalPermissionProbe.enabled() && stage >= 200) {
                var reply = TerminalPermissionClient.advance(client, stage, OWNER);
                if (reply != null) ack(reply.moved(), reply.status());
                return;
            }
            if (UpgradeCompetitionProbe.enabled() && stage >= 100) {
                var reply = UpgradeCompetitionClient.advance(client, stage, OWNER);
                if (reply != null) ack(reply.moved(), reply.status());
                return;
            }
            if (stage == 51) {
                if (OWNER) ack(0, -1);
                else {
                    acknowledged = true; client.player.closeContainer(); client.level.disconnect(); client.disconnect(new TitleScreen());
                    observed = false; reconnecting = true; reconnectAt = Util.getMillis() + 700;
                }
                return;
            }
            if (stage == 52) { ack(0, -1); return; }
            if (stage == 41 || stage == 43) {
                if (OWNER) {
                    var pos = PlayerLoginServerProbe.POS;
                    client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                            + (stage == 41 ? " revoke " : " grant ") + CompetitionServerProbe.GUEST);
                }
                ack(0, -1); return;
            }
            if (stage == 42 || stage == 44 || stage == 53) {
                if (!OWNER) {
                    require(old != null, "Missing old request");
                    if (stage == 53) {
                        if (!(client.player.containerMenu instanceof NetworkCoreMenu menu)) return;
                        require(connections == 2 && !menu.terminalSession().equals(old.session()), "Reconnect reused old menu");
                    }
                    PacketDistributor.sendToServer(old);
                }
                // 与旧正式请求走同一 TCP 连接、同一 MAIN 队列；服务器收到此回执后才核对拒绝结果。
                ack(0, -2); return;
            }
            if (stage == 0 && !OWNER || stage == 1 && !OWNER) { ack(0, -1); return; }
            if (stage == 0 && step == 4) {
                if (!(client.player.containerMenu instanceof NetworkCoreMenu)) ack(0, -1);
                return;
            }
            if (!(client.screen instanceof NetworkCoreScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return;
            if (menu.canManage() != OWNER) return;
            session = menu.terminalSession().toString();
            if (stage == 0) { setup(client, screen, menu); return; }
            if (stage == 1) { prepareBee(screen, menu); return; }
            if (stage == 80) {
                if (UpgradeCompetitionProbe.enabled() && !menu.canUpgrade()) return;
                if (!queryReady(screen, menu)) return;
                if (step == 0) { press(screen, "tab.2"); step++; return; }
                if (menu.clientState().view() == null) return;
                var inventory = client.player.getInventory();
                require(inventory.getItem(4).getCount() == 64 && inventory.getItem(7).getCount() == 64
                        && inventory.getItem(8).is(Items.GOLD_INGOT) && inventory.getItem(8).getCount() == (TerminalPermissionProbe.enabled() ? 64 : OWNER ? 62 : 63),
                        "Recovered inventory was not synchronized");
                ack(0, -1); return;
            }
            if (stage >= 10 && stage <= 33) {
                int round = (stage - 10) / 3, part = (stage - 10) % 3;
                if (part == 0) prepare(screen, menu, CompetitionSignal.Case.values()[round], false);
                else if (part == 1) commit(screen, menu, false);
                else { require(pending != null, "Missing replay"); PacketDistributor.sendToServer(pending); ack(0, -2); }
                return;
            }
            if (stage == 40 || stage == 50 || stage == 54) {
                prepare(screen, menu, stage == 40 ? CompetitionSignal.Case.REGRANTED : CompetitionSignal.Case.RECONNECTED, stage != 54); return;
            }
            if (stage == 55) { if (OWNER) ack(0, TerminalReply.Status.OK.ordinal()); else commit(screen, menu, true); return; }
            throw new IllegalStateException("Unexpected client stage " + stage);
        } catch (Exception error) { finish(client, error); }
        finally { advancing = false; }
    }
    private static void setup(Minecraft client, NetworkCoreScreen screen, NetworkCoreMenu menu) {
        switch (step) {
            case 0 -> { if (menu.value(1) != (UpgradeCompetitionProbe.enabled() || TerminalPermissionProbe.enabled() ? 2 : 1)) return; press(screen, "join"); step++; }
            case 1 -> { if (menu.ownershipStatus() != CoreOwnershipController.Status.MANAGED.ordinal()) return; press(screen, "start"); step++; }
            case 2 -> { if (!menu.productionRunning() || ++settled < 10) return; press(screen, "pause"); step++; }
            case 3 -> {
                if (menu.productionRunning()) return;
                var pos = PlayerLoginServerProbe.POS;
                client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " grant " + CompetitionServerProbe.GUEST);
                step++;
            }
        }
    }
    private static boolean queryReady(NetworkCoreScreen screen, NetworkCoreMenu menu) {
        if (menu.clientState().waiting() || !menu.clientState().ready(Util.getMillis())) return false;
        String refresh = Component.translatable("screen.productivebeesgenesis.network.refresh").getString();
        return screen.children().stream().noneMatch(c -> c instanceof Button b && b.getMessage().getString().equals(refresh) && !b.active);
    }
    private static void prepareBee(NetworkCoreScreen screen, NetworkCoreMenu menu) {
        if (!queryReady(screen, menu)) return;
        switch (step) {
            case 0 -> { press(screen, "tab.1"); step++; }
            case 1 -> { if (!member(screen, menu)) return; chooseSlot(screen, menu, 0); press(screen, "feed_in"); step++; }
            case 2 -> { result(menu, TerminalReply.Status.MOVED, 1); press(screen, "refresh"); step++; }
            case 3 -> { if (!member(screen, menu)) return; chooseSlot(screen, menu, 1); press(screen, "cage_in"); step++; }
            case 4 -> { result(menu, TerminalReply.Status.MOVED, 1); ack(0, -1); }
        }
    }
    private static void prepare(NetworkCoreScreen screen, NetworkCoreMenu menu, CompetitionSignal.Case test, boolean remember) {
        if (!queryReady(screen, menu)) return;
        if (step == 0) { press(screen, test.operation == TerminalRequest.Operation.TAKE_PRODUCT ? "tab.2" : "tab.1"); step++; return; }
        var view = menu.clientState().view(); if (view == null) return;
        int row = -1, inventory = -1;
        if (test.operation == TerminalRequest.Operation.TAKE_PRODUCT) {
            for (int i = 0; i < view.rows().size(); i++) {
                var value = view.rows().get(i);
                boolean variant = test.variant.equals("__plain__") ? !value.detail().contains("minecraft:custom_name") : value.detail().contains(test.variant);
                if (value.label().equals(test.id) && variant) { row = i; break; }
            }
            require(row >= 0 && view.rows().get(row).available().equals(Integer.toString(test.available)), "Missing expected shared asset: " + test);
        } else {
            for (int i = 0; i < view.rows().size(); i++) if (!view.rows().get(i).bees().isEmpty()) { row = i; break; }
            require(row >= 0 && member(screen, menu), "Missing shared member");
            inventory = test.operation == TerminalRequest.Operation.FEED_OUT ? 0 : 1;
            chooseSlot(screen, menu, inventory);
        }
        prepared = test;
        pending = new TerminalRequest(menu.containerId, menu.terminalSession(), menu.terminalReply().sequence() + 1,
                test.operation, view.generation(), row, test.operation == TerminalRequest.Operation.TAKE_PRODUCT ? -1 : 0, inventory, test.amount);
        if (remember) old = pending;
        ack(0, -1);
    }
    private static void commit(NetworkCoreScreen screen, NetworkCoreMenu menu, boolean reconnect) {
        if (step == 0) {
            if (!queryReady(screen, menu)) return;
            require(pending != null && menu.clientState().view() != null && menu.terminalSession().equals(pending.session())
                    && menu.clientState().view().generation() == pending.generation(), "Shared selection expired before commit");
            if (prepared.operation == TerminalRequest.Operation.TAKE_PRODUCT) {
                double x = (screen.width - NetworkCoreScreen.WIDTH) / 2 + 49 + pending.row() * 22;
                double y = (screen.height - NetworkCoreScreen.HEIGHT) / 2 + 75;
                int button = prepared.amount == 1 ? 1 : 0;
                require(screen.mouseClicked(x, y, button), "Product click missed"); screen.mouseReleased(x, y, button);
            } else press(screen, prepared.operation == TerminalRequest.Operation.FEED_OUT ? "feed_out" : "cage_out");
            step++; return;
        }
        var reply = menu.clientState().exchangeResult(); if (reply == null) return;
        require(reply.sequence() == pending.sequence(), "Received another operation result");
        ack(reply.moved(), reply.status().ordinal());
    }
    private static void ack(int moved, int status) { PacketDistributor.sendToServer(new CompetitionSignal(stage, moved, status)); acknowledged = true; }
    private static void connect(Minecraft client) {
        client.options.pauseOnLostFocus = false;
        String address = "127.0.0.1:" + Integer.getInteger("pbg.concurrent.port", 25583);
        ConnectScreen.startConnecting(client.screen, client, ServerAddress.parseString(address),
                new ServerData("PBG concurrent probe", address, ServerData.Type.OTHER), false, null);
    }
    private static void finish(Minecraft client, Exception failure) {
        if (finished) return;
        finished = true; var report = new JsonObject();
        try {
            Files.createDirectories(Path.of("results"));
            if (failure == null) {
                require(stage == 90 && connections == (reader() || OWNER || CraftingProbe.enabled() || MeBridgeProbe.enabled() ? 1 : 2), "Incomplete connection lifecycle");
                if (TerminalPermissionProbe.enabled() && !reader()) require(TerminalPermissionClient.completed(), "Terminal permission client incomplete");
                try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/concurrent.png")); }
                client.player.closeContainer(); client.level.disconnect(); client.disconnect(new TitleScreen());
            } else if (client.player != null && stage >= 0) PacketDistributor.sendToServer(new CompetitionSignal(stage, 0, -3));
        } catch (Exception error) { failure = error; }
        report.addProperty("passed", failure == null); report.addProperty("role", OWNER ? "owner" : "guest");
        report.addProperty("mode", reader() ? "read" : "write"); report.addProperty("connections", connections);
        report.addProperty("session", session); report.addProperty("ae2Loaded", ModList.get().isLoaded("ae2"));
        if (TerminalPermissionProbe.enabled()) report.addProperty("terminalPermissionsClient", reader() || TerminalPermissionClient.completed());
        if (failure != null) { report.addProperty("failure", failure.toString()); com.mojang.logging.LogUtils.getLogger().error("CONCURRENT_CLIENT_FAILED at {}/{}", stage, step, failure); }
        try { Files.writeString(Path.of("results/concurrent-client.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report)); }
        catch (Exception error) { com.mojang.logging.LogUtils.getLogger().error("Cannot write concurrent report", error); }
        CompetitionSignal.client = null; pending = null; old = null; client.stop();
    }
    private CompetitionClientProbe() {}
}
