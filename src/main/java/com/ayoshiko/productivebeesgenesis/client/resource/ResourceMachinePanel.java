package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceMenuSync;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.client.event.ScreenEvent;

/** 在所有已有机械 GUI 安装相同 MEK 侧面 TAB；不再绘制独立覆盖层。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class ResourceMachinePanel {
    private static GuiResourceWindow window;
    private static GuiResourceSideConfigPage sidesWindow;
    private ResourceMachinePanel() {}
    @SubscribeEvent public static void opening(ScreenEvent.Opening event) { window = null; sidesWindow = null; }
    static void opened(GuiResourceWindow value) { window = value; }
    static void closed(GuiResourceWindow value) { if (window == value) window = null; }
    public static void accept(ResourceMenuSync.Reply reply) { if (window != null) window.accept(reply); }
    static void openedSides(GuiResourceSideConfigPage value) {
        if (sidesWindow != null && sidesWindow != value) sidesWindow.deactivate();
        sidesWindow = value;
    }
    static void closedSides(GuiResourceSideConfigPage value) { if (sidesWindow == value) sidesWindow = null; }
    public static void acceptSides(com.ayoshiko.productivebeesgenesis.resource.ResourceSideConfigSync.Reply reply) {
        if (sidesWindow != null) sidesWindow.accept(reply);
    }

    @SubscribeEvent public static void init(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen) || !(screen instanceof IGuiWrapper gui)
                || !(screen.getMenu() instanceof MekanismTileContainer<?> menu)) return;
        var tile = menu.getTileEntity();
        if (!(tile instanceof TileEntityMekApiary || ResourceHiveHost.isMekCentrifuge(tile))) return;
        int y = findTabY(screen, gui, 62);
        var tab = new GuiResourceTab[1];
        tab[0] = new GuiResourceTab(gui, menu, screen, y, () -> tab[0]);
        event.addListener(tab[0]);
    }

    private static int findTabY(AbstractContainerScreen<?> screen, IGuiWrapper gui, int start) {
        int y = start;
        for (; y < gui.getHeight() + 112; y += 28) {
            boolean occupied = false;
            for (var child : screen.children()) if (child instanceof GuiElement element
                    && element.getRelativeX() >= gui.getWidth() - 1 && element.getRelativeX() < gui.getWidth() + 28
                    && element.getRelativeY() < y + 28 && element.getRelativeY() + element.getHeight() > y - 2) { occupied = true; break; }
            if (!occupied) break;
        }
        return y;
    }
}
