package com.ayoshiko.productivebeesgenesis.compat.emextras.client.gui;

import com.ayoshiko.productivebeesgenesis.apiary.client.GuiPbUpgradeTab;
import com.ayoshiko.productivebeesgenesis.client.screen.CompactStackCountScreen;
import com.ayoshiko.productivebeesgenesis.client.screen.CentrifugeInputReturnButton;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiMekCentrifugeFactoryHelper;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiMultiFluidTanksTab;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiMultiFluidTanksWindow;
import com.ayoshiko.productivebeesgenesis.client.screen.MultiFluidTabSyncWatcher;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEFactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;
import io.github.masyumero.emextras.client.gui.element.tab.EMExtraGuiSortingTab;
import mekanism.api.recipes.cache.CachedRecipe.OperationTracker.RecipeError;
import mekanism.client.gui.GuiConfigurableTile;
import mekanism.client.gui.element.progress.GuiProgress;
import mekanism.client.gui.element.tab.GuiEnergyTab;
import mekanism.client.gui.element.tab.GuiRedstoneControlTab;
import mekanism.client.gui.element.tab.GuiWarningTab;
import mekanism.client.gui.element.window.GuiWindow;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.inventory.warning.IWarningTracker;
import mekanism.common.inventory.warning.WarningTracker.WarningType;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
	 * EME扩展版离心机工厂Screen（隔离类）
	 * <br/>
	 * 继承Mekanism的GuiConfigurableTile，使用dynamicSlots=true自动渲染槽位背景。
	 * 每进程：1红色输入槽 + 3蓝色输出槽（主/副1/副2）+ 共享流体槽。
	 * <p>
	 * <b>类加载安全</b>：本类直接引用 {@link EMExtraGuiSortingTab}（EME 的 GUI 类）和
	 * {@link TileEntityEMExtraMekCentrifugeFactory}（compat 隔离类），
	 * 仅在 EME 加载时由 {@link com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient}
	 * 通过 {@code MekCompatHooks.isEvolvedMekanismExtrasLoaded()} 守卫注册。
	 * 未安装 EME 时本类不会被加载，避免 {@link NoClassDefFoundError}。
	 * <p>
	 * <b>隔离迁移（v2.0.9）</b>：从 {@code client/screen} 包移至 {@code compat/emextras/client/gui} 包，
	 * 与其他 EME 专属类保持隔离。布局参数通过 {@link EMEFactoryLayoutHelper} 调用，
	 * 不再依赖基础类 {@link com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper}。
	 * <p>
	 * 布局参数通过 {@link EMEFactoryLayoutHelper} 动态计算，
	 * 支持EME 4等级（ABSOLUTE_OVERCLOCKED/SUPREME_QUANTUM/COSMIC_DENSE/INFINITE_MULTIVERSAL）。
	 * <p>
	 * 与原版/ME工厂GUI的差异：
	 * - 使用EME的EMExtraGuiSortingTab（而非原版GuiSortingTab或ME的ExtraGuiSortingTab）
	 * - 3行输出槽需要额外高度（+40），inventoryLabelY=125
	 * - EME tier直接存储imageWidth和inventoryLabelX，无需公式推导
	 * - 流体输出槽在左侧固定位置
	 * - 进度条使用SMELTING + PB离心配方的双配方跳转
	 */
public class GuiEMExtraMekCentrifugeFactory
		extends GuiConfigurableTile<TileEntityEMExtraMekCentrifugeFactory,
				MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>>
		implements CompactStackCountScreen {

	/** PB升级TAB */
	private GuiPbUpgradeTab<TileEntityEMExtraMekCentrifugeFactory> pbUpgradeTab;
	/** 多流体槽 Tab — 仅当 tile 实现 IMultiFluidTankHost 且槽数 > 1 时创建 */
	private GuiMultiFluidTanksTab<IMultiFluidTankHost> multiFluidTanksTab;
	/** 多流体槽 Tab 同步监听器 — containerTick 中检测同步值变化动态添加/移除 Tab */
	private MultiFluidTabSyncWatcher multiFluidTabWatcher;

	public GuiEMExtraMekCentrifugeFactory(MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory> container,
		Inventory inv, Component title) {
		super(container, inv, title);
		// 3行输出槽需要额外高度：标准187 + 副输出1(20) + 副输出2(20) = 227
		imageHeight = 187 + 40;
		inventoryLabelY = 125;

		// EME tier直接存储imageWidth增量值
		imageWidth += EMEFactoryLayoutHelper.getImageWidthAddition(tile.tier);

		// EME tier直接存储inventoryLabelX值
		inventoryLabelX = EMEFactoryLayoutHelper.getInventoryLabelX(tile.tier);
		titleLabelY = 4;
		dynamicSlots = true;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();
		// 超频等级 SortingTab 高度差异处理:EMExtraGuiSortingTab 默认 y=62/h=35(左侧),与 MEK/ME 标准 SortingTab 一致
		// y 坐标链审计修复后:左 62/101/131/161,右 98/131,所有间距≥4px,无重叠
		addRenderableWidget(new EMExtraGuiSortingTab(this, tile));
		// PB升级TAB
		pbUpgradeTab = addRenderableWidget(new GuiPbUpgradeTab<>(this, tile, () -> pbUpgradeTab));
		// 标准能量条（右侧布局）+ 能量标签
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createStandardPowerBar(this, tile.getEnergyContainer(), imageWidth))
				.warning(WarningType.NOT_ENOUGH_ENERGY, tile.getWarningCheck(RecipeError.NOT_ENOUGH_ENERGY, 0));
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createEnergyTab(this, tile.getEnergyContainer(),
			tile::getLastUsage));
		// Tab 布局重排:GuiEnergyTab 下移 Δ=+24(y=137→161),与 GuiMultiFluidTanksTab(y=131,底部157)间距4px
		// y 坐标链审计修复:统一所有等级 y 坐标链为 62/101/131/161,确保间距≥4px
		for (GuiEventListener child : children()) {
			if (child instanceof GuiEnergyTab energyTab) {
				energyTab.move(0, 24);
				break;
			}
		}

		// 进度条循环（输入槽与主输出槽之间，双配方跳转）
		int baseX = EMEFactoryLayoutHelper.getBaseX(tile.tier);
		int baseXMult = EMEFactoryLayoutHelper.getBaseXMult(tile.tier);
		for (GuiProgress bar : GuiMekCentrifugeFactoryHelper.createProgressBars(
				this, tile, tile.tier.processes,
				i -> tile.getScaledProgress(1, i),
				i -> tile.getWarningCheck(RecipeError.INPUT_DOESNT_PRODUCE_OUTPUT, i),
				baseX, baseXMult)) {
			addRenderableWidget(bar);
		}

		// 共享流体输出槽 — 位置通过EMEFactoryLayoutHelper动态计算，避免与输出槽重叠
		addRenderableWidget(GuiMekCentrifugeFactoryHelper.createFluidGauge(
				this,
				tile::getFluidOutputTank,
				() -> tile.getFluidTanks(null),
				EMEFactoryLayoutHelper.getFluidTankX(tile.tier),
				EMEFactoryLayoutHelper.getFluidTankY(tile.tier)));
		CentrifugeInputReturnButton inputReturnButton = CentrifugeInputReturnButton.createForFactory(
				this, imageWidth, tile.getBlockPos());
		if (inputReturnButton != null) addRenderableWidget(inputReturnButton);
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
		// 警告 Tab 上移 Δ=-8(y=109→101),位于 EMExtraGuiSortingTab(y=62,h=35,底部97)下方(间距4px)
		// y 坐标链审计修复:原 move(-11)→y=98 与 EMExtraGuiSortingTab 间距仅1px,改为 move(-8)→y=101 间距4px
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
