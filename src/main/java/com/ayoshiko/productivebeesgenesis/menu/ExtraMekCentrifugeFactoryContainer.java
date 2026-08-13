package com.ayoshiko.productivebeesgenesis.menu;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * ME扩展版离心机工厂Container
	 * <br/>
	 * 继承MekanismTileContainer，槽位由基类自动从方块实体提取。
	 * 基类自动处理：升级槽、输入/输出/能量槽、侧面配置同步、红石控制同步。
	 * <p>
	 * 额外添加PB升级虚拟槽位（输入/输出），供 GuiPbUpgradeWindow 绑定。
	 * <p>
	 * 重写偏移方法以适配3行输出槽布局和ME等级的宽GUI：
	 * - Y偏移135（对应inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠
	 * - X偏移通过FactoryLayoutHelper的ExtraFactoryTier重载方法动态计算
	 */
public class ExtraMekCentrifugeFactoryContainer extends MekanismTileContainer<TileEntityExtraMekCentrifugeFactory>
		implements IPbUpgradeSlotContainer {

	/** PB升级输入虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeInputSlot;

	/** PB升级输出虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeOutputSlot;

	public ExtraMekCentrifugeFactoryContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv,
		@NotNull TileEntityExtraMekCentrifugeFactory tile) {
		super(type, id, inv, tile);
	}

	/** 添加槽位 — 添加PB升级输入/输出虚拟槽 */
	@Override
	protected void addSlots() {
		super.addSlots();
		// null 守卫：客户端 Container 构造时 pbUpgradeDelegate 可能尚未初始化
		PbUpgradeInventorySlot inputSlot = tile.getPbUpgradeInputSlot();
		PbUpgradeInventorySlot outputSlot = tile.getPbUpgradeOutputSlot();
		if (inputSlot == null || outputSlot == null) {
			ProductiveBeesGenesis.LOGGER.warn("ME 工厂离心机 Container 构造时 PB 升级槽位为 null，跳过虚拟槽注册");
			return;
		}
		pbUpgradeInputSlot = inputSlot.createContainerSlot();
		addSlot(pbUpgradeInputSlot);
		pbUpgradeOutputSlot = outputSlot.createContainerSlot();
		addSlot(pbUpgradeOutputSlot);
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
	 * Y偏移 — 3行输出槽布局需要更大的Y偏移
	 * <br/>
	 * 固定135（inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠。
	 */
	@Override
	protected int getInventoryYOffset() {
		return 135;
	}

	/**
	 * X偏移 — 使用FactoryLayoutHelper的ExtraFactoryTier重载方法动态计算
	 * <br/>
	 * ME等级使用公式：8 + 19 * (tier.processes - 3)，与ME原版ExtraFactoryContainer一致。
	 * 但由于3行输出槽需要额外宽度，使用FactoryLayoutHelper统一计算。
	 */
	@Override
	protected int getInventoryXOffset() {
		int imageWidthAddition = FactoryLayoutHelper.getImageWidthAddition(tile.tier);
		if (imageWidthAddition > 0) {
			// ME等级：动态居中公式
			int imageWidth = 176 + imageWidthAddition;
			return super.getInventoryXOffset() + (imageWidth / 2 - 90);
		}
		return super.getInventoryXOffset();
	}
}
