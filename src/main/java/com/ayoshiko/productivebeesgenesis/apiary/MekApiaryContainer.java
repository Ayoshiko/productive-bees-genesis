package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.List;

import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * MEK 通用机械蜂箱 Container
 * <br/>
 * 继承 MekanismTileContainer，槽位由基类自动从方块实体提取（dynamicSlots=true 由 Screen 设置）。
 * <p>
 * 基类自动处理：
 * <ul>
 *   <li>升级槽、能量槽、蜂笼 I/O 槽、输出槽的 ContainerSlot 创建</li>
 *   <li>侧面配置同步、红石控制同步</li>
 *   <li>快速移动（shift-click）逻辑</li>
 * </ul>
 * <p>
 * 本类：
 * <ul>
 *   <li>重写 addSlots() 添加喂食器虚拟槽位（VirtualInventoryContainerSlot）</li>
 *   <li>重写玩家物品栏偏移，使物品栏定位在输出区下方</li>
 * </ul>
 */
public class MekApiaryContainer extends MekanismTileContainer<TileEntityMekApiary> implements IFeederSlotContainer, IPbUpgradeSlotContainer {

	/** 初始版蜜蜂列数 */
	private static final int BEE_COLS = 3;

	/** 初始版蜜蜂行数 */
	private static final int BEE_ROWS = 1;

	/** 初始版输出列数 */
	private static final int OUTPUT_COLS = 3;

	/** 喂食器虚拟槽位列表（Popup Window 交互用） */
	@Nullable
	private List<VirtualInventoryContainerSlot> feederSlots;

	/** PB升级输入虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeInputSlot;

	/** PB升级输出虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeOutputSlot;

	public MekApiaryContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, @NotNull TileEntityMekApiary tile) {
		super(type, id, inv, tile);
	}

	/**
	 * 添加槽位 — 重写以添加喂食器虚拟槽位
	 * <br/>
	 * 调用 super.addSlots() 后，遍历 tile 的 FeederInventorySlot 列表，
	 * 为每个槽位创建 VirtualInventoryContainerSlot 并添加到容器。
	 * 虚拟槽位坐标为 (0,0)（由 GuiVirtualSlot 动态更新实际渲染位置），
	 * 仅在对应 SelectedWindowData（UNSPECIFIED）窗口打开时激活。
	 */
	@Override
	protected void addSlots() {
		super.addSlots();
		// 添加喂食器虚拟槽位
		List<FeederInventorySlot> feederInventorySlots = tile.getFeederInventorySlots();
		feederSlots = new ArrayList<>(feederInventorySlots.size());
		for (FeederInventorySlot feederSlot : feederInventorySlots) {
			VirtualInventoryContainerSlot virtualSlot = feederSlot.createContainerSlot();
			addSlot(virtualSlot);
			feederSlots.add(virtualSlot);
		}
		// 添加PB升级输入/输出虚拟槽位
		pbUpgradeInputSlot = tile.getPbUpgradeInputSlot().createContainerSlot();
		addSlot(pbUpgradeInputSlot);
		pbUpgradeOutputSlot = tile.getPbUpgradeOutputSlot().createContainerSlot();
		addSlot(pbUpgradeOutputSlot);
	}

	/** 获取喂食器虚拟槽位列表（供 GuiFeederWindow 绑定 GuiVirtualSlot） */
	@Nullable
	public List<VirtualInventoryContainerSlot> getFeederSlots() {
		return feederSlots;
	}

	/** 获取指定索引的喂食器虚拟槽位 */
	@Nullable
	public VirtualInventoryContainerSlot getFeederSlot(int index) {
		return feederSlots != null && index >= 0 && index < feederSlots.size() ? feederSlots.get(index) : null;
	}

	@Nullable
	@Override
	public VirtualInventoryContainerSlot getPbUpgradeInputSlot() {
		return pbUpgradeInputSlot;
	}

	@Nullable
	@Override
	public VirtualInventoryContainerSlot getPbUpgradeOutputSlot() {
		return pbUpgradeOutputSlot;
	}

	/**
	 * 玩家物品栏 Y 偏移 — 定位在输出区下方
	 * <br/>
	 * 计算链：beeBottom → outputY → outputBottom → inventoryY
	 */
	@Override
	protected int getInventoryYOffset() {
		int beeBottom = ApiaryGuiLayoutHelper.getBeeBottom(BEE_ROWS);
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(beeBottom, BEE_ROWS) + ApiaryGuiLayoutHelper.getOutputH();
		return ApiaryGuiLayoutHelper.getInventoryY(outputBottom);
	}

	/**
	 * 玩家物品栏 X 偏移 — 居中 9 格物品栏
	 * <br/>
	 * 公式：imageWidth/2 - 80
	 */
	@Override
	protected int getInventoryXOffset() {
		int imageWidth = ApiaryGuiLayoutHelper.getImageWidth(BEE_COLS, OUTPUT_COLS);
		return ApiaryGuiLayoutHelper.getInventoryX(imageWidth);
	}
}
