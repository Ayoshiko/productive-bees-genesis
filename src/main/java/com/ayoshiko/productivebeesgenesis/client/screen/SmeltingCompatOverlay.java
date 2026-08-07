package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.ToggleSmeltingCompatPayload;

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
import org.lwjgl.glfw.GLFW;

/**
 * 离心机电力熔炼炉配方兼容开关覆盖层 — 注入到 MEK 侧面配置窗口
 * <br/>
 * 仿 {@link AeInputOverlay}：在侧面配置窗口（ITEM 页）注入 "F" 按钮，
 * 点击发送 {@link ToggleSmeltingCompatPayload} 切换 per-tile 熔炉配方兼容开关。
 * <ul>
 *   <li>仅离心机（{@link IMekCentrifugeTile}）显示</li>
 *   <li>按钮 active 受全局总开关 {@code mekCentrifugeSmeltingCompatEnabled} 控制，
 *       总开关关闭时灰显且不可点击</li>
 *   <li>tooltip 显示当前 per-tile 开关状态（经容器 SyncableBoolean 同步）</li>
 * </ul>
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class SmeltingCompatOverlay {

	/** 按钮 X 偏移 — 与 AeOutput(120,6)/AeInput(120,24) 同列，位于其下方 y=42 */
	private static final int BUTTON_X_OFFSET = 120;
	/** 按钮 Y 偏移 — 与 AeInputButton 底部保持 4px 间距 */
	private static final int BUTTON_Y_OFFSET = 42;
	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;

	/** 按钮缓存：key=侧面配置窗口实例。WeakHashMap 在窗口 GC 后自动回收 */
	private static final Map<GuiSideConfiguration<?>, SmeltingCompatButton> BUTTONS = new WeakHashMap<>();

	private SmeltingCompatOverlay() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Screen screen = Minecraft.getInstance().screen;
		if (screen == null) return;
		AeInputOverlay.OverlayTarget target = findTarget(screen);
		ensureButton(target);
		updateButton(target);
	}

	@SubscribeEvent
	public static void render(ScreenEvent.Render.Post event) {
		// 渲染阶段不修改状态，按钮由 Minecraft 按 visible/active 字段渲染
	}

	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		AeInputOverlay.OverlayTarget target = findTarget(event.getScreen());
		if (target == null) return;
		SmeltingCompatButton button = BUTTONS.get(target.sideConfig());
		if (button == null || !button.visible) return;
		ButtonBounds bounds = bounds(target.gui(), target.sideConfig());
		if (!bounds.contains(event.getMouseX(), event.getMouseY())) return;
		// 总开关关闭时按钮灰显，不响应点击
		if (!isGlobalEnabled()) return;
		PacketDistributor.sendToServer(new ToggleSmeltingCompatPayload(target.tile().getBlockPos()));
		event.setCanceled(true);
	}

	private static void ensureButton(AeInputOverlay.OverlayTarget target) {
		if (target == null) return;
		BUTTONS.computeIfAbsent(target.sideConfig(), sideConfig -> {
			SmeltingCompatButton button = new SmeltingCompatButton(
					target.gui(),
					sideConfig.getRelativeX() + BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
					Component.literal("F"),
					t -> PacketDistributor.sendToServer(new ToggleSmeltingCompatPayload(t.tile().getBlockPos())),
					target);
			sideConfig.children().add(button);
			return button;
		});
	}

	private static void updateButton(AeInputOverlay.OverlayTarget target) {
		if (target == null) return;
		SmeltingCompatButton button = BUTTONS.get(target.sideConfig());
		if (button == null) return;
		button.target = target;
		button.visible = shouldRender(target.type());
		boolean globalEnabled = isGlobalEnabled();
		button.active = globalEnabled;
		if (!globalEnabled) {
			button.setTooltip(Tooltip.create(Component.translatable(
					"productivebeesgenesis.gui.smelting_compat.global_disabled")));
		} else {
			boolean perTile = target.tile() instanceof IAe2OutputHostBase host
					&& host.productivebeesgenesis$getAe2StateHolder() != null
					&& host.productivebeesgenesis$getAe2StateHolder().isSmeltingCompatEnabled();
			button.setTooltip(Tooltip.create(Component.translatable(
					perTile ? "productivebeesgenesis.gui.smelting_compat.on"
							: "productivebeesgenesis.gui.smelting_compat.off")));
		}
	}

	private static AeInputOverlay.OverlayTarget findTarget(Screen screen) {
		ScreenTarget screenTarget = findScreenTarget(screen);
		if (screenTarget == null) return null;
		for (GuiWindow window : screenTarget.gui().getWindows()) {
			if (window instanceof GuiSideConfiguration<?> sideConfig) {
				return new AeInputOverlay.OverlayTarget(screenTarget.gui(), sideConfig, screenTarget.tile(), getCurrentType(sideConfig));
			}
		}
		return null;
	}

	private static ScreenTarget findScreenTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		if (!(container.getTileEntity() instanceof IMekCentrifugeTile)) return null;
		return new ScreenTarget(gui, container.getTileEntity());
	}

	private static TransmissionType getCurrentType(GuiSideConfiguration<?> sideConfig) {
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab && !tab.visible) {
				return tab.getTransmissionType();
			}
		}
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab) {
				return tab.getTransmissionType();
			}
		}
		return TransmissionType.ITEM;
	}

	/** 仅 ITEM 页显示（熔炉配方为物品输入） */
	private static boolean shouldRender(TransmissionType type) {
		return type == TransmissionType.ITEM;
	}

	private static boolean isGlobalEnabled() {
		if (ModConfig.SERVER == null) return false;
		return ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled != null
				&& ModConfig.SERVER.mekCentrifugeSmeltingCompatEnabled.get();
	}

	private static ButtonBounds bounds(GuiMekanism<?> gui, GuiSideConfiguration<?> sideConfig) {
		return new ButtonBounds(
				gui.getGuiLeft() + sideConfig.getRelativeX() + BUTTON_X_OFFSET,
				gui.getGuiTop() + sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
				BUTTON_SIZE);
	}

	private record ScreenTarget(GuiMekanism<?> gui, BlockEntity tile) {
	}

	private record ButtonBounds(int x, int y, int size) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseX < this.x + this.size
					&& mouseY >= this.y && mouseY < this.y + this.size;
		}
	}
}
