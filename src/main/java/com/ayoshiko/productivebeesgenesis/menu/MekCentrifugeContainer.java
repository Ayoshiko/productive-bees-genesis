package com.ayoshiko.productivebeesgenesis.menu;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeSlotContainer;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.factory.TileEntityFactory;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * MEK离心机Container
	 * <br/>
	 * 继承MekanismTileContainer，槽位由基类自动从方块实体提取。
	 * 基类自动处理：升级槽、输入/输出/能量槽、侧面配置同步、红石控制同步。
	 * <p>
	 * 基础离心机和原版工厂离心机均添加PB升级虚拟槽位（输入/输出），供 GuiPbUpgradeWindow 绑定。
	 * 原版工厂离心机通过 {@link AbstractMekCentrifugeFactory} 暴露槽位访问方法。
	 * <p>
	 * 工厂版重写偏移方法以适配3行输出槽布局：
	 * - Y偏移135（对应inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠
	 * - X偏移通过FactoryLayoutHelper动态计算，支持原版ULTIMATE与EM高等级
	 */
public class MekCentrifugeContainer<TILE extends TileEntityMekanism> extends MekanismTileContainer<TILE>
		implements IPbUpgradeSlotContainer {

	/** PB升级输入虚拟槽位（基础离心机或原版工厂离心机创建） */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeInputSlot;

	/** PB升级输出虚拟槽位（基础离心机或原版工厂离心机创建） */
	@Nullable
	private VirtualInventoryContainerSlot pbUpgradeOutputSlot;

	public MekCentrifugeContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, @NotNull TILE tile) {
		super(type, id, inv, tile);
	}

	/**
	 * 添加槽位 — 基础离心机和原版工厂离心机添加PB升级输入/输出虚拟槽
	 * <br/>
	 * 原版工厂离心机的 tile 为 {@link AbstractMekCentrifugeFactory} 子类，
	 * 通过其 getPbUpgradeInputSlot/getPbUpgradeOutputSlot 暴露 PB 升级槽位。
	 */
	@Override
	protected void addSlots() {
		super.addSlots();
		if (tile instanceof TileEntityMekCentrifuge centrifuge) {
			addPbUpgradeSlots(centrifuge.getPbUpgradeInputSlot(), centrifuge.getPbUpgradeOutputSlot());
		} else if (tile instanceof AbstractMekCentrifugeFactory factory) {
			addPbUpgradeSlots(factory.getPbUpgradeInputSlot(), factory.getPbUpgradeOutputSlot());
		}
	}

	/** 创建并添加 PB 升级输入/输出虚拟槽 — 消除重复代码；含 null 守卫防止客户端构造时 NPE */
	private void addPbUpgradeSlots(PbUpgradeInventorySlot inputSlot, PbUpgradeInventorySlot outputSlot) {
		if (inputSlot == null || outputSlot == null) {
			com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis.LOGGER
					.warn("离心机 Container 构造时 PB 升级槽位为 null，跳过虚拟槽注册");
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
	 * 工厂版Y偏移 — 3行输出槽布局需要更大的Y偏移
	 * <br/>
	 * 固定135（inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠。
	 * EM高等级无需调整，本项目布局固定为3行输出槽。
	 */
	@Override
	protected int getInventoryYOffset() {
		if (tile instanceof TileEntityFactory<?>) {
			return 135;
		}
		return super.getInventoryYOffset();
	}

	/**
	 * 工厂版X偏移 — 使用FactoryLayoutHelper动态计算，支持原版ULTIMATE与EM高等级
	 * <br/>
	 * 原版ULTIMATE：imageWidthAddition=34，偏移=addition/2=17（原版行为）
	 * EM高等级：参考EM FactoryContainerMixin的动态居中公式
	 *   offset = base + (imageWidth/2 - inventorySize/2)
	 *   imageWidth = 176 + addition，inventorySize = 9*20 = 180
	 */
	@Override
	protected int getInventoryXOffset() {
		if (tile instanceof TileEntityFactory<?> factory) {
			int imageWidthAddition = FactoryLayoutHelper.getImageWidthAddition(factory.tier);
			if (imageWidthAddition > 0) {
				if (FactoryLayoutHelper.isEMHighTier(factory.tier)) {
					// EM高等级：动态居中公式，与EM原生FactoryContainerMixin一致
					int imageWidth = 176 + imageWidthAddition;
					return super.getInventoryXOffset() + (imageWidth / 2 - 90);
				}
				// 原版ULTIMATE：偏移addition/2以居中（34/2=17）
				return super.getInventoryXOffset() + imageWidthAddition / 2;
			}
		}
		return super.getInventoryXOffset();
	}
}
