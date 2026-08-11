package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElementHolder;
import mekanism.client.gui.element.button.GuiPinButton;
import mekanism.client.gui.element.gauge.GaugeType;
import mekanism.client.gui.element.gauge.GuiFluidGauge;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.SelectedWindowData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
	 * 多流体槽状态窗口(SRP:仅负责渲染,数据查询通过 {@link IMultiFluidTankHost} 抽象)
	 * <br/>
	 * 继承 MEK {@link GuiWindow},单行横排布局(1 行 N 列),无翻页。
	 * <p>
	 * <b>单行横排设计 + tankCount 动态更新</b>
	 * <ul>
	 *   <li>所有槽位在同一行横排显示,窗口宽度随 tankCount 线性增长</li>
	 *   <li>tankCount 不快照,每 tick 动态读取 {@code host.getFluidTankCount()}(修复 v14:渲染阶段不修改状态)</li>
	 *   <li>tankCount 变化时自动重建 Gauge(支持服务端运行时分配新槽位)</li>
	 *   <li>窗口高度固定,布局简洁</li>
	 * </ul>
	 * <p>
	 * <b>使用的 MEK 现成元素:</b>
	 * <ul>
	 *   <li>{@link GuiPinButton} — 固定按钮</li>
	 *   <li>{@link GuiElementHolder} — 灰色背景</li>
	 *   <li>{@link GuiFluidGauge} — 流体槽 gauge(GaugeType.SMALL)</li>
	 * </ul>
	 *
	 * @since Task 8
	 */
public class GuiMultiFluidTanksWindow extends GuiWindow {

	/** PinButton X 偏移(标题栏左侧) */
	private static final int PIN_X_OFFSET = 16;
	/** PinButton Y 偏移(标题栏左侧) */
	private static final int PIN_Y_OFFSET = 6;

	/** 数据源 — 提供 tank 查询能力 */
	private final IMultiFluidTankHost host;
	/** 构造时的列数(用于网格背景尺寸) */
	private final int cols;
	/** 构造时的可见行数(固定 1,单行横排) */
	private final int visibleRows;
	/** 上次 tick 时的 tankCount(用于检测变化并重建 Gauge,修复 v14:渲染阶段不修改状态) */
	private int lastTankCount = -1;
	/** 已添加的 Gauge 元素列表(供 tankCount 变化时移除重建) */
	private final List<GuiFluidGauge> gaugeElements = new ArrayList<>();

	/**
	 * 构造多流体槽窗口
	 *
	 * @param gui        所属 GUI 包装器
	 * @param x          窗口 X 坐标(由 Tab 居中计算)
	 * @param y          窗口 Y 坐标(固定 15)
	 * @param host       数据源(提供 tank 查询)
	 * @param windowData 窗口数据(含持久化信息)
	 */
	public GuiMultiFluidTanksWindow(IGuiWrapper gui, int x, int y, IMultiFluidTankHost host, SelectedWindowData windowData) {
		super(gui, x, y,
				GuiMultiFluidTanksLayoutHelper.calculateWindowWidth(host.getFluidTankCount() - 1),
				GuiMultiFluidTanksLayoutHelper.calculateWindowHeight(host.getFluidTankCount() - 1),
				windowData);
		this.host = host;
		// Tab 只显示 idx=1 到 maxTanks-1 的槽位(跳过主槽 idx=0),所以 tankCount - 1
		int initialTankCount = host.getFluidTankCount() - 1;
		this.lastTankCount = initialTankCount;
		this.cols = GuiMultiFluidTanksLayoutHelper.calculateCols(initialTankCount);
		this.visibleRows = 1; // 单行横排
		// 参考 GuiAeInputConfig:允许所有交互(拖动、点击子元素等)
		this.interactionStrategy = InteractionStrategy.ALL;

		// 固定按钮(UNSPECIFIED 类型需手动添加,GuiWindow 父类不会自动添加)
		addChild(new GuiPinButton(gui(), relativeX + PIN_X_OFFSET, relativeY + PIN_Y_OFFSET, this));

		// 网格背景(GuiElementHolder 灰色背景,单行高度)
		int gridWidth = cols * GuiMultiFluidTanksLayoutHelper.SLOT_PITCH - GuiMultiFluidTanksLayoutHelper.GAUGE_GAP;
		int gridHeight = GuiMultiFluidTanksLayoutHelper.GAUGE_H;
		addChild(new GuiElementHolder(gui(),
				relativeX + GuiMultiFluidTanksLayoutHelper.LEFT_PADDING,
				relativeY + GuiMultiFluidTanksLayoutHelper.GRID_Y,
				gridWidth, gridHeight));

		// 动态构建 Gauge(单行横排)
		buildGauges();
	}

	/**
	 * 动态构建流体槽 Gauge(单行横排渲染)
	 * <br/>
	 * 遍历所有槽位(idx=1 ~ tankCount),通过 LayoutHelper 计算每个 Gauge 的 X/Y 坐标。
	 * 单行布局:所有槽位从左到右排列,Y 坐标固定为 GRID_Y。
	 * <p>
	 * <b>tankCount 动态读取</b>:每次调用时从 {@code host.getFluidTankCount()} 获取最新值,
	 * 支持服务端运行时分配新槽位后客户端自动更新显示。
	 */
	private void buildGauges() {
		// Tab 只显示 idx=1 到 maxTanks-1 的槽位(跳过主槽 idx=0),所以 tankCount - 1
		int currentTankCount = host.getFluidTankCount() - 1;
		if (currentTankCount <= 0) return;
		int pageCols = GuiMultiFluidTanksLayoutHelper.calculateCols(currentTankCount);
		for (int pageIdx = 0; pageIdx < currentTankCount; pageIdx++) {
			int globalIdx = pageIdx + 1; // +1 跳过主槽 idx=0
			int gaugeX = GuiMultiFluidTanksLayoutHelper.calculateGaugeX(pageIdx, pageCols, currentTankCount);
			int gaugeY = GuiMultiFluidTanksLayoutHelper.calculateGaugeY(pageIdx, pageCols);
			final int tankIndex = globalIdx;
			GuiFluidGauge gauge = addChild(new GuiFluidGauge(
					() -> host.getFluidTank(tankIndex),
					() -> host.getFluidTanks(),
					GaugeType.SMALL,
					gui(),
					relativeX + gaugeX,
					relativeY + gaugeY));
			gaugeElements.add(gauge);
		}
	}

	/**
	 * tankCount 变化时移除旧 Gauge 元素并重建
	 */
	private void rebuildGauges() {
		for (GuiFluidGauge gauge : gaugeElements) {
			children().remove(gauge);
		}
		gaugeElements.clear();
		buildGauges();
	}

	/** 标题左侧内边距 — 为关闭按钮和固定按钮留出空间 */
	@Override
	protected int getTitlePadStart() {
		return 14 + GuiPinButton.WIDTH;
	}

	/**
	 * 渲染前景 — 标题
	 */
	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		super.renderForeground(guiGraphics, mouseX, mouseY);
		// 标题(居中渲染)
		drawTitleText(guiGraphics,
				Component.translatable("gui.productivebeesgenesis.multi_fluid_tanks_window.title"),
				5);
	}

	/**
	 * 修复 v14 渲染阶段不修改状态: tankCount 变化检测与 Gauge 重建迁移至 tick
	 * <br/>
	 * 原实现放在 drawBackground 中,会在渲染阶段修改 lastTankCount 并 rebuildGauges
	 * (增删 children),存在递归渲染与 ConcurrentModificationException 风险。
	 * tick 由容器每 tick 调用,与渲染阶段解耦,重建 Gauge 安全。
	 * <p>
	 * <b>原理</b>:tankCount 不快照(构造时不固定),支持服务端运行时动态分配新槽位后
	 * 客户端窗口自动更新显示(潜在问题 17 修复)。先 super.tick() 传播到子元素,
	 * 再检测变化并重建,避免遍历 children 时并发修改。
	 */
	@Override
	public void tick() {
		super.tick();
		// 检测 tankCount 变化,动态重建 Gauge(不快照 tankCount)
		// Tab 只显示 idx=1 到 maxTanks-1 的槽位(跳过主槽 idx=0),所以 tankCount - 1
		int currentTankCount = host.getFluidTankCount() - 1;
		if (currentTankCount != lastTankCount) {
			lastTankCount = currentTankCount;
			rebuildGauges();
		}
	}

	/**
	 * 渲染背景 — 仅渲染,不修改状态(修复 v14 渲染阶段不修改状态)
	 */
	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTick);
		guiGraphics.setColor(1, 1, 1, 1);
	}
}
