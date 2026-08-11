package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.network.OpenAeInputConfigPayload;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
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
import net.minecraft.core.BlockPos;
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
	 * AE2 输入拉取覆盖层 — 将 per-tile AE2 输入拉取控制注入 MEK 侧面配置窗口
	 * <br/>
	 * 参考 {@link AeOutputOverlay} 的模式，适配 {@link IAe2InputHost} 接口。
	 * 注入 1 个按钮：打开 AE2 输入拉取配置窗口（{@link GuiAeInputConfig}）。
	 * <p>
	 * <b>与输出覆盖层的区别</b>：
	 * <ul>
	 *   <li>宿主判断：使用 {@link IAe2InputHost} 接口而非 {@code IAe2OutputHostBase}</li>
	 *   <li>仅 ITEM 类型显示（输入拉取只处理物品）</li>
	 *   <li>1 个打开窗口按钮而非 1 按钮 + 1 文字</li>
	 *   <li>全局配置检查 {@code mekCentrifugeAeInputEnabled}</li>
	 *   <li>网络包：{@link OpenAeInputConfigPayload}（请求服务端推送过滤器状态）</li>
	 * </ul>
	 * <p>
	 * <b>按钮交互</b>：点击时在 MEK GUI 上添加 {@link GuiAeInputConfig} 窗口，
	 * 同时发送 {@link OpenAeInputConfigPayload} 请求服务端推送最新过滤器状态到客户端。
	 * 客户端通过 {@code SyncAeInputFilterEntriesPayload} 接收最新状态并刷新窗口显示。
	 * <p>
	 * <b>线程安全</b>：客户端 GUI 渲染单线程，WeakHashMap 无需同步。
	 * <p>
	 * <b>事件驱动点击检测原理</b>：通过 NeoForge 的 {@link ScreenEvent.MouseButtonPressed.Pre}
	 * 事件在 Screen.mouseClicked 之前拦截点击，命中按钮后取消事件避免穿透。按钮自身的
	 * onPress 回调作为后备路径（事件被其他模组取消时仍可响应）。
	 * <p>
	 * <b>修复 v14 渲染阶段不修改状态</b>：按钮创建（computeIfAbsent + children().add）
	 * 迁移到 {@link ClientTickEvent.Pre}（每秒 20 次的非渲染阶段），
	 * 渲染阶段仅更新 visible/active/tooltip 状态，避免 ConcurrentModificationException
	 * 和递归渲染风险。
	 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class AeInputOverlay {

	/**
	 * 打开配置窗口按钮 X 偏移(潜在问题 26 统一布局)
	 * <br/>
	 * 左列布局:ae2_overlay(120,6) + ae2_input(120,24),与 MEK Eject(136,6) 分列左右
	 * 原 BUTTON_X_OFFSET=136 与 MEK Eject 同列,用户易误点击 Eject 期望打开输入配置
	 * 改为 120 与 ae2_overlay 对齐,形成左列,MEK Eject 独占右列
	 */
	private static final int BUTTON_X_OFFSET = 120;
	/** 按钮 Y 偏移(位于 ae2_overlay(120,6) 正下方 y=24,与 ae2_overlay 底部 20 保持 4px 间距) */
	private static final int BUTTON_Y_OFFSET = 24;
	/** 按钮尺寸（宽=高） */
	private static final int BUTTON_SIZE = 14;

	/** 按钮缓存：key=侧面配置窗口实例。WeakHashMap 在窗口 GC 后自动回收 */
	private static final Map<GuiSideConfiguration<?>, AeInputButton> BUTTONS = new WeakHashMap<>();

	private AeInputOverlay() {
	}

	/**
	 * 客户端 tick 事件：在非渲染阶段创建按钮
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：原在 Render.Post 中通过 computeIfAbsent
	 * 创建按钮并调用 sideConfig.children().add() 修改子元素列表，
	 * 可能导致 ConcurrentModificationException（渲染时遍历 children 同时添加新元素）
	 * 和递归渲染（新添加的子元素触发额外渲染）。迁移到 tick 事件中创建，
	 * 渲染阶段仅更新状态。
	 * <p>
	 * 每 tick（1/20 秒）检查当前屏幕，若有侧面配置窗口则确保按钮已创建。
	 * 屏幕关闭时 tick 不触发，按钮随 WeakHashMap 自动回收。
	 */
	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Pre event) {
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		// tick 阶段可能无屏幕打开（如游戏世界内未打开 GUI）
		Screen screen = Minecraft.getInstance().screen;
		if (screen == null) return;
		OverlayTarget target = findTarget(screen);
		ensureButton(target);
		// P0-1 修复：updateButton 从 render 阶段迁移到 tick 阶段
		// 原在 ScreenEvent.Render.Post 中调用，修改 button.target/visible/active/tooltip 违反"渲染阶段只读"原则
		// tick 频率 20Hz 足够保证按钮状态与 TransmissionType 同步（人眼无感知延迟）
		updateButton(target);
	}

	/**
	 * 渲染事件：无操作（保留订阅以便未来扩展，但不修改任何状态）
	 * <br/>
	 * P0-1 修复：原在 Render.Post 中调用 updateButton 修改按钮状态字段（visible/active/tooltip/target），
	 * 违反"渲染阶段不修改状态"原则。已将 updateButton 迁移到 {@link ClientTickEvent.Pre}。
	 * 渲染阶段由 Minecraft 自行根据按钮的 visible/active 字段渲染，本类无需干预。
	 */
	@SubscribeEvent
	public static void render(ScreenEvent.Render.Post event) {
		// 渲染阶段不修改状态，保留订阅以备未来需要只读渲染辅助
	}

	/**
	 * 鼠标点击事件：拦截对 AE2 输入按钮的点击并打开配置窗口
	 * <br/>
	 * 仅处理左键点击，且仅当点击落在按钮范围内时取消事件（避免穿透到窗口下层）。
	 * 全局配置关闭时不响应点击。
	 * <p>
	 * <b>isCanceled 逻辑修复(潜在问题 23)</b>：
	 * <ul>
	 *   <li>仅在按钮实际被点击(triggered=true)且按钮可见(visible=true)时才取消事件</li>
	 *   <li>点击位置不在按钮范围内时 isCanceled=false,允许事件传递给后续按钮(如 MEK Ejector)</li>
	 *   <li>按钮不可见时(如非 ITEM 类型)不取消事件,即使点击坐标在 bounds 内</li>
	 * </ul>
	 * <p>
	 * <b>备选方案</b>(代码层面分析):如 Pre 事件被 MEK 子窗口消费导致不触发,
	 * 可改用 {@link ScreenEvent.MouseButtonPressed.Post} 或直接在
	 * {@link AeInputButton} 的 onPress 回调中发送网络包(当前已实现 onPress 后备路径)。
	 * bounds() 与 ensureButton() 中按钮 x/y 计算公式一致,代码层面无坐标错误。
	 */
	@SubscribeEvent
	public static void mouseClicked(ScreenEvent.MouseButtonPressed.Pre event) {
		if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return;
		if (!Ae2IntegrationLoader.isAe2Loaded()) return;
		OverlayTarget target = findTarget(event.getScreen());
		if (target == null || !shouldRender(target.type())) return;
		if (!isGlobalEnabled(target.tile())) return;
		if (!BUTTONS.containsKey(target.sideConfig())) return;
		ButtonBounds bounds = bounds(target.gui(), target.sideConfig());
		boolean triggered = bounds.contains(event.getMouseX(), event.getMouseY());
		// 按钮可见性检查:仅按钮可见时才取消事件,避免不可见按钮阻止事件传递给 MEK Ejector 等原生按钮
		AeInputButton button = BUTTONS.get(target.sideConfig());
		boolean buttonVisible = button != null && button.visible;
		// isCanceled 逻辑:仅按钮可见且点击命中时才取消事件,允许事件传递给 MEK Ejector
		if (triggered && buttonVisible) {
			openConfigWindow(target);
			event.setCanceled(true);
		}
	}

	/**
	 * 打开 AE2 输入配置窗口
	 * <br/>
	 * 在 MEK GUI 上添加 {@link GuiAeInputConfig} 窗口（而非 setScreen 替换整个 Screen），
	 * 同时发送 {@link OpenAeInputConfigPayload} 请求服务端推送最新过滤器状态。
	 * 服务端 handler 调用 {@code syncFilterToClients} 推送 SyncAeInputFilterEntriesPayload，
	 * 客户端 handler 在窗口已打开时刷新 filter 副本，保证显示数据与服务端一致。
	 */
	private static void openConfigWindow(OverlayTarget target) {
		BlockPos pos = target.tile().getBlockPos();
		// 防止重复打开：若已存在 GuiAeInputConfig 窗口则直接返回
		for (GuiWindow window : target.gui().getWindows()) {
			if (window instanceof GuiAeInputConfig) {
				return;
			}
		}
		// 在 MEK GUI 上添加 Window（而非 setScreen 替换整个 Screen）
		if (target.tile() instanceof IAe2InputHost host) {
			GuiAeInputConfig window = new GuiAeInputConfig(target.gui(), 20, 20, host, null);
			// V14: 从服务端配置读取最小页数，使 aeInputMinPages 配置项生效
			if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeAeInputMinPages != null) {
				window.setMinPages(ModConfig.SERVER.mekCentrifugeAeInputMinPages.get());
			}
			target.gui().addWindow(window);
		}
		// 请求服务端推送最新过滤器状态，确保客户端窗口显示与服务端一致
		PacketDistributor.sendToServer(new OpenAeInputConfigPayload(pos));
	}

	/**
	 * 创建按钮并添加为窗口子元素（仅在非渲染阶段调用）
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：使用 computeIfAbsent 保证每个窗口只创建一次按钮，
	 * 创建后调用 sideConfig.children().add() 注入到 Mekanism GUI 渲染管线。
	 * 此方法不更新按钮状态，状态更新由 {@link #updateButton} 在渲染阶段处理。
	 */
	private static void ensureButton(OverlayTarget target) {
		if (target == null) return;
		BUTTONS.computeIfAbsent(target.sideConfig(), sideConfig -> {
			// Task 12: 按钮文字统一为单字符 "I"（Input），与 AeOutputButton 的 "A" 风格一致
			AeInputButton open = new AeInputButton(
					target.gui(),
					sideConfig.getRelativeX() + BUTTON_X_OFFSET,
					sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
					Component.literal("I"),
					AeInputOverlay::openConfigWindow,
					target);
			sideConfig.children().add(open);
			return open;
		});
	}

	/**
	 * 更新按钮状态（仅在渲染阶段调用，不修改 children 列表）
	 * <br/>
	 * 修复 v14 渲染阶段不修改状态：仅更新 target 引用、visible、active、tooltip，
	 * 保证状态与当前 TransmissionType 同步。按钮不存在时（屏幕刚打开首帧）跳过。
	 */
	private static void updateButton(OverlayTarget target) {
		if (target == null) return;
		AeInputButton button = BUTTONS.get(target.sideConfig());
		// 按钮尚未创建（屏幕刚打开首帧，tick 尚未执行），跳过更新
		if (button == null) return;

		// 每帧更新 target 引用
		button.target = target;

		// 更新可见性 — 仅 ITEM 类型显示
		button.visible = shouldRender(target.type());

		// 更新激活状态和 tooltip
		boolean globalEnabled = isGlobalEnabled(target.tile());
		// active 反映全局配置状态：关闭时灰显（点击事件仍会被 mouseClicked 拦截）
		button.active = globalEnabled;
		if (!globalEnabled) {
			button.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae2_input.global_disabled")));
		} else {
			button.setTooltip(Tooltip.create(Component.translatable("productivebeesgenesis.gui.ae_input_config.open")));
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
	 * 识别屏幕是否为持有一个 {@link IAe2InputHost} 方块实体的 Mekanism GUI
	 * <br/>
	 * 三重判断：GuiMekanism + MekanismTileContainer + tile 实现 IAe2InputHost。
	 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，运行时 instanceof 检查有效。
	 */
	private static ScreenTarget findScreenTarget(Screen screen) {
		if (!(screen instanceof GuiMekanism<?> gui)) return null;
		if (!(gui.getMenu() instanceof MekanismTileContainer<?> container)) return null;
		if (!(container.getTileEntity() instanceof IAe2InputHost)) return null;
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
	 * 判断是否应渲染按钮 — 仅 ITEM 类型（输入拉取只处理物品）
	 */
	private static boolean shouldRender(TransmissionType type) {
		return type == TransmissionType.ITEM;
	}

	/**
	 * 判断全局配置是否启用 AE2 输入拉取
	 * <br/>
	 * 配置项在 AE2 未加载时为 null（条件化注册），需 null 守卫。
	 */
	private static boolean isGlobalEnabled(BlockEntity tile) {
		if (ModConfig.SERVER == null) return false;
		return ModConfig.SERVER.mekCentrifugeAeInputEnabled != null
				&& ModConfig.SERVER.mekCentrifugeAeInputEnabled.get();
	}

	/**
	 * 计算按钮在屏幕上的绝对坐标边界，用于点击检测
	 *
	 * @param gui       Mekanism GUI 实例
	 * @param sideConfig 侧面配置窗口实例
	 */
	private static ButtonBounds bounds(GuiMekanism<?> gui, GuiSideConfiguration<?> sideConfig) {
		return new ButtonBounds(
				gui.getGuiLeft() + sideConfig.getRelativeX() + BUTTON_X_OFFSET,
				gui.getGuiTop() + sideConfig.getRelativeY() + BUTTON_Y_OFFSET,
				BUTTON_SIZE);
	}

	/**
	 * 屏幕目标 — 持有 GUI 实例和方块实体引用
	 */
	private record ScreenTarget(GuiMekanism<?> gui, BlockEntity tile) {
	}

	/**
	 * 覆盖层目标 — 持有注入按钮所需的全部上下文
	 * <br/>
	 * 公开 record，供 {@link AeInputButton} 引用。
	 *
	 * @param gui       Mekanism GUI 实例
	 * @param sideConfig 侧面配置窗口实例（按钮注入目标）
	 * @param tile      方块实体（实现 IAe2InputHost）
	 * @param type      当前激活的传输类型（仅 ITEM 时显示按钮）
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
