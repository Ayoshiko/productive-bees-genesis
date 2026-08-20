package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.network.ToggleApiaryDirectEjectPayload;
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
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.BUTTON_SIZE;
import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.BUTTON_X_OFFSET;
import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.BUTTON_Y_OFFSET;
import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.CENTRIFUGE_PRIORITY_BUTTON_X_OFFSET;
import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.CENTRIFUGE_PRIORITY_BUTTON_Y_OFFSET;
import static com.ayoshiko.productivebeesgenesis.client.screen.ApiaryDirectEjectLayout.DIRECT_AE_BUTTON_X_OFFSET;

/** 在机械蜂箱的 Mekanism 物品侧面配置页注入快速直连开关。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class ApiaryDirectEjectOverlay {

	private static final Map<GuiSideConfiguration<?>, WeakReference<ApiaryDirectEjectButton>> BUTTONS =
			new WeakHashMap<>();
	private static final Map<GuiSideConfiguration<?>, WeakReference<ApiaryDirectAeOutputButton>> DIRECT_AE_BUTTONS =
			new WeakHashMap<>();
	private static final Map<GuiSideConfiguration<?>, WeakReference<ApiaryCentrifugePriorityButton>>
			CENTRIFUGE_PRIORITY_BUTTONS = new WeakHashMap<>();

	private ApiaryDirectEjectOverlay() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Screen screen = Minecraft.getInstance().screen;
		if (screen == null) return;
		OverlayTarget target = findTarget(screen);
		if (target == null) return;
		GuiSideConfiguration<?> sideConfig = target.sideConfig();
		ApiaryDirectEjectButton button = getButton(sideConfig);
		if (button == null) {
			button = new ApiaryDirectEjectButton(target.gui(),
					sideConfig.getRelativeX() + BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET, target);
			sideConfig.children().add(button);
			BUTTONS.put(sideConfig, new WeakReference<>(button));
		}
		button.target = target;
		button.visible = target.type() == TransmissionType.ITEM;
		boolean enabled = target.apiary().isDirectEjectEnabled();
		button.setTooltip(Tooltip.create(Component.translatable(enabled
				? "productivebeesgenesis.gui.apiary_direct_eject.enabled"
				: "productivebeesgenesis.gui.apiary_direct_eject.disabled")));

		// 直连 AE 按钮与离心机优先级按钮发送 CycleAeOutputPayload（服务端注册被 AE2 守卫），
		// AE2 未安装时不创建，否则点击触发 UnsupportedOperationException（Issue #8 对称修复）
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		ApiaryDirectAeOutputButton directAeButton = getDirectAeButton(sideConfig);
		if (directAeButton == null) {
			directAeButton = new ApiaryDirectAeOutputButton(target.gui(),
					sideConfig.getRelativeX() + DIRECT_AE_BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET, target);
			sideConfig.children().add(directAeButton);
			DIRECT_AE_BUTTONS.put(sideConfig, new WeakReference<>(directAeButton));
		}
		directAeButton.target = target;
		directAeButton.visible = target.type() == TransmissionType.ITEM;
		directAeButton.active = target.apiary().productivebeesgenesis$isAeItemOutputEnabled()
				|| target.apiary().productivebeesgenesis$isAeFluidOutputEnabled();
		directAeButton.setTooltip(Tooltip.create(Component.translatable(
				target.apiary().isDirectAeOutputEnabled()
						? "productivebeesgenesis.gui.apiary_direct_ae.enabled"
						: "productivebeesgenesis.gui.apiary_direct_ae.disabled")));

		ApiaryCentrifugePriorityButton priorityButton = getCentrifugePriorityButton(sideConfig);
		if (priorityButton == null) {
			priorityButton = new ApiaryCentrifugePriorityButton(target.gui(),
					sideConfig.getRelativeX() + CENTRIFUGE_PRIORITY_BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + CENTRIFUGE_PRIORITY_BUTTON_Y_OFFSET, target);
			sideConfig.children().add(priorityButton);
			CENTRIFUGE_PRIORITY_BUTTONS.put(sideConfig, new WeakReference<>(priorityButton));
		}
		priorityButton.target = target;
		priorityButton.visible = target.type() == TransmissionType.ITEM;
		priorityButton.setTooltip(Tooltip.create(Component.translatable(
				target.apiary().isCentrifugePriorityEnabled()
						? "productivebeesgenesis.gui.apiary_centrifuge_priority.enabled"
						: "productivebeesgenesis.gui.apiary_centrifuge_priority.disabled")));
	}

	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		// AE2 未加载时 AE 相关按钮未注入（防御性短路，正常路径 null 检查已覆盖）
		boolean aeLoaded = Ae2IntegrationLoader.isAe2Loaded();
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		OverlayTarget target = findTarget(event.getScreen());
		if (target == null || target.type() != TransmissionType.ITEM) return;
		ApiaryDirectEjectButton button = getButton(target.sideConfig());
		if (button == null || !button.visible) return;
		ApiaryCentrifugePriorityButton priorityButton = getCentrifugePriorityButton(target.sideConfig());
		ButtonBounds priorityBounds = bounds(
				target,
				CENTRIFUGE_PRIORITY_BUTTON_X_OFFSET,
				CENTRIFUGE_PRIORITY_BUTTON_Y_OFFSET);
		if (aeLoaded && priorityButton != null && priorityButton.visible
				&& priorityBounds.contains(event.getMouseX(), event.getMouseY())) {
			PacketDistributor.sendToServer(new com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload(
					target.apiary().getBlockPos(),
					com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload.OutputType
							.APIARY_CENTRIFUGE_PRIORITY));
			event.setCanceled(true);
			return;
		}
		ApiaryDirectAeOutputButton directAeButton = getDirectAeButton(target.sideConfig());
		ButtonBounds directAeBounds = bounds(target, DIRECT_AE_BUTTON_X_OFFSET);
		if (aeLoaded && directAeButton != null && directAeButton.visible && directAeButton.active
				&& directAeBounds.contains(event.getMouseX(), event.getMouseY())) {
			PacketDistributor.sendToServer(new com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload(
					target.apiary().getBlockPos(),
					com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload.OutputType.APIARY_DIRECT));
			event.setCanceled(true);
			return;
		}
		ButtonBounds bounds = bounds(target);
		if (bounds.contains(event.getMouseX(), event.getMouseY())) {
			PacketDistributor.sendToServer(new ToggleApiaryDirectEjectPayload(target.apiary().getBlockPos()));
			event.setCanceled(true);
		}
	}

	private static ApiaryDirectEjectButton getButton(GuiSideConfiguration<?> sideConfig) {
		WeakReference<ApiaryDirectEjectButton> reference = BUTTONS.get(sideConfig);
		return reference == null ? null : reference.get();
	}

	private static ApiaryDirectAeOutputButton getDirectAeButton(GuiSideConfiguration<?> sideConfig) {
		WeakReference<ApiaryDirectAeOutputButton> reference = DIRECT_AE_BUTTONS.get(sideConfig);
		return reference == null ? null : reference.get();
	}

	private static ApiaryCentrifugePriorityButton getCentrifugePriorityButton(
			GuiSideConfiguration<?> sideConfig) {
		WeakReference<ApiaryCentrifugePriorityButton> reference =
				CENTRIFUGE_PRIORITY_BUTTONS.get(sideConfig);
		return reference == null ? null : reference.get();
	}

	private static OverlayTarget findTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		if (!(container.getTileEntity() instanceof TileEntityMekApiary apiary)) return null;
		for (GuiWindow window : gui.getWindows()) {
			if (window instanceof GuiSideConfiguration<?> sideConfig) {
				return new OverlayTarget(gui, sideConfig, apiary, getCurrentType(sideConfig));
			}
		}
		return null;
	}

	private static TransmissionType getCurrentType(GuiSideConfiguration<?> sideConfig) {
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab && !tab.visible) return tab.getTransmissionType();
		}
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab) return tab.getTransmissionType();
		}
		return TransmissionType.ITEM;
	}

	private static ButtonBounds bounds(OverlayTarget target) {
		return bounds(target, BUTTON_X_OFFSET);
	}

	private static ButtonBounds bounds(OverlayTarget target, int xOffset) {
		return bounds(target, xOffset, BUTTON_Y_OFFSET);
	}

	private static ButtonBounds bounds(OverlayTarget target, int xOffset, int yOffset) {
		return new ButtonBounds(
				target.gui().getGuiLeft() + target.sideConfig().getRelativeX() + xOffset,
				target.gui().getGuiTop() + target.sideConfig().getRelativeY() + yOffset,
				BUTTON_SIZE);
	}

	public record OverlayTarget(GuiMekanism<?> gui, GuiSideConfiguration<?> sideConfig,
			TileEntityMekApiary apiary, TransmissionType type) {
	}

	private record ButtonBounds(int x, int y, int size) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= x && mouseX < x + size && mouseY >= y && mouseY < y + size;
		}
	}
}
