package com.ayoshiko.productivebeesgenesis.menu;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEFactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.CentrifugeFactoryCommonLogic;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * EME扩展版离心机工厂Container
	 * <br/>
	 * 继承MekanismTileContainer，槽位由基类自动从方块实体提取。
	 * <p>
	 * 额外添加PB升级虚拟槽位（输入/输出），供 GuiPbUpgradeWindow 绑定。
	 * <p>
	 * 重写偏移方法以适配3行输出槽布局和EME等级的宽GUI：
	 * - Y偏移135（对应inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠
	 * - X偏移通过 {@link EMEFactoryLayoutHelper} 动态计算，
	 *   使用tier.inventoryLabelX（EME枚举直接存储了计算好的值）
	 * <p>
	 * <b>类加载安全</b>：本类引用 {@link EMEFactoryLayoutHelper}（compat 隔离类）和
	 * {@link TileEntityEMExtraMekCentrifugeFactory}（compat 隔离类），
	 * 仅在 EME 加载时由 {@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEMenuTypeRegistration} 注册实例化。
	 */
public class EMExtraMekCentrifugeFactoryContainer extends MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory>
		implements IPbUpgradeSlotContainer {

	/** PB升级输入虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeInputSlot;

	/** PB升级输出虚拟槽位 */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeOutputSlot;

	public EMExtraMekCentrifugeFactoryContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, @NotNull TileEntityEMExtraMekCentrifugeFactory tile) {
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
			ProductiveBeesGenesis.LOGGER.warn("EME 工厂离心机 Container 构造时 PB 升级槽位为 null，跳过虚拟槽注册");
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

	/** Y偏移 — 3行输出槽布局需要更大的Y偏移 */
	@Override
	protected int getInventoryYOffset() {
		return 135;
	}

	/** X偏移 — 使用EME tier直接存储的inventoryLabelX值 */
	@Override
	protected int getInventoryXOffset() {
		int labelX = EMEFactoryLayoutHelper.getInventoryLabelX(tile.tier);
		if (labelX > 0) {
			return labelX;
		}
		return super.getInventoryXOffset();
	}
}
