package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.resource.GuiResourceSideConfigPage;
import com.ayoshiko.productivebeesgenesis.client.resource.GuiResourceSideConfigTab;
import com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.GuiCloseButton;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.tab.GuiConfigTypeTab;
import mekanism.client.gui.element.window.GuiSideConfiguration;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** 化学品位于左侧原生页签中，其他扩展资源从右侧打开独立窗口。 */
@Mixin(value = GuiSideConfiguration.class, remap = false)
public abstract class ResourceSideConfigurationMixin extends GuiWindow {
    @Shadow @Final private List<GuiConfigTypeTab> configTabs;
    @Shadow private TransmissionType currentType;
    @Unique private GuiResourceSideConfigPage productivebeesgenesis$chemicalPage;
    @Unique private List<GuiElement> productivebeesgenesis$nativeContent;

    protected ResourceSideConfigurationMixin(IGuiWrapper gui, int x, int y, int width, int height, SelectedWindowData data) {
        super(gui, x, y, width, height, data);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void productivebeesgenesis$installResourceEntry(IGuiWrapper gui, int x, int y, TileEntityMekanism tile,
            SelectedWindowData data, CallbackInfo callback) {
        if (!ResourceSidePersistence.machine(tile) || !(gui instanceof GuiMekanism<?> screen)
                || !(screen.getMenu() instanceof MekanismTileContainer<?> menu)) return;
        var owner = (GuiSideConfiguration<?>) (Object) this;
        if (configTabs.stream().noneMatch(tab -> tab.getTransmissionType() == TransmissionType.CHEMICAL)) {
            productivebeesgenesis$nativeContent = children().stream().filter(element -> !(element instanceof GuiConfigTypeTab
                    || element instanceof GuiCloseButton || element instanceof GuiPinButton)).toList();
            productivebeesgenesis$chemicalPage = addChild(new GuiResourceSideConfigPage(gui, relativeX, relativeY,
                    menu, screen, () -> ResourceNativeType.CHEMICAL,
                    () -> screen.getWindows().stream().findFirst().orElse(null) == owner));
            productivebeesgenesis$chemicalPage.show(false);
            int chemicalY = configTabs.stream().filter(tab -> tab.getTransmissionType() == TransmissionType.ENERGY)
                    .mapToInt(GuiElement::getRelativeY).findFirst().orElse(relativeY + 58) + 28;
            var chemical = addChild(new GuiConfigTypeTab(gui, TransmissionType.CHEMICAL,
                    relativeX - 26, chemicalY, owner, true));
            configTabs.add(chemical);
        }
        int extensionY = relativeY + 2;
        for (var nativeTab : configTabs) {
            if (nativeTab.getRelativeX() >= relativeX + width) extensionY = Math.max(extensionY, nativeTab.getRelativeY() + 28);
        }
        var tab = new GuiResourceSideConfigTab[1];
        tab[0] = new GuiResourceSideConfigTab(gui, menu, screen, relativeX + width, extensionY, false, () -> tab[0]);
        addChild(tab[0]);
    }

    @Inject(method = "updateTabs", at = @At("TAIL"))
    private void productivebeesgenesis$showChemicalPage(CallbackInfo callback) {
        if (productivebeesgenesis$chemicalPage == null) return;
        boolean chemical = currentType == TransmissionType.CHEMICAL;
        for (var element : productivebeesgenesis$nativeContent) element.visible = !chemical;
        productivebeesgenesis$chemicalPage.show(chemical);
    }

    @Inject(method = "renderForeground", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$chemicalHeading(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback) {
        if (productivebeesgenesis$chemicalPage != null && currentType == TransmissionType.CHEMICAL) callback.cancel();
    }
}
