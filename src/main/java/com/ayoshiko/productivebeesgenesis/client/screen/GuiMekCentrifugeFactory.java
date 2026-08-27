package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeTab;
import com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.bar.GuiVerticalPowerBar;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.tab.GuiRedstoneControlTab;
import mekanism.client.gui.element.tab.GuiSortingTab;
import mekanism.client.gui.element.tab.GuiWarningTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.slot.ContainerSlotType;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.IWarningTracker;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import mekanism.common.tile.factory.TileEntityFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
	 * 工厂版MEK离心机Screen
	 * <br/>
	 * 继承Mekanism的GuiConfigurableTile，使用dynamicSlots=true自动渲染槽位背景。
	 * 每进程：1红色输入槽 + 3蓝色输出槽（主/副1/副2）+ 共享流体槽。
	 * <p>
	 * 布局参数通过 {@link FactoryLayoutHelper} 动态计算，统一支持原版4等级
	 * （BASIC/ADVANCED/ELITE/ULTIMATE）与EvolvedMekanism扩展高等级
	 * （OVERCLOCKED/QUANTUM/DENSE/MULTIVERSAL/CREATIVE）。
	 * <p>
	 * 布局参考：
	 * - 原版4等级：mekmm GuiMoreMachineFactory（baseX/baseXMult）+ Mekanism GuiFactory（ULTIMATE imageWidth+=34）
	 * - EM高等级：EvolvedMekanism GuiFactoryMixin（imageWidthAddition公式、baseX=9、inventoryLabelX动态居中）
	 * - 输入槽 y=13, 主输出 y=57, 副输出1 y=77, 副输出2 y=97
	 * - 进度条DOWN类型在输入与主输出之间
	 * - 原版4等级：垂直能量条在右侧，流体槽在左侧副输出2行
	 * - EM高等级：能量条在左侧（GuiEnergyTab下边框与物品栏最下面一排对齐，GuiVerticalPowerBar上边框与物品栏最上面一排对齐），
	 *   流体槽在右侧物品栏右边（间距与红石能量槽一致）
	 * <p>
	 * dynamicSlots=true自动根据侧面配置渲染红/蓝边框，无需手动添加GuiSlot。
	 */
public class GuiMekCentrifugeFactory
		extends GuiConfigurableTile<TileEntityFactory<?>, MekanismTileContainer<TileEntityFactory<?>>>
		implements CompactStackCountScreen {

	/** PB升级TAB — 仅当 tile 为 AbstractMekCentrifugeFactory 时创建 */
	private GuiPbUpgradeTab<AbstractMekCentrifugeFactory> pbUpgradeTab;
	/** 多流体槽 Tab — 仅当 tile 实现 IMultiFluidTankHost 且槽数 > 1 时创建 */
	private GuiMultiFluidTanksTab<IMultiFluidTankHost> multiFluidTanksTab;
	/** 多流体槽 Tab 同步监听器 — containerTick 中检测同步值变化动态添加/移除 Tab */
	private MultiFluidTabSyncWatcher multiFluidTabWatcher;

	@SuppressWarnings("unchecked")
	public GuiMekCentrifugeFactory(MekanismTileContainer<TileEntityFactory<?>> container, Inventory inv, Component title) {
		super(container, inv, title);
		// 3行输出槽需要额外高度：标准187 + 副输出1(20) + 副输出2(20) = 227
		imageHeight = 187 + 40;
		inventoryLabelY = 125;

		// 使用FactoryLayoutHelper动态计算imageWidth增量（原版ULTIMATE=34，EM高等级按公式计算）
		imageWidth += FactoryLayoutHelper.getImageWidthAddition(tile.tier);

		// EM高等级不使用inventoryLabelY=75（EM原版是1输出行机器，我们是3输出行）
		// 保持inventoryLabelY=125，避免标签与能量条/流体槽重叠

		// 使用FactoryLayoutHelper动态计算inventoryLabelX
		int labelX = FactoryLayoutHelper.getInventoryLabelX(tile.tier);
		if (labelX == -1) {
			// EM高等级动态居中：imageWidth/2 - font.width(playerInventoryTitle)/2
			inventoryLabelX = imageWidth / 2 - Minecraft.getInstance().font.width(playerInventoryTitle) / 2;
		} else {
			inventoryLabelX = labelX;
		}
		titleLabelY = 4;
		dynamicSlots = true;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();
		addRenderableWidget(new GuiSortingTab(this, tile));
		// PB升级TAB — 仅原版工厂离心机（AbstractMekCentrifugeFactory 子类）支持
		if (tile instanceof AbstractMekCentrifugeFactory factory) {
			pbUpgradeTab = addRenderableWidget(new GuiPbUpgradeTab<>(this, factory, () -> pbUpgradeTab));
		}

		// 能量条与能量标签页布局：
		// - 原版4等级：能量条在右侧（标准布局），高度73
		// - EM高等级：一比一复刻EvolvedMekanism GuiFactoryMixin实现：
		//   从Container slot动态获取energySlotX（ContainerSlotType.POWER类型的slot.x）
		//   power bar x = energySlotX + 5
		//   power bar y = inventoryLabelY + 9
		//   power bar height = 52
		//   energy tab 使用默认构造（Y=137）
		if (FactoryLayoutHelper.isEMHighTier(tile.tier)) {
			// 从Container slot动态获取energySlotX — 与EM原版GuiFactoryMixin完全一致
			int energySlotX = menu.getInventoryContainerSlots().stream()
					.filter(slot -> slot.getSlotType() == ContainerSlotType.POWER)
					.findFirst()
					.map(slot -> slot.x)
					.orElse(FactoryLayoutHelper.getEnergySlotX(tile.tier));
			addRenderableWidget(new GuiVerticalPowerBar(this, tile.getEnergyContainer(), energySlotX + 5,
				this.inventoryLabelY + 9,
				52))
					.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		} else {
			// 原版4等级：标准能量条（右侧布局）
			addRenderableWidget(GuiMekCentrifugeFactoryHelper.createStandardPowerBar(this, tile.getEnergyContainer(),
				imageWidth))
					.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		}
		// GuiEnergyTab使用默认构造（左侧 x=-26, Y=137），与EM原版一致
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createEnergyTab(this, tile.getEnergyContainer(),
			tile::getLastUsage));
		// Tab 布局重排:所有等级 GuiEnergyTab 下移 Δ=+24(y=137→161),与 GuiMultiFluidTanksTab(y=131,底部157)间距4px
		// 修复 EM 高等级 GuiEnergyTab 不移动导致与 GuiMultiFluidTanksTab 重叠的问题(潜在问题1)
		for (GuiEventListener child : children()) {
			if (child instanceof GuiEnergyTab energyTab) {
				energyTab.move(0, 24);
				break;
			}
		}

		// 进度条循环（输入槽与主输出槽之间，双配方跳转）
		// 物品输出槽由dynamicSlots自动渲染蓝色边框，无需手动添加GuiSlot
		int baseX = FactoryLayoutHelper.getBaseX(tile.tier);
		int baseXMult = FactoryLayoutHelper.getBaseXMult(tile.tier);
		for (GuiProgress bar : GuiMekCentrifugeFactoryHelper.createProgressBars(
				this, tile, tile.tier.processes,
				i -> tile.getScaledProgress(1, i),
				i -> tile.getWarningCheck(RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT, i),
				baseX, baseXMult)) {
			addRenderableWidget(bar);
		}

		// 共享流体输出槽 — 位置通过FactoryLayoutHelper动态计算，避免与输出槽重叠
		if (tile instanceof TileEntityMekCentrifugeFactory centrifugeFactory) {
			addRenderableWidget(GuiMekCentrifugeFactoryHelper.createFluidGauge(
					this,
					centrifugeFactory::getFluidOutputTank,
					() -> tile.getFluidTanks(null),
					FactoryLayoutHelper.getFluidTankX(tile.tier),
					FactoryLayoutHelper.getFluidTankY(tile.tier)));
		}
		// AE2 输出按钮已移至 MEK 侧面配置 Tab，由 AeOutputOverlay 动态注入
		// Tab 显示条件基于 isMultiFluidModeSynced 同步值(选项 A 决策,放弃旧存档隐藏约束):
		// GUI 构造期 tile.getLevel() 可能为 null,isMultiFluidMode() 会走 holder 类型判断导致 Tab 不显示
		if (tile instanceof IMultiFluidTankHost host && host.isMultiFluidModeSynced()) {
			multiFluidTanksTab = addRenderableWidget(new GuiMultiFluidTanksTab<>(this, host, () -> multiFluidTanksTab));
		}
		// 初始化同步监听器 — 记录 GUI 构造期同步状态作为基准,watcher 在 containerTick 中检测变化动态添加/移除 Tab
		// 解决首次打开 GUI 时同步数据未到达导致 Tab 不显示的竞态问题
		if (tile instanceof IMultiFluidTankHost host) {
			multiFluidTabWatcher = new MultiFluidTabSyncWatcher();
			multiFluidTabWatcher.init(host.isMultiFluidModeSynced());
		}
	}

	/**
	 * containerTick 重写 — 第一行触发 SyncableBoolean 同步,然后由 watcher 检测同步值变化动态添加/移除 Tab
	 * <br/>
	 * 解决首次打开 GUI 时同步数据未到达(addGuiElements 期)导致 Tab 不显示的竞态:
	 * 同步数据到达后下一帧 containerTick 检测到 false→true 变化,watcher 调用 addMultiFluidTab 动态添加。
	 */
	@Override
	public void containerTick() {
		super.containerTick();
		if (multiFluidTabWatcher != null && tile instanceof IMultiFluidTankHost host) {
			multiFluidTabWatcher.tick(host, this::addMultiFluidTab, this::removeMultiFluidTabAndWindow);
		}
	}

	/**
	 * 动态添加多流体槽 Tab(防御性重复添加检查)
	 * <br/>
	 * 由 watcher 在检测到 SINGLE→MULTI 变化时调用。addGuiElements 期若已添加则 multiFluidTanksTab 非 null,跳过。
	 */
	private void addMultiFluidTab() {
		if (multiFluidTanksTab == null && tile instanceof IMultiFluidTankHost host) {
			multiFluidTanksTab = addRenderableWidget(new GuiMultiFluidTanksTab<>(this, host, () -> multiFluidTanksTab));
		}
	}

	/**
	 * 动态移除多流体槽 Tab 与已打开的窗口
	 * <br/>
	 * 严格时序(避免 closeListener NPE 与 LRU 并发修改):
	 * <ol>
	 *   <li>复制 windows 列表(底层 LRU 无 fail-fast)</li>
	 *   <li>遍历副本找 GuiMultiFluidTanksWindow(找不到则跳过 close)</li>
	 *   <li>window.close() — 触发 closeListener,通过 elementSupplier.get() 访问 tab 字段(此时仍非 null)</li>
	 *   <li>removeWidget(tab) — 从 children 列表移除 tab,不触发 closeListener,不影响 LRU</li>
	 *   <li>field=null — 最后置 null,避免 closeListener NPE</li>
	 * </ol>
	 */
	private void removeMultiFluidTabAndWindow() {
		if (multiFluidTanksTab == null) return;
		// (1) 复制 windows 列表(底层 LRU 无 fail-fast,遍历副本安全)
		List<GuiWindow> windowsCopy = new ArrayList<>(getWindows());
		// (2) 遍历副本查找多流体槽窗口
		GuiMultiFluidTanksWindow targetWindow = null;
		for (GuiWindow window : windowsCopy) {
			if (window instanceof GuiMultiFluidTanksWindow multiFluidWindow) {
				targetWindow = multiFluidWindow;
				break;
			}
		}
		// (3) 关闭窗口(此时 multiFluidTanksTab 仍非 null,closeListener 可安全访问)
		if (targetWindow != null) {
			targetWindow.close();
		}
		// (4) 从 children 移除 tab(不触发 closeListener,不影响 LRU)
		removeWidget(multiFluidTanksTab);
		// (5) 最后置 null,避免 closeListener NPE
		multiFluidTanksTab = null;
	}

	/**
	 * Tab 布局重排 — 通过 move() 平移 MEK 原生 Tab 实现左右对称
	 * <br/>
	 * 不修改 MEK 源码保持升级兼容性,参考 GuiMekApiary 三种 Tab 移动模式:
	 * 模式1(本方法)、模式2(addGenericTabs)、模式3(addGuiElements 中 GuiEnergyTab)。
	 */
	@Override
	protected void addWarningTab(IWarningTracker warningTracker) {
		// 警告 Tab 上移 Δ=-8(y=109→101),位于 GuiSortingTab(y=62,h=35,底部97)下方(间距4px)
		// y 坐标链审计修复:原 move(-11)→y=98 与 GuiSortingTab 间距仅1px,改为 move(-8)→y=101 间距4px
		GuiWarningTab tab = addRenderableWidget(new GuiWarningTab(this, warningTracker, true));
		tab.move(0, -8);
	}

	@Override
	protected void addGenericTabs() {
		super.addGenericTabs();
		// 红石 Tab 上移 Δ=-6(y=137→131),与左侧 GuiMultiFluidTanksTab(y=131)对称
		// y 坐标链审计修复:原 move(-11)→y=126 与 GuiPbUpgradeTab(y=98,底部124)间距仅2px,改为 move(-6)→y=131 间距7px
		for (GuiEventListener child : children()) {
			if (child instanceof GuiRedstoneControlTab redstoneTab) {
				redstoneTab.move(0, -6);
				break;
			}
		}
	}

	@Override
	protected void drawForegroundText(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		renderTitleText(guiGraphics);
		renderInventoryText(guiGraphics);
		super.drawForegroundText(guiGraphics, mouseX, mouseY);
	}
}
