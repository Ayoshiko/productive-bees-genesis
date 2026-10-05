package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

/** 页面走实际 GUI；竞争请求由客户端状态生成并在双端屏障后经正式 TCP 提交。 */
final class TerminalPermissionClient {
    record Reply(int moved, int status) { }
    private static int lastStage = -1, step;
    private static TerminalRequest pending;
    private static boolean completed;
    static Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
        if (stage != lastStage) { lastStage = stage; step = 0; }
        if (stage == 213 || stage == 215) {
            if (owner) {
                var pos = PlayerLoginServerProbe.POS;
                client.getConnection().sendCommand("pbgnetwork access " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                        + (stage == 213 ? " revoke " : " grant ") + CompetitionServerProbe.GUEST);
            }
            return ack();
        }
        if (stage == 202 || stage == 209 || stage == 214 || stage == 216) {
            require(pending != null, "Missing retained terminal request"); PacketDistributor.sendToServer(pending); return ack();
        }
        if (stage == 223) { client.player.closeContainer(); return ack(); }
        if (!(client.screen instanceof NetworkTerminalScreen screen) || !(client.player.containerMenu instanceof NetworkCoreMenu menu)) return null;
        require(!menu.canManage() && (owner || !menu.canUpgrade()), "Terminal role projection incorrect");
        var state = menu.clientState(); long now = Util.getMillis();
        if (stage == 201 || stage == 204 || stage == 208 || stage == 220 || stage == 206 || stage == 211 || stage == 218) {
            if ((stage == 206 || stage == 211) && owner) return ack();
            if (step == 0) {
                require(pending != null && pending.session().equals(menu.terminalSession()), "Competition lost frozen request");
                if (stage == 218) {
                    PacketDistributor.sendToServer(new TerminalRequest(pending.containerId(), UUID.randomUUID(), pending.sequence(), BEE_DISABLE,
                            pending.generation(), pending.row(), pending.targetSlot(), -1, 0));
                    PacketDistributor.sendToServer(new TerminalRequest(pending.containerId() + 1, pending.session(), pending.sequence(), BEE_DISABLE,
                            pending.generation(), pending.row(), pending.targetSlot(), -1, 0));
                    pending = new TerminalRequest(pending.containerId(), pending.session(), pending.sequence(), BEE_DISABLE,
                            pending.generation(), pending.row(), pending.targetSlot(), 0, 1);
                }
                PacketDistributor.sendToServer(pending); step++; return null;
            }
            var reply = state.exchangeResult();
            if (reply == null || reply.sequence() != pending.sequence()) return null;
            return new Reply(reply.moved(), reply.status().ordinal());
        }
        if (stage == 222) {
            if (step == 0) {
                var request = state.begin(CANCEL, -1, -1, -1, 0, now); if (request == null) return null;
                pending = request; PacketDistributor.sendToServer(request); step++; return null;
            }
            var result = state.result();
            return result == null || result.sequence() != pending.sequence() ? null : new Reply(result.moved(), result.status().ordinal());
        }
        if (!state.actionable(now) || state.view() == null) return null;
        if (stage == 224) {
            java.nio.file.Files.createDirectories(Path.of("results"));
            try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", "terminal-permissions.png")); }
            completed = true; return ack();
        }
        int tab = stage == 205 || stage == 210 ? 3 : stage == 207 || stage == 221 ? 2 : 1;
        if (step == 0) { ClientTerminalProbe.press(screen, "tab." + tab); step++; return null; }
        var view = state.view();
        var kind = tab == 1 ? NetworkSelectionSession.Kind.MEMBERS : tab == 2 ? NetworkSelectionSession.Kind.PRODUCTS : NetworkSelectionSession.Kind.UPGRADES;
        if (view.kind() != kind) return null;
        if (stage == 221) {
            if (step == 1) { ClientTerminalProbe.press(screen, "terminal.sort_id"); step++; return null; }
            return state.sortAgeSeconds(now) < 0 ? null : ack();
        }
        if ((stage == 205 || stage == 210) && owner) return ack();
        int row = 0;
        if (stage == 207) {
            row = -1;
            for (int i = 0; i < view.rows().size(); i++) if (view.rows().get(i).label().equals("minecraft:gold_ingot")) row = i;
            require(row >= 0 && view.rows().get(row).available().equals("4"), "Missing shared gold snapshot");
        } else if (tab == 1) require(view.rows().size() == 1 && view.rows().getFirst().bees().getFirst().identity() != null, "Missing controlled bee");
        var operation = stage == 203 ? BEE_ENABLE : stage == 205 || stage == 210 ? UPGRADE_INSTALL
                : stage == 207 ? TAKE_PRODUCT : stage == 219 ? CAGE_OUT : BEE_DISABLE;
        int slot = stage == 207 ? -1 : 0;
        int inventory = stage == 207 ? 8 : stage == 219 ? 1 : stage == 205 || stage == 210 ? 20 : -1;
        int amount = stage == 207 ? 64 : stage == 219 || stage == 205 || stage == 210 ? 1 : 0;
        pending = state.begin(operation, row, slot, inventory, amount, now);
        return pending == null ? null : ack();
    }
    static boolean completed() { return completed; }
    private static Reply ack() { return new Reply(0, -1); }
    private TerminalPermissionClient() { }
}
