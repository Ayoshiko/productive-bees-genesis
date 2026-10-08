package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.*;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class CraftingReturnClient {
    private static int previous = -1, step;
    static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
        if (previous != stage) { previous = stage; step = 0; }
        if (owner && stage == 804 || !owner && stage != 800 && stage != 804 && stage != 818) return ack();
        if (stage == 817) return client.player.containerMenu == client.player.inventoryMenu ? ack() : null;
        if (stage == 811 || stage == 814) {
            if (step == 0) {
                if (!(client.player.getMainHandItem().getItem() instanceof WirelessTerminalItem)) return null;
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); step++; return null;
            }
            return ready(client) ? ack() : null;
        }
        var preference = ModConfig.CLIENT.terminalPreferences.returnCraftingOnClose;
        if (stage == 802 && step == 1) { require(client.screen instanceof MeTerminalScreen, "ME subpage not displayed"); client.screen.onClose(); step++; return null; }
        if (stage == 801 || stage == 803 || stage == 804 || stage == 806 || stage == 808 || stage == 810 || stage == 813 || stage == 816) {
            if (step == 0) {
                if (!ready(client)) return null;
                preference.set(stage != 801); client.screen.onClose(); step++; return null;
            }
            return client.player.containerMenu == client.player.inventoryMenu ? ack() : null;
        }
        if (!ready(client)) return null;
        if (stage == 800) { require(!preference.get(), "Return preference must default off"); return ack(); }
        if (stage == 802 && step == 0) { preference.set(true); client.setScreen(new MeTerminalScreen(client.screen, client.player.containerMenu)); step++; return null; }
        if (stage == 807) {
            var menu = (NetworkCoreMenu) client.player.containerMenu;
            if (step == 0) {
                var request = menu.clientState().beginCrafting(TerminalRequest.Operation.CRAFT_RETURN_ON_CLOSE, 0, -1, -1, 0, Util.getMillis());
                if (request == null) return null; PacketDistributor.sendToServer(request); step++; return null;
            }
            require(menu.clientState().exchangeResult() != null && menu.clientState().exchangeResult().status() == TerminalReply.Status.STALE, "Stale close return was accepted");
        }
        if (stage == 805) {
            Files.createDirectories(Path.of("results"));
            try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results/return-full-inventory.png")); }
        }
        if (stage == 818 && owner) { preference.save(); }
        return ack();
    }
    private static boolean ready(Minecraft client) {
        long now = Util.getMillis();
        if (client.screen instanceof NetworkTerminalScreen && client.player.containerMenu instanceof NetworkCoreMenu menu)
            return menu.clientState().ready(now) && menu.craftingGeneration() != 0;
        if (client.screen instanceof MachineScreen screen && client.player.containerMenu instanceof MachineMenu menu) {
            if (!menu.craftingState().ready(now)) return false;
            if (!menu.slots.get(36).isActive()) { screen.prepareRecipeTransfer(); return false; }
            return menu.craftingGeneration() != 0;
        }
        return false;
    }
    private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
    private CraftingReturnClient() { }
}
