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
 * 工厂版通用机械蜂箱 Container
 * <br/>
 * 继承 MekanismTileContainer，根据工厂等级动态计算玩家物品栏偏移。
 * 槽位由基类自动从方块实体提取（dynamicSlots=true 由 Screen 设置）。
 * <p>
 * 与初始版 {@link MekApiaryContainer} 的差异：
 * <ul>
 *   <li>蜜蜂列数固定 5（工厂版 FACTORY_BEE_COLS）</li>
 *   <li>蜜蜂行数随等级递增（Basic=1/Advanced=2/Elite=3/Ultimate=4）</li>
 *   <li>输出列数随等级递增（Basic=3/Advanced=4/Elite=5/Ultimate=6）</li>
 *   <li>imageWidth 动态：Ultimate=210px，其他=176px</li>
 *   <li>喂食槽数量随等级递增（Basic=9, Advanced=12, Elite=15, Ultimate=21），由 FeederSlotManager 动态创建</li>
 * </ul>
 */
public class MekApiaryFactoryContainer extends MekanismTileContainer<TileEntityMekApiaryFactory> implements IFeederSlotContainer, IPbUpgradeSlotContainer {

	/** 喂食器虚拟槽位列表（Popup Window 交互用） */
	@Nullable
	private List<VirtualInventoryContainerSlot> feederSlots;

	/** PB升级输入虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeInputSlot;

	/** PB升级输出虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeOutputSlot;

	public MekApiaryFactoryContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv,
			@NotNull TileEntityMekApiaryFactory tile) {
		super(type, id, inv, tile);
	}

	/**
	 * 添加槽位 — 重写以添加喂食器虚拟槽位
	 * <br/>
	 * 与 MekApiaryContainer 相同：调用 super.addSlots() 后添加喂食器虚拟槽位。
	 * 工厂版喂食槽数量由 FeederSlotManager（通过 createFeederSlotManager 重写）动态决定。
	 */
	@Override
	protected void addSlots() {
		super.addSlots();
		List<FeederInventorySlot> feederInventorySlots = tile.getFeederInventorySlots();
		feederSlots = new ArrayList<>(feederInventorySlots.size());
		for (FeederInventorySlot feederSlot : feederInventorySlots) {
			VirtualInventoryContainerSlot virtualSlot = feederSlot.createContainerSlot();
			addSlot(virtualSlot);
			feederSlots.add(virtualSlot);
		}
		// 添加PB升级输入/输出虚拟槽位（工厂版继承自TileEntityMekApiary，直接访问getter）
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
	 * 玩家物品栏 Y 偏移 — 根据工厂等级动态计算
	 * <br/>
	 * 计算链：beeBottom → outputY → outputBottom → inventoryY。
	 * 通过 tile.getBeeRows() 获取行数，避免直接依赖 tile.getTier()（ME/EME 版本返回 null
	 * 导致 forTier(null) 回退 Basic 配置，布局偏移错误）。
	 */
	@Override
	protected int getInventoryYOffset() {
		int beeRows = tile.getBeeRows();
		int beeBottom = ApiaryGuiLayoutHelper.getBeeBottom(beeRows);
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(beeBottom, beeRows) + ApiaryGuiLayoutHelper.getOutputH();
		return ApiaryGuiLayoutHelper.getInventoryY(outputBottom);
	}

	/**
	 * 玩家物品栏 X 偏移 — 根据工厂等级 imageWidth 居中
	 * <br/>
	 * Ultimate 工厂 imageWidth=210，其他=176。
	 * 通过 tile getter 获取列数，避免依赖 tile.getTier()（ME/EME 版本返回 null）。
	 */
	@Override
	protected int getInventoryXOffset() {
		int beeCols = tile.getBeeCols();
		int outputCols = tile.getOutputCols();
		int imageWidth = ApiaryGuiLayoutHelper.getImageWidth(beeCols, outputCols);
		return ApiaryGuiLayoutHelper.getInventoryX(imageWidth);
	}
}
