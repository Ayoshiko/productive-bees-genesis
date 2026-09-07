package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.ToggleDirectContainerOutputPayload;
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

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 产物直通 per-tile 开关覆盖层 — 注入到蜂箱与离心机的侧面配置窗口（ITEM 页）。
 * <br/>
 * 一个覆盖层同时服务两类机器（OCP/DRY）：状态读取按方块实体类型分派，
 * 网络包与按钮实现共用。按钮位于 (120, 78)，避开已有的
 * AE 输出(120,6) / AE 输入(120,24) / 熔炼兼容(120,42) / 离心机直输AE(120,60) /
 * MEK 自动弹出(136,6) / 蜂箱直连(136,24) / 离心机优先(136,40) / 清除侧面(136,95)。
 * <p>
 * 全局配置 {@code external_logistics.directContainerOutput} 为总开关：
 * 关闭时按钮灰显、点击不响应（tooltip 说明原因），与 {@link SmeltingCompatOverlay} 一致。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class DirectContainerOutputOverlay {

	/** 按钮 X 偏移 — 与 AE 输出/输入/熔炼/直输 AE 同列 */
	private static final int BUTTON_X_OFFSET = 120;
	/** 按钮 Y 偏移 — 位于离心机直输 AE(60) 下方，保持 4px 间距 */
	private static final int BUTTON_Y_OFFSET = 78;
	private static final int BUTTON_SIZE = 14;

	/** 按钮缓存：key=侧面配置窗口实例，WeakHashMap 在窗口 GC 后自动回收 */
	private static final Map<GuiSideConfiguration<?>, DirectContainerOutputButton> BUTTONS = new WeakHashMap<>();

	private DirectContainerOutputOverlay() {
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		Target target = findTarget(Minecraft.getInstance().screen);
		if (target == null) return;
		GuiSideConfiguration<?> sideConfig = target.sideConfig();
		DirectContainerOutputButton button = BUTTONS.computeIfAbsent(sideConfig, config -> {
			DirectContainerOutputButton created = new DirectContainerOutputButton(target.gui(),
					config.getRelativeX() + BUTTON_X_OFFSET,
					config.getRelativeY() + BUTTON_Y_OFFSET,
					target.tile());
			config.children().add(created);
			return created;
		});
		BlockEntity tile = target.tile();
		button.tile = tile;
		button.visible = target.type() == TransmissionType.ITEM;
		boolean globalEnabled = isGlobalEnabled();
		button.active = globalEnabled;
		if (!globalEnabled) {
			button.setTooltip(Tooltip.create(Component.translatable(
					"productivebeesgenesis.gui.direct_container_output.global_disabled")));
		} else {
			button.setTooltip(Tooltip.create(Component.translatable(isPerTileEnabled(tile)
					? "productivebeesgenesis.gui.direct_container_output.on"
					: "productivebeesgenesis.gui.direct_container_output.off")));
		}
	}

	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		Target target = findTarget(event.getScreen());
		if (target == null || target.type() != TransmissionType.ITEM) return;
		DirectContainerOutputButton button = BUTTONS.get(target.sideConfig());
		if (button == null || !button.visible) return;
		int x = target.gui().getGuiLeft() + target.sideConfig().getRelativeX() + BUTTON_X_OFFSET;
		int y = target.gui().getGuiTop() + target.sideConfig().getRelativeY() + BUTTON_Y_OFFSET;
		if (event.getMouseX() < x || event.getMouseX() >= x + BUTTON_SIZE
				|| event.getMouseY() < y || event.getMouseY() >= y + BUTTON_SIZE) return;
		// 总开关关闭时按钮灰显，不响应点击（服务端 handler 亦有同样校验）
		if (!isGlobalEnabled()) {
			event.setCanceled(true);
			return;
		}
		sendToggle(target.tile());
		event.setCanceled(true);
	}

	/** per-tile 开关状态 — 蜂箱读自身字段，离心机读 AE2 状态持有者（不依赖 AE2 是否加载） */
	static boolean isPerTileEnabled(BlockEntity tile) {
		if (tile instanceof TileEntityMekApiary apiary) {
			return apiary.isDirectContainerOutputEnabled();
		}
		if (tile instanceof IAe2OutputHostBase host
				&& host.productivebeesgenesis$getAe2StateHolder() != null) {
			return host.productivebeesgenesis$getAe2StateHolder().isDirectContainerOutputEnabled();
		}
		return false;
	}

	/** 发送切换包（按钮点击与鼠标命中两条路径共用） */
	static void sendToggle(BlockEntity tile) {
		PacketDistributor.sendToServer(new ToggleDirectContainerOutputPayload(tile.getBlockPos()));
	}

	private static Target findTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		BlockEntity tile = container.getTileEntity();
		// 仅本模组的蜂箱与离心机支持产物直通
		if (!(tile instanceof TileEntityMekApiary) && !(tile instanceof IMekCentrifugeTile)) return null;
		for (GuiWindow window : gui.getWindows()) {
			if (window instanceof GuiSideConfiguration<?> sideConfig) {
				return new Target(gui, sideConfig, tile, currentType(sideConfig));
			}
		}
		return null;
	}

	/** 侧面配置窗口当前激活的传输类型（激活 Tab 的 visible=false，与其他覆盖层一致） */
	private static TransmissionType currentType(GuiSideConfiguration<?> sideConfig) {
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab && !tab.visible) return tab.getTransmissionType();
		}
		for (GuiElement child : sideConfig.children()) {
			if (child instanceof GuiConfigTypeTab tab) return tab.getTransmissionType();
		}
		return TransmissionType.ITEM;
	}

	/** 全局总开关（客户端读同步后的服务端配置；未加载时按关闭处理） */
	private static boolean isGlobalEnabled() {
		if (ModConfig.SERVER == null) return false;
		return ModConfig.SERVER.externalDirectContainerOutput != null
				&& ModConfig.SERVER.externalDirectContainerOutput.get();
	}

	private record Target(
			GuiMekanism<?> gui,
			GuiSideConfiguration<?> sideConfig,
			BlockEntity tile,
			TransmissionType type) {
	}
}
