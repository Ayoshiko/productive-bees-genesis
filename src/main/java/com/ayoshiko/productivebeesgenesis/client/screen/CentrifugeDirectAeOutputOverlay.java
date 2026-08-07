package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.glfw.GLFW;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.tab.GuiConfigTypeTab;
import mekanism.client.gui.element.window.GuiSideConfiguration;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** 在离心机侧面配置 ITEM 页加入新产物直输 AE 开关。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class CentrifugeDirectAeOutputOverlay {

	private static final int BUTTON_X_OFFSET = 120;
	private static final int BUTTON_Y_OFFSET = 60;
	private static final int BUTTON_SIZE = 14;
	private static final Map<GuiSideConfiguration<?>, CentrifugeDirectAeOutputButton> BUTTONS = new WeakHashMap<>();

	private CentrifugeDirectAeOutputOverlay() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		AeInputOverlay.OverlayTarget target = findTarget(Minecraft.getInstance().screen);
		if (target == null) return;
		CentrifugeDirectAeOutputButton button = BUTTONS.computeIfAbsent(target.sideConfig(), sideConfig -> {
			CentrifugeDirectAeOutputButton created = new CentrifugeDirectAeOutputButton(target.gui(),
					sideConfig.getRelativeX() + BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
					Component.literal("A"),
					t -> PacketDistributor.sendToServer(new CycleAeOutputPayload(t.tile().getBlockPos(),
							CycleAeOutputPayload.OutputType.CENTRIFUGE_DIRECT)), target);
			sideConfig.children().add(created);
			return created;
		});
		button.target = target;
		button.visible = target.type() == TransmissionType.ITEM;
		boolean enabled = target.tile() instanceof IAe2OutputHostBase host
				&& host.productivebeesgenesis$getAe2StateHolder() != null
				&& host.productivebeesgenesis$getAe2StateHolder().isCentrifugeDirectAeOutputEnabled();
		button.setTooltip(Tooltip.create(Component.translatable(enabled
				? "productivebeesgenesis.gui.centrifuge_direct_ae_output.on"
				: "productivebeesgenesis.gui.centrifuge_direct_ae_output.off")));
	}

	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		AeInputOverlay.OverlayTarget target = findTarget(event.getScreen());
		if (target == null) return;
		CentrifugeDirectAeOutputButton button = BUTTONS.get(target.sideConfig());
		if (button == null || !button.visible) return;
		int x = target.gui().getGuiLeft() + target.sideConfig().getRelativeX() + BUTTON_X_OFFSET;
		int y = target.gui().getGuiTop() + target.sideConfig().getRelativeY() + BUTTON_Y_OFFSET;
		if (event.getMouseX() < x || event.getMouseX() >= x + BUTTON_SIZE
				|| event.getMouseY() < y || event.getMouseY() >= y + BUTTON_SIZE) return;
		PacketDistributor.sendToServer(new CycleAeOutputPayload(target.tile().getBlockPos(),
				CycleAeOutputPayload.OutputType.CENTRIFUGE_DIRECT));
		event.setCanceled(true);
	}

	private static AeInputOverlay.OverlayTarget findTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		BlockEntity tile = container.getTileEntity();
		if (!(tile instanceof IMekCentrifugeTile)) return null;
		for (GuiWindow window : gui.getWindows()) {
			if (window instanceof GuiSideConfiguration<?> sideConfig) {
				return new AeInputOverlay.OverlayTarget(gui, sideConfig, tile, currentType(sideConfig));
			}
		}
		return null;
	}

	private static TransmissionType currentType(GuiSideConfiguration<?> sideConfig) {
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab && !tab.visible) return tab.getTransmissionType();
		}
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab) return tab.getTransmissionType();
		}
		return TransmissionType.ITEM;
	}
}
