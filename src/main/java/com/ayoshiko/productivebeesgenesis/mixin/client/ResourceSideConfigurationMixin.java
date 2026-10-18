package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.client.resource.GuiResourceSideConfig;
import com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.IGuiWrapper;
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

/** 只在 Genesis 机器的原生侧面窗口中增加资源页；原生配置仍由 MEK 处理。 */
@Mixin(value = GuiSideConfiguration.class, remap = false)
public abstract class ResourceSideConfigurationMixin extends GuiWindow {
    @Shadow @Final private List<GuiConfigTypeTab> configTabs;
    @Unique private GuiResourceSideConfig productivebeesgenesis$resourcePage;

    protected ResourceSideConfigurationMixin(IGuiWrapper gui, int x, int y, int width, int height, SelectedWindowData data) {
        super(gui, x, y, width, height, data);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void productivebeesgenesis$installResourcePages(IGuiWrapper gui, int x, int y, TileEntityMekanism tile,
            SelectedWindowData data, CallbackInfo callback) {
        if (!ResourceSidePersistence.machine(tile) || !(gui instanceof GuiMekanism<?> screen)
                || !(screen.getMenu() instanceof MekanismTileContainer<?> menu)) return;
        var content = children().stream().filter(element -> !(element instanceof GuiConfigTypeTab
                || element instanceof GuiCloseButton || element instanceof GuiPinButton)).toList();
        productivebeesgenesis$resourcePage = addChild(new GuiResourceSideConfig(
                (GuiSideConfiguration<?>) (Object) this, menu, screen, configTabs.size(), content));
        int totalTabs = configTabs.size() + productivebeesgenesis$resourcePage.resourceTabs().size();
        height = Math.max(height, Math.max(4, totalTabs - 4) * 28 + 4);
    }

    @Inject(method = "setCurrentType", at = @At("TAIL"))
    private void productivebeesgenesis$selectNativeType(TransmissionType type, CallbackInfo callback) {
        if (productivebeesgenesis$resourcePage != null) productivebeesgenesis$resourcePage.selectNative();
    }

    @Inject(method = "renderForeground", at = @At("HEAD"), cancellable = true)
    private void productivebeesgenesis$hideNativeHeading(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo callback) {
        if (productivebeesgenesis$resourcePage != null && productivebeesgenesis$resourcePage.selectedPage()) callback.cancel();
    }
}
