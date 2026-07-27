package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.List;

import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.upgrade.MachineUpgradeData;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 蜂箱工厂升级数据
 * <br/>
 * 继承 {@link MachineUpgradeData}，在标准机器升级数据基础上增加蜂箱特有状态，
 * 用于 ItemTierInstaller 升级蜂箱工厂时完整保存/恢复所有状态。
 * <p>
 * 蜂箱相比普通 Mekanism 机器多出以下状态：
 * <ul>
 *   <li>蜜蜂槽（BeeSlot[]，非 IInventorySlot，无法通过 MachineUpgradeData 的 inputSlots/outputSlots 传递）</li>
 *   <li>喂食槽（花朵物品，独立于主物品槽位体系）</li>
 *   <li>PB 升级数量映射（EnumMap，非物品槽位）</li>
 *   <li>PB 升级输入/输出槽（PbUpgradeInventorySlot，独立于主物品槽位体系）</li>
 *   <li>蜂蜜流体罐内容（IExtendedFluidTank 的 FluidStack）</li>
 *   <li>蜂笼输出槽（OutputInventorySlot，独立于产物输出槽，不注册到ejector）</li>
 *   <li>选中的蜜蜂槽索引（GUI 高亮状态）</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>开闭原则：继承 MachineUpgradeData 扩展字段，不修改父类</li>
 *   <li>单一职责：仅承载数据，不包含序列化/反序列化逻辑（由 TileEntity 的 getUpgradeData/parseUpgradeData 负责）</li>
 * </ul>
 */
public class ApiaryUpgradeData extends MachineUpgradeData {

	/** 蜜蜂槽数组 NBT（由 ApiarySlotManager.saveBeeSlots 序列化） */
	public final CompoundTag beeSlotsNbt;

	/** 喂食槽 NBT（由 FeederSlotManager.saveFeederSlots 序列化） */
	public final CompoundTag feederSlotsNbt;

	/** PB 升级数量映射 NBT（由 TileEntityMekApiary.savePbUpgradeCounts 序列化） */
	public final CompoundTag pbUpgradeCountsNbt;

	/** PB 升级输入槽 NBT（由 PbUpgradeInventorySlot.serializeNBT 序列化） */
	public final CompoundTag pbUpgradeInputNbt;

	/** PB 升级输出槽 NBT（由 PbUpgradeInventorySlot.serializeNBT 序列化） */
	public final CompoundTag pbUpgradeOutputNbt;

	/** 蜂蜜流体罐内容 NBT（由 FluidStack.save 序列化，可能为空标签表示空罐） */
	public final CompoundTag fluidNbt;

	/** 蜂笼输出槽 NBT（由 OutputInventorySlot.serializeNBT 序列化，独立于产物输出槽） */
	public final CompoundTag cageOutSlotNbt;

	/** 选中的蜜蜂槽索引（-1=未选择） */
	public final int selectedBeeSlot;

	/** per-tile AE2 物品输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeItemOutputEnabled;

	/** per-tile AE2 流体输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeFluidOutputEnabled;

	/**
	 * 蜂箱工厂升级数据构造函数
	 *
	 * @param provider             注册表访问器
	 * @param redstone             红石控制状态
	 * @param controlType          红石控制模式
	 * @param energyContainer      能量容器（用于读取当前能量值）
	 * @param progress             进度数组（蜂箱为单进度，传入长度1的数组）
	 * @param energySlot           能量槽（含物品内容）
	 * @param inputSlots           输入槽列表（蜂箱为蜂笼输入槽，单元素列表）
	 * @param outputSlots          输出槽列表（蜂箱产物输出槽，多元素列表）
	 * @param sorting              排序开关状态
	 * @param components           组件列表（ITileComponent，由父类序列化为 CompoundTag）
	 * @param beeSlotsNbt          蜜蜂槽数组 NBT
	 * @param feederSlotsNbt       喂食槽 NBT
	 * @param pbUpgradeCountsNbt   PB 升级数量映射 NBT
	 * @param pbUpgradeInputNbt    PB 升级输入槽 NBT
	 * @param pbUpgradeOutputNbt   PB 升级输出槽 NBT
	 * @param fluidNbt             蜂蜜流体罐内容 NBT
	 * @param cageOutSlotNbt       蜂笼输出槽 NBT
	 * @param selectedBeeSlot      选中的蜜蜂槽索引
	 * @param aeItemOutputEnabled  per-tile AE2 物品输出开关
	 * @param aeFluidOutputEnabled per-tile AE2 流体输出开关
	 */
	public ApiaryUpgradeData(HolderLookup.Provider provider, boolean redstone, RedstoneControl controlType,
			IEnergyContainer energyContainer, int[] progress, EnergyInventorySlot energySlot,
			List<IInventorySlot> inputSlots, List<IInventorySlot> outputSlots, boolean sorting,
			List<ITileComponent> components,
			CompoundTag beeSlotsNbt, CompoundTag feederSlotsNbt, CompoundTag pbUpgradeCountsNbt,
			CompoundTag pbUpgradeInputNbt, CompoundTag pbUpgradeOutputNbt,
			CompoundTag fluidNbt, CompoundTag cageOutSlotNbt, int selectedBeeSlot,
			boolean aeItemOutputEnabled, boolean aeFluidOutputEnabled) {
		super(provider, redstone, controlType, energyContainer, progress, energySlot,
				inputSlots, outputSlots, sorting, components);
		this.beeSlotsNbt = beeSlotsNbt;
		this.feederSlotsNbt = feederSlotsNbt;
		this.pbUpgradeCountsNbt = pbUpgradeCountsNbt;
		this.pbUpgradeInputNbt = pbUpgradeInputNbt;
		this.pbUpgradeOutputNbt = pbUpgradeOutputNbt;
		this.fluidNbt = fluidNbt;
		this.cageOutSlotNbt = cageOutSlotNbt;
		this.selectedBeeSlot = selectedBeeSlot;
		this.aeItemOutputEnabled = aeItemOutputEnabled;
		this.aeFluidOutputEnabled = aeFluidOutputEnabled;
	}
}
