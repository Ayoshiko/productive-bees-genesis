package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.upgrade.MachineUpgradeData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

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

	/**
	 * 蜂笼输入槽 NBT 快照（由 serializeNBT 序列化）
	 * <br/>
	 * 修复：父类 {@code inputSlots} 持有旧槽位对象引用，getUpgradeData 后的
	 * saveAllItemsForDrop 会清空旧槽位，恢复时必须从快照读取而非旧引用。
	 * null 表示旧版本升级数据（向后兼容回退到 inputSlots 路径）。
	 */
	@Nullable
	public final CompoundTag cageInSlotNbt;

	/**
	 * 能量槽 NBT 快照（由 serializeNBT 序列化）
	 * <br/>
	 * 修复：父类 {@code energySlot} 持有旧槽位对象引用，saveAllItemsForDrop 清空后
	 * 恢复时会读到空栈；改用快照确保升级后能量物品不丢失。
	 * null 表示旧版本升级数据（向后兼容回退到 energySlot 路径）。
	 */
	@Nullable
	public final CompoundTag energySlotNbt;

	/**
	 * 产物溢出缓冲区 NBT 快照（由 ApiaryOutputBuffer.save 序列化）
	 * <br/>
	 * 修复：输出缓冲区不在任何槽位体系内，升级数据此前无字段承载，
	 * 且 saveAllItemsForDrop 会 clear 缓冲区，导致积压产物在升级时凭空消失。
	 * null 表示旧版本升级数据（无缓冲区恢复）。
	 */
	@Nullable
	public final CompoundTag outputBufferNbt;

	/**
	 * 产物输出槽的 ItemStack 深拷贝列表
	 * <br/>
	 * 模块 3 Bug 2 修复：独立于父类 {@code outputSlots}（{@code List<IInventorySlot>} 引用列表），
	 * 防止 {@code setRemoved} 清空槽位后引用指向空栈导致升级数据丢失。
	 * 由 {@link ApiaryNbtSerializer#buildUpgradeData} 通过 {@code ItemStack.copy()} 收集。
	 * {@code null} 表示旧版本升级数据（向后兼容回退到 {@code outputSlots} 路径）。
	 */
	@Nullable
	public final List<ItemStack> outputItems;

	/** 选中的蜜蜂槽索引（-1=未选择） */
	public final int selectedBeeSlot;

	/** per-tile AE2 物品输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeItemOutputEnabled;

	/** per-tile AE2 流体输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeFluidOutputEnabled;

	/** 蜂箱到相邻离心机的特殊直连通道开关。 */
	public final boolean directEjectEnabled;
	public final boolean directAeOutputEnabled;

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
	 * @param outputSlots          输出槽列表（蜂箱产物输出槽，多元素列表，父类引用）
	 * @param sorting              排序开关状态
	 * @param components           组件列表（ITileComponent，由父类序列化为 CompoundTag）
	 * @param beeSlotsNbt          蜜蜂槽数组 NBT
	 * @param feederSlotsNbt       喂食槽 NBT
	 * @param pbUpgradeCountsNbt   PB 升级数量映射 NBT
	 * @param pbUpgradeInputNbt    PB 升级输入槽 NBT
	 * @param pbUpgradeOutputNbt   PB 升级输出槽 NBT
	 * @param fluidNbt             蜂蜜流体罐内容 NBT
	 * @param cageOutSlotNbt       蜂笼输出槽 NBT
	 * @param outputItems          产物输出槽 ItemStack 深拷贝列表（模块 3 Bug 2 修复，null 表示旧版本回退）
	 * @param cageInSlotNbt        蜂笼输入槽 NBT 快照（null 表示旧版本回退到 inputSlots）
	 * @param energySlotNbt        能量槽 NBT 快照（null 表示旧版本回退到 energySlot）
	 * @param outputBufferNbt      产物溢出缓冲区 NBT 快照（null 表示旧版本无缓冲区数据）
	 * @param selectedBeeSlot      选中的蜜蜂槽索引
	 * @param aeItemOutputEnabled  per-tile AE2 物品输出开关
	 * @param aeFluidOutputEnabled per-tile AE2 流体输出开关
	 * @param directEjectEnabled   特殊直连通道开关
	 */
	public ApiaryUpgradeData(HolderLookup.Provider provider, boolean redstone, RedstoneControl controlType,
			IEnergyContainer energyContainer, int[] progress, EnergyInventorySlot energySlot,
			List<IInventorySlot> inputSlots, List<IInventorySlot> outputSlots, boolean sorting,
			List<ITileComponent> components,
			CompoundTag beeSlotsNbt, CompoundTag feederSlotsNbt, CompoundTag pbUpgradeCountsNbt,
			CompoundTag pbUpgradeInputNbt, CompoundTag pbUpgradeOutputNbt,
			CompoundTag fluidNbt, CompoundTag cageOutSlotNbt,
			@Nullable List<ItemStack> outputItems,
			@Nullable CompoundTag cageInSlotNbt, @Nullable CompoundTag energySlotNbt,
			@Nullable CompoundTag outputBufferNbt, int selectedBeeSlot,
			boolean aeItemOutputEnabled, boolean aeFluidOutputEnabled, boolean directEjectEnabled,
			boolean directAeOutputEnabled) {
		super(provider, redstone, controlType, energyContainer, progress, energySlot,
				inputSlots, outputSlots, sorting, components);
		this.beeSlotsNbt = beeSlotsNbt;
		this.feederSlotsNbt = feederSlotsNbt;
		this.pbUpgradeCountsNbt = pbUpgradeCountsNbt;
		this.pbUpgradeInputNbt = pbUpgradeInputNbt;
		this.pbUpgradeOutputNbt = pbUpgradeOutputNbt;
		this.fluidNbt = fluidNbt;
		this.cageOutSlotNbt = cageOutSlotNbt;
		this.outputItems = outputItems;
		this.cageInSlotNbt = cageInSlotNbt;
		this.energySlotNbt = energySlotNbt;
		this.outputBufferNbt = outputBufferNbt;
		this.selectedBeeSlot = selectedBeeSlot;
		this.aeItemOutputEnabled = aeItemOutputEnabled;
		this.aeFluidOutputEnabled = aeFluidOutputEnabled;
		this.directEjectEnabled = directEjectEnabled;
		this.directAeOutputEnabled = directAeOutputEnabled;
	}
}
