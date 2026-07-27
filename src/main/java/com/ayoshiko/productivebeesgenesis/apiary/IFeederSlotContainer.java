package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.List;

import mekanism.common.inventory.container.slot.VirtualInventoryContainerSlot;
import org.jetbrains.annotations.Nullable;

/**
 * 喂食槽容器接口
 * <br/>
 * 由 MekApiaryContainer 和 MekApiaryFactoryContainer 实现，
 * 提供对喂食器虚拟槽位列表的统一访问，使 GUI 窗口无需关心具体容器类型。
 */
public interface IFeederSlotContainer {

	/** 获取喂食器虚拟槽位列表 */
	@Nullable
	List<VirtualInventoryContainerSlot> getFeederSlots();

	/** 获取指定索引的喂食器虚拟槽位 */
	@Nullable
	VirtualInventoryContainerSlot getFeederSlot(int index);
}
