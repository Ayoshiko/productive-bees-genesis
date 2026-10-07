package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.client.NetworkTerminalScreen;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineMenu;
import java.nio.file.*;
import net.minecraft.Util;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class NativeSlotsClient {
    private record Click(int slot, int button, ClickType type) { }
    private static int previous = -1, step;
    private static boolean sortedSeen;
    static CraftingClient.Reply advance(Minecraft client, int stage, boolean owner) throws Exception {
        if (previous != stage) { previous = stage; step = 0; }
        if (!owner && stage >= 804 && stage <= 822 && stage != 809 && stage != 810) return ack();
        if (stage == 818) return client.player.containerMenu == client.player.inventoryMenu ? ack() : null;
        if (stage == 815 || stage == 819) {
            if (step == 0) {
                if (!(client.player.getMainHandItem().getItem() instanceof WirelessTerminalItem)) return null;
                client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND); step++; return null;
            }
            return ready(client) ? ack() : null;
        }
        if (!ready(client)) return null;
        var menu = client.player.containerMenu;
        if (stage == 80 || stage == 825) {
            var held = menu.getCarried();
            require(held.is(owner ? Items.DIAMOND : Items.EMERALD) && held.getCount() == (owner ? 7 : 5), "Private cursor did not restore on client");
            return ack();
        }
        if (stage == 800) return ack();
        if (stage == 826) {
            if (step++ == 0) { picture(client, "native-slots.png"); ((AbstractContainerScreen<?>) client.screen).resize(client, 320, 240); return null; }
            picture(client, "native-compact.png"); return ack();
        }
        if (stage == 810 && !sortedSeen) {
            require(menu.slots.get(37).getItem().is(Items.STONE) && menu.slots.get(39).getItem().is(Items.APPLE), "Standard Container change did not reach shared viewer");
            sortedSeen = true; return owner ? null : ack();
        }
        Click[] clicks = switch (stage) {
            case 801, 817, 823 -> new Click[]{pick(27, 0)};
            case 802 -> new Click[]{pick(owner ? 36 : 37, 1)};
            case 803 -> new Click[]{pick(0, 0)};
            case 804 -> new Click[]{pick(0, 1)};
            case 805 -> new Click[]{new Click(-999, 0, ClickType.QUICK_CRAFT), new Click(38, 1, ClickType.QUICK_CRAFT),
                new Click(39, 1, ClickType.QUICK_CRAFT), new Click(-999, 2, ClickType.QUICK_CRAFT)};
            case 806 -> new Click[]{shift(38)};
            case 807 -> new Click[]{new Click(39, 1, ClickType.SWAP)};
            case 808 -> new Click[]{pick(0, 0), new Click(0, 0, ClickType.PICKUP_ALL)};
            case 809 -> new Click[]{owner ? pick(36, 0) : shift(0)};
            case 810 -> new Click[]{shift(37), shift(39)};
            case 811, 821 -> new Click[]{pick(45, 0)};
            case 812 -> new Click[]{new Click(0, 0, ClickType.PICKUP_ALL)};
            case 813 -> new Click[]{shift(45)};
            case 814 -> new Click[]{shift(36), new Click(0, 0, ClickType.THROW), pick(-999, 0)};
            case 816 -> new Click[]{pick(35, 0), new Click(0, 8, ClickType.SWAP), shift(35)};
            case 820 -> new Click[]{pick(35, 0), pick(27, 0), pick(36, 0)};
            case 822 -> new Click[]{shift(36)};
            case 824 -> new Click[]{pick(-999, 0), new Click(36, 0, ClickType.THROW)};
            default -> new Click[0];
        };
        if (stage == 805 && step == 0) {
            for (var click : clicks) client.gameMode.handleInventoryMouseClick(menu.containerId, click.slot(), click.button(), click.type(), client.player);
            step = clicks.length; return null;
        }
        if (step < clicks.length) {
            var click = clicks[step++];
            if (click.type() == ClickType.PICKUP && click.slot() >= 0) {
                var screen = (AbstractContainerScreen<?>) client.screen; var slot = menu.slots.get(click.slot());
                double x = screen.getGuiLeft() + slot.x + 8, y = screen.getGuiTop() + slot.y + 8;
                require(screen.mouseClicked(x, y, click.button()), "Native UI click missed"); screen.mouseReleased(x, y, click.button());
            } else client.gameMode.handleInventoryMouseClick(menu.containerId, click.slot(), click.button(), click.type(), client.player);
            return null;
        }
        if (stage == 821) picture(client, "native-machine.png");
        return ack();
    }
    private static boolean ready(Minecraft client) {
        if (!(client.screen instanceof AbstractContainerScreen<?>)) return false;
        long now = Util.getMillis();
        if (client.player.containerMenu instanceof NetworkCoreMenu menu) {
            if (!menu.clientState().ready(now)) return false;
            if (!menu.slots.get(36).isActive()) { ClientTerminalProbe.press((NetworkTerminalScreen) client.screen, "tab.4"); return false; }
            return menu.craftingGeneration() != 0;
        }
        if (client.player.containerMenu instanceof MachineMenu menu) {
            if (!menu.craftingState().ready(now)) return false;
            if (!menu.slots.get(36).isActive()) { ((com.ayoshiko.productivebeesgenesis.multiblock.client.MachineScreen) client.screen).prepareRecipeTransfer(); return false; }
            return menu.craftingGeneration() != 0;
        }
        return false;
    }
    private static Click pick(int slot, int button) { return new Click(slot, button, ClickType.PICKUP); }
    private static Click shift(int slot) { return new Click(slot, 0, ClickType.QUICK_MOVE); }
    private static CraftingClient.Reply ack() { return new CraftingClient.Reply(0, -1); }
    private static void picture(Minecraft client, String name) throws Exception {
        Files.createDirectories(Path.of("results"));
        try (var image = Screenshot.takeScreenshot(client.getMainRenderTarget())) { image.writeToFile(Path.of("results", name)); }
    }
    private NativeSlotsClient() { }
}
