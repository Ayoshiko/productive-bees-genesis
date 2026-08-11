package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import org.jetbrains.annotations.Nullable;

/**
	 * PB升级槽容器接口
	 * <br/>
	 * 由MekApiaryContainer和MekApiaryFactoryContainer实现，
	 * 提供对PB升级虚拟槽位的统一访问，使GUI窗口无需关心具体容器类型。
	 */
public interface IPbUpgradeSlotContainer {

	/** 获取PB升级输入虚拟槽位 */
	@Nullable
	VirtualInventoryContainerSlot getPbUpgradeInputSlot();

	/** 获取PB升级输出虚拟槽位 */
	@Nullable
	VirtualInventoryContainerSlot getPbUpgradeOutputSlot();
}
