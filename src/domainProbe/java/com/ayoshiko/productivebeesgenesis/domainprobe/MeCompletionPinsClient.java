package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreMenu;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class MeCompletionPinsClient {
    private static int previous = -1, step;
    private static long beforeTake;
    static boolean verified;
    static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
        if (!owner) return ack();
        if (previous != stage) { previous = stage; step = 0; }
        if (stage == 630 && step == 1) { client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); step++; return null; }
        if (!(client.player.containerMenu instanceof NetworkCoreMenu menu) || !(client.screen instanceof NetworkTerminalScreen screen)) return null;
        var session = menu.meTerminal(); if (session.waiting()) return null; var view = session.view();
        if (stage == 620) {
            if (step == 0) { require(!ModConfig.CLIENT.terminalPreferences.pinCraftingFinished.get(), "Completed pin preference must default off"); click(client, screen.getGuiLeft() + 230, screen.getGuiTop() + 15, 0); step++; return null; }
            if (view.mode() != MeTerminalView.Mode.STORAGE) return null;
            require(view.more() && view.rows().size() == 36 && view.rows().stream().noneMatch(r -> r.pinned() || plain(r.icon())), "Default ordering did not leave completion beyond first page"); return ack();
        }
        if (stage == 621) {
            if (step == 0) { ModConfig.CLIENT.terminalPreferences.pinCraftingFinished.set(true); if (!button(client, "refresh")) return null; step++; return null; }
            pinned(view); picture(client, "me-completed-pin.png"); return ack();
        }
        if (stage == 622) {
            if (step == 0) { if (!button(client, "sort.name")) return null; step++; return null; }
            pinned(view); return ack();
        }
        if (stage == 623) {
            if (step == 0) { search(client).setValue("AAA"); step++; return null; }
            if (view.rows().stream().anyMatch(r -> !r.icon().has(DataComponents.CUSTOM_NAME))) return null;
            require(view.rows().size() == 36 && view.more() && view.rows().stream().noneMatch(MeTerminalView.Row::pinned), "Search admitted an unrelated completed key"); return ack();
        }
        if (stage == 624) {
            if (step == 0) { search(client).setValue(""); step++; return null; }
            if (step == 1) { if (!button(client, "type.all")) return null; step++; return null; }
            if (step == 2) { if (!button(client, "type.item")) return null; step++; return null; }
            require(view.rows().isEmpty(), "Fluid-only filter retained completed item"); return ack();
        }
        if (stage == 625) {
            if (step == 0) { if (!button(client, "type.fluid")) return null; step++; return null; }
            if (step == 1) { if (!button(client, "type.other")) return null; step++; return null; }
            if (step == 2) { pinned(view); beforeTake = view.revision(); first(client, screen, 1); step++; return null; }
            require(plain(menu.getCarried()) && menu.getCarried().getCount() == 1, "Pinned slot took a different item"); return ack();
        }
        if (stage == 626) {
            if (step == 0) {
                deferRefresh(screen);
                var request = session.begin(MeTerminalRequest.Action.TAKE, 0, 0, 1, "", MeStorageFilter.DEFAULT, true);
                PacketDistributor.sendToServer(new MeTerminalRequest(request.containerId(), request.session(), request.sequence(), request.action(), beforeTake, 0, 0, 1, "", request.filter(), true)); step++; return null;
            }
            require(view.status() == MeTerminalView.Status.STALE && menu.getCarried().getCount() == 1, "Old pinned revision repeated extraction"); return ack();
        }
        if (stage == 627 || stage == 629) {
            if (step == 0) { first(client, screen, 0); step++; return null; }
            require(menu.getCarried().isEmpty(), "Pinned item deposit failed"); return ack();
        }
        if (stage == 628) {
            if (step == 0) { first(client, screen, 0); step++; return null; }
            if (step == 1) { require(plain(menu.getCarried()) && menu.getCarried().getCount() == 2, "Pinned complete stack extraction failed"); if (!button(client, "refresh")) return null; step++; return null; }
            require(view.rows().stream().noneMatch(MeTerminalView.Row::pinned), "Zero stock remained pinned"); return ack();
        }
        if (stage == 630) {
            if (step == 0) { screen.onClose(); step++; return null; }
            if (view.mode() != MeTerminalView.Mode.STORAGE) return null;
            pinned(view); return ack();
        }
        if (stage == 631) {
            if (step == 0) { if (!button(client, "refresh")) return null; step++; return null; }
            require(view.rows().stream().noneMatch(MeTerminalView.Row::pinned), "Expired completed history stayed pinned");
            ModConfig.CLIENT_SPEC.save(); verified = true; return ack();
        }
        if (stage == 632) return !menu.wirelessTerminal() && menu.craftingGeneration() != 0 ? ack() : null;
        return null;
    }
    private static void pinned(MeTerminalView view) {
        require(view.mode() == MeTerminalView.Mode.STORAGE && !view.rows().isEmpty() && plain(view.rows().getFirst().icon())
            && view.rows().getFirst().pinned() && view.rows().getFirst().amount() == 2 && view.rows().stream().filter(MeTerminalView.Row::pinned).count() == 1, "Expected exact completed output first: " + view);
    }
    private static boolean plain(ItemStack stack) { return stack.is(Items.IRON_INGOT) && !stack.has(DataComponents.CUSTOM_NAME); }
    private static EditBox search(Minecraft client) { return client.screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow(); }
    private static boolean button(Minecraft client, String key) {
        var expected = Component.translatable("screen.productivebeesgenesis.me_terminal." + key).getString();
        var widget = client.screen.children().stream().filter(w -> w instanceof AbstractWidget b && b.active && b.visible && b.getMessage().getString().equals(expected)).map(w -> (AbstractWidget) w).findFirst().orElse(null);
        if (widget == null) return false; click(client, widget.getX() + widget.getWidth() / 2., widget.getY() + widget.getHeight() / 2., 0); return true;
    }
    private static void first(Minecraft client, NetworkTerminalScreen screen, int button) { click(client, screen.getGuiLeft() + 40, screen.getGuiTop() + 59, button); }
    private static void click(Minecraft client, double x, double y, int button) { require(client.screen.mouseClicked(x, y, button), "Pin UI click missed"); client.screen.mouseReleased(x, y, button); }
    private static void deferRefresh(NetworkTerminalScreen screen) throws Exception {
        var product = NetworkTerminalScreen.class.getDeclaredField("productPane"); product.setAccessible(true); var pane = product.get(screen);
        var me = pane.getClass().getDeclaredField("mePane"); me.setAccessible(true); var storage = me.get(pane);
        var due = storage.getClass().getDeclaredField("due"); due.setAccessible(true); due.setLong(storage, Util.getMillis() + 10_000);
    }
    private static void picture(Minecraft client, String name) throws Exception { Files.createDirectories(Path.of("results")); try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); } }
    private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
    private MeCompletionPinsClient() { }
}
