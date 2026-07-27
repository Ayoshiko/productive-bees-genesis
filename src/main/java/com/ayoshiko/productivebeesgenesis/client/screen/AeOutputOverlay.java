package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Map;
import java.util.WeakHashMap;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
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
 * AE2 输出按钮覆盖层 — 将 per-tile AE2 输出开关注入 MEK 侧面配置窗口
 * <br/>
 * 参考 Mek-Energistics 的 AeOutputConfigOverlay，适配我们的 {@link IAe2OutputHostBase} 接口。
 * <p>
 * <b>原理</b>：通过 NeoForge 的 {@link ScreenEvent} 监听客户端屏幕渲染和鼠标点击，
 * 在检测到 {@link GuiSideConfiguration} 窗口打开时，动态注入 {@link AeOutputButton}
 * 和 {@link AeOutputText} 作为窗口子元素。使用 {@link WeakHashMap} 缓存避免重复创建，
 * 同时在窗口关闭后自动回收。
 * <p>
 * <b>与 Mek-Energistics 的区别</b>：
 * <ul>
 *   <li>宿主判断：使用 {@link IAe2OutputHostBase} 接口而非 MeAeMachine</li>
 *   <li>支持流体切换：canToggle 包含 ITEM 和 FLUID（Mek-Energistics 不支持 FLUID 切换）</li>
 *   <li>全局配置按方块类型区分：蜂箱与离心机使用独立配置项</li>
 *   <li>网络包：使用 {@link CycleAeOutputPayload}（OutputType 枚举）</li>
 * </ul>
 * <p>
 * <b>线程安全</b>：客户端 GUI 渲染单线程，WeakHashMap 无需同步。
 * <p>
 * <b>事件驱动点击检测原理</b>：通过 NeoForge 的 {@link ScreenEvent.MouseButtonPressed.Pre}
 * 事件在 Screen.mouseClicked 之前拦截点击，命中按钮后取消事件避免穿透。按钮自身的
 * onPress 回调作为后备路径（事件被其他模组取消时仍可响应）。
 * <p>
 * <b>修复 v14 渲染阶段不修改状态</b>：按钮/文字创建（computeIfAbsent + children().add）
 * 迁移到 {@link ClientTickEvent.Pre}（每秒 20 次的非渲染阶段），
 * 渲染阶段仅更新 visible/active/tooltip/message 状态，避免 ConcurrentModificationException
 * 和递归渲染风险。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class AeOutputOverlay {

	/**
	 * 按钮在侧面配置窗口中的 X 偏移(潜在问题 26 统一布局)
	 * <br/>
	 * 左列布局:ae2_overlay(120,6) + ae2_input(120,24),与 MEK Eject(136,6) 分列左右
	 * 避免与 MEK Eject 按钮同列混淆,降低用户误点击概率
	 */
	private static final int BUTTON_X_OFFSET = 120;
	/** 按钮在侧面配置窗口中的 Y 偏移(与 MEK Eject 同行 y=6) */
	private static final int BUTTON_Y_OFFSET = 6;
	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;
	/** 状态文字 X 偏移 */
	private static final int AE_TEXT_X_OFFSET = 78;
	/** 状态文字 Y 偏移 */
	private static final int AE_TEXT_Y_OFFSET = 27;
	/** 状态文字宽度 */
	private static final int AE_TEXT_WIDTH = 38;

	/** 按钮缓存：key=侧面配置窗口实例，value=按钮。WeakHashMap 在窗口 GC 后自动回收 */
	private static final Map<GuiSideConfiguration<?>, MekanismButton> BUTTONS = new WeakHashMap<>();
	/** 文字缓存：key=侧面配置窗口实例，value=文字元素 */
	private static final Map<GuiSideConfiguration<?>, AeOutputText> TEXTS = new WeakHashMap<>();

	private AeOutputOverlay() {
	}

	/**
	 * 客户端 tick 事件：在非渲染阶段创建按钮和文字
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：原在 Render.Post 中通过 computeIfAbsent
	 * 创建按钮/文字并调用 sideConfig.children().add() 修改子元素列表，
	 * 可能导致 ConcurrentModificationException（渲染时遍历 children 同时添加新元素）
	 * 和递归渲染（新添加的子元素触发额外渲染）。迁移到 tick 事件中创建，
	 * 渲染阶段仅更新状态。
	 * <p>
	 * 每 tick（1/20 秒）检查当前屏幕，若有侧面配置窗口则确保按钮/文字已创建。
	 * 屏幕关闭时 tick 不触发，元素随 WeakHashMap 自动回收。
	 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// tick 阶段可能无屏幕打开（如游戏世界内未打开 GUI）
		Screen screen = Minecraft.getInstance().screen;
		if (screen == null) return;
		OverlayTarget target = findTarget(screen);
		ensureButton(target);
		// P0-2 修复：updateButton 从 render 阶段迁移到 tick 阶段
		// 原在 ScreenEvent.Render.Post 中调用，修改 button.target/visible/active/tooltip/message 违反"渲染阶段只读"原则
		// tick 频率 20Hz 足够保证按钮状态与 TransmissionType 同步（人眼无感知延迟）
		updateButton(target);
	}

	/**
	 * 渲染事件：无操作（保留订阅以便未来扩展，但不修改任何状态）
	 * <br/>
	 * P0-2 修复：原在 Render.Post 中调用 updateButton 修改按钮/文字状态字段，
	 * 违反"渲染阶段不修改状态"原则。已将 updateButton 迁移到 {@link ClientTickEvent.Pre}。
	 * 渲染阶段由 Minecraft 自行根据元素的 visible/active 字段渲染，本类无需干预。
	 */
	@SubscribeEvent
	public static void render(ScreenEvent.Render.Post event) {
		// AE2 未加载时不注入任何元素；渲染阶段不修改状态，保留订阅以备未来需要只读渲染辅助
	}

	/**
	 * 鼠标点击事件：拦截对 AE2 按钮的点击并发送切换包
	 * <br/>
	 * 仅处理左键点击，且仅当点击落在按钮范围内时取消事件（避免穿透到窗口下层）。
	 * <p>
	 * <b>isCanceled 逻辑修复(潜在问题 23)</b>：
	 * <ul>
	 *   <li>仅在按钮实际被点击(triggered=true)且按钮可见(visible=true)时才取消事件</li>
	 *   <li>点击位置不在按钮范围内时 isCanceled=false,允许事件传递给后续按钮(如 MEK Ejector)</li>
	 *   <li>按钮不可见时(如非 ITEM/FLUID 类型)不取消事件,即使点击坐标在 bounds 内</li>
	 *   <li>添加 cacheHit 检查(与 AeInputOverlay 一致),按钮未创建时不处理点击</li>
	 * </ul>
	 */
	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		OverlayTarget target = findTarget(event.getScreen());
		if (target == null || !canToggle(target.type())) return;
		// 全局配置关闭时不响应点击，避免灰显状态下发送切换网络包
		if (!isGlobalEnabled(target.tile(), target.type())) return;
		// cacheHit 检查(与 AeInputOverlay 一致):按钮未创建时不处理点击,避免在无按钮时误判 bounds
		if (!BUTTONS.containsKey(target.sideConfig())) return;
		ButtonBounds bounds = bounds(target.gui(), target.sideConfig());
		boolean triggered = bounds.contains(event.getMouseX(), event.getMouseY());
		// 按钮可见性检查:仅按钮可见时才取消事件,避免不可见按钮阻止事件传递给 MEK Ejector 等原生按钮
		MekanismButton button = BUTTONS.get(target.sideConfig());
		boolean buttonVisible = button != null && button.visible;
		// isCanceled 逻辑:仅按钮可见且点击命中时才取消事件,允许事件传递给 MEK Ejector
		if (triggered && buttonVisible) {
			sendToggle(target);
			event.setCanceled(true);
		}
	}

	/**
	 * 创建按钮和文字元素并添加为窗口子元素（仅在非渲染阶段调用）
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：使用 computeIfAbsent 保证每个窗口只创建一次按钮/文字，
	 * 创建后调用 sideConfig.children().add() 注入到 Mekanism GUI 渲染管线。
	 * 此方法不更新元素状态，状态更新由 {@link #updateButton} 在渲染阶段处理。
	 */
	private static void ensureButton(OverlayTarget target) {
		if (target == null) return;
		BUTTONS.computeIfAbsent(target.sideConfig(), sideConfig -> {
			AeOutputButton newButton = new AeOutputButton(
					target.gui(),
					sideConfig.getRelativeX() + BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
					target);
			sideConfig.children().add(newButton);
			return newButton;
		});
		TEXTS.computeIfAbsent(target.sideConfig(), sideConfig -> {
			AeOutputText newText = new AeOutputText(
					target.gui(),
					sideConfig.getRelativeX() + AE_TEXT_X_OFFSET,
					sideConfig.getRelativeY() + AE_TEXT_Y_OFFSET);
			sideConfig.children().add(newText);
			return newText;
		});
	}

	/**
	 * 更新按钮和文字状态（仅在渲染阶段调用，不修改 children 列表）
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：仅更新 target 引用、visible、active、tooltip、message，
	 * 保证状态与当前 TransmissionType 同步。元素不存在时（屏幕刚打开首帧）跳过。
	 */
	private static void updateButton(OverlayTarget target) {
		if (target == null) return;
		MekanismButton button = BUTTONS.get(target.sideConfig());
		// 元素尚未创建（屏幕刚打开首帧，tick 尚未执行），跳过更新
		if (button == null) return;
		if (button instanceof AeOutputButton aeButton) {
			aeButton.target = target;
		}
		button.visible = shouldRender(target.type());
		boolean globalEnabled = isGlobalEnabled(target.tile(), target.type());
		button.active = canToggle(target.type()) && globalEnabled;
		button.setMessage(Component.literal("A"));
		// 全局配置关闭时 tooltip 提示需要开启配置
		if (globalEnabled) {
			button.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae2_output.button")));
		} else {
			button.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae2_output.global_disabled")));
		}
		AeOutputText text = TEXTS.get(target.sideConfig());
		if (text != null) {
			text.target = target;
			text.visible = shouldRender(target.type());
		}
	}

	/**
	 * 查找当前打开的侧面配置窗口作为注入目标
	 * <br/>
	 * 遍历 GUI 中所有打开的窗口，找到第一个 {@link GuiSideConfiguration} 实例，
	 * 结合屏幕目标信息构建 {@link OverlayTarget}。
	 */
	private static OverlayTarget findTarget(Screen screen) {
		ScreenTarget screenTarget = findScreenTarget(screen);
		if (screenTarget == null) return null;
		for (GuiWindow window : screenTarget.gui().getWindows()) {
			if (window instanceof GuiSideConfiguration<?> sideConfig) {
				TransmissionType type = getCurrentType(sideConfig);
				return new OverlayTarget(screenTarget.gui(), sideConfig, screenTarget.tile(), type);
			}
		}
		return null;
	}

	/**
	 * 识别屏幕是否为持有一个 {@link IAe2OutputHostBase} 方块实体的 Mekanism GUI
	 * <br/>
	 * 三重判断：GuiMekanism + MekanismTileContainer + tile 实现 IAe2OutputHostBase。
	 * 只有我们的方块实体（离心机/蜂箱）才会实现此接口。
	 */
	private static ScreenTarget findScreenTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		if (!(container.getTileEntity() instanceof IAe2OutputHostBase)) return null;
		return new ScreenTarget(gui, container.getTileEntity());
	}

	/**
	 * 获取侧面配置窗口当前激活的 TransmissionType
	 * <br/>
	 * Mekanism 的 GuiConfigTypeTab 中，激活的 Tab 其 visible=false（按钮隐藏表示当前选中）。
	 * 优先查找不可见的 Tab，其次回退到第一个可见 Tab，最终回退到 ITEM。
	 */
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

	/**
	 * 判断指定类型是否可切换 — 仅 ITEM 和 FLUID
	 * <br/>
	 * 与 Mek-Energistics 不同，我们支持流体切换（不支持 CHEMICAL）。
	 * 包级可见以供 {@link AeOutputButton} 点击回调复用。
	 */
	static boolean canToggle(TransmissionType type) {
		return type == TransmissionType.ITEM || type == TransmissionType.FLUID;
	}

	/**
	 * 判断是否应渲染按钮和文字 — canToggle 类型或 FLUID
	 * <br/>
	 * 由于 canToggle 已包含 FLUID，此方法等价于 canToggle，
	 * 但保留显式 FLUID 判断以明确意图并向前兼容未来 canToggle 调整。
	 */
	private static boolean shouldRender(TransmissionType type) {
		return canToggle(type) || type == TransmissionType.FLUID;
	}

	/**
	 * 判断全局配置是否启用指定类型的 AE2 输出
	 * <br/>
	 * 按方块类型区分：蜂箱使用 apiary* 配置项，离心机使用 mekCentrifuge* 配置项。
	 * 配置项在 AE2 未加载时为 null（条件化注册），需 null 守卫。
	 */
	private static boolean isGlobalEnabled(BlockEntity tile, TransmissionType type) {
		if (ModConfig.SERVER == null) return false;
		boolean isApiary = tile instanceof TileEntityMekApiary;
		if (type == TransmissionType.FLUID) {
			return isApiary
					? ModConfig.SERVER.apiaryAeFluidOutputEnabled != null
							&& ModConfig.SERVER.apiaryAeFluidOutputEnabled.get()
					: ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled != null
							&& ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.get();
		}
		// ITEM 类型
		return isApiary
				? ModConfig.SERVER.apiaryAeOutputEnabled != null
						&& ModConfig.SERVER.apiaryAeOutputEnabled.get()
				: ModConfig.SERVER.mekCentrifugeAeOutputEnabled != null
						&& ModConfig.SERVER.mekCentrifugeAeOutputEnabled.get();
	}

	/**
	 * 查询 per-tile AE2 输出状态
	 * <br/>
	 * 委托给 {@link IAe2OutputHostBase} 的 per-tile 开关方法。
	 * 物品用 isAeItemOutputEnabled，流体用 isAeFluidOutputEnabled。
	 */
	static boolean isPerTileEnabled(BlockEntity tile, TransmissionType type) {
		if (!(tile instanceof IAe2OutputHostBase host)) return false;
		return type == TransmissionType.FLUID
				? host.productivebeesgenesis$isAeFluidOutputEnabled()
				: host.productivebeesgenesis$isAeItemOutputEnabled();
	}

	/**
	 * 发送切换 per-tile AE2 输出的网络包到服务端
	 * <br/>
	 * 将 Mekanism 的 TransmissionType 转换为我们的 OutputType 枚举。
	 * 仅 canToggle 类型会调用此方法（ITEM/FLUID）。
	 */
	private static void sendToggle(OverlayTarget target) {
		CycleAeOutputPayload.OutputType outputType = target.type() == TransmissionType.FLUID
				? CycleAeOutputPayload.OutputType.FLUID
				: CycleAeOutputPayload.OutputType.ITEM;
		PacketDistributor.sendToServer(new CycleAeOutputPayload(target.tile().getBlockPos(), outputType));
	}

	/**
	 * 计算按钮在屏幕上的绝对坐标边界，用于点击检测
	 */
	private static ButtonBounds bounds(GuiMekanism<?> gui, GuiSideConfiguration<?> sideConfig) {
		return new ButtonBounds(
				gui.getGuiLeft() + sideConfig.getRelativeX() + BUTTON_X_OFFSET,
				gui.getGuiTop() + sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
				BUTTON_SIZE);
	}

	/**
	 * 屏幕目标 — 持有 GUI 实例和方块实体引用
	 * <br/>
	 * findScreenTarget 的返回值，用于 findTarget 构建 OverlayTarget。
	 */
	private record ScreenTarget(GuiMekanism<?> gui, BlockEntity tile) {
	}

	/**
	 * 覆盖层目标 — 持有注入按钮所需的全部上下文
	 * <br/>
	 * 公开 record，供 {@link AeOutputButton} 和 {@link AeOutputText} 引用。
	 *
	 * @param gui      Mekanism GUI 实例
	 * @param sideConfig 侧面配置窗口实例（按钮注入目标）
	 * @param tile     方块实体（实现 IAe2OutputHostBase）
	 * @param type     当前激活的传输类型（ITEM/FLUID/...）
	 */
	public record OverlayTarget(
			GuiMekanism<?> gui,
			GuiSideConfiguration<?> sideConfig,
			BlockEntity tile,
			TransmissionType type) {
	}

	/**
	 * 按钮边界 — 用于鼠标点击命中检测
	 */
	private record ButtonBounds(int x, int y, int size) {
		private boolean contains(double mouseX, double mouseY) {
			return mouseX >= this.x && mouseX < this.x + this.size
					&& mouseY >= this.y && mouseY < this.y + this.size;
		}
	}
}
