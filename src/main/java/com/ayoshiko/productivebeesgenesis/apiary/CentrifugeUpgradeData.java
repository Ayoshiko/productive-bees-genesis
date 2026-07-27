package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.List;
import java.util.Map;

import mekanism.api.energy.IEnergyContainer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.upgrade.MachineUpgradeData;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/**
 * 离心机工厂升级数据
 * <br/>
 * 继承 {@link MachineUpgradeData}，在标准机器升级数据基础上增加离心机工厂特有状态，
 * 用于 ItemTierInstaller 升级离心机工厂等级时完整保存/恢复 PB 升级和 AE2 per-tile 设置。
 * <p>
 * 离心机工厂相比普通 Mekanism 机器多出以下状态：
 * <ul>
 *   <li>PB 升级数量映射（EnumMap，非物品槽位，无法通过 MachineUpgradeData 传递）</li>
 *   <li>AE2 per-tile 输入拉取设置（开关、NBT 忽略、过滤模式、过滤条目）</li>
 *   <li>AE2 per-tile 输出开关（物品/流体）</li>
 *   <li>多流体槽 NBT（Task 5：等级升级持久化,与扳手拆卸/区块存档一致）</li>
 * </ul>
 * <p>
 * 设计原则：
 * <ul>
 *   <li>开闭原则：继承 MachineUpgradeData 扩展字段，不修改父类</li>
 *   <li>单一职责：仅承载数据，不包含序列化/反序列化逻辑（由方块实体的 getUpgradeData/parseUpgradeData 负责）</li>
 * </ul>
 * <p>
 * 序列化策略：
 * <ul>
 *   <li>PB 升级数量用 {@code Map<String, Integer>}，键为 {@code PbUpgradeType.name()}，避免枚举硬依赖</li>
 *   <li>AE2 输入过滤条目用 {@code Map<Integer, String>}，键为槽位 index，值为条目字符串（V15 位置固定语义）</li>
 *   <li>标准字段（能量/进度/槽位/组件）由父类 {@link MachineUpgradeData} 承载，无需重复</li>
 * </ul>
 *
 * @since Task 10
 */
public class CentrifugeUpgradeData extends MachineUpgradeData {

	/** PB 升级数量映射（键为 PbUpgradeType.name()，值为已安装数量） */
	public final Map<String, Integer> pbUpgrades;

	/** PB 升级输入槽 NBT（由 PbUpgradeInventorySlot.serializeNBT 序列化，保存槽内待安装的升级物品） */
	public final CompoundTag pbUpgradeInputNbt;

	/** PB 升级输出槽 NBT（由 PbUpgradeInventorySlot.serializeNBT 序列化，保存槽内已卸载的升级物品） */
	public final CompoundTag pbUpgradeOutputNbt;

	/** per-tile AE2 输入拉取开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeItemInputEnabled;

	/** per-tile AE2 输入 NBT 忽略开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeInputNbtIgnore;

	/** per-tile AE2 输入过滤模式（FilterMode ordinal：0=DISABLED, 1=WHITELIST, 2=BLACKLIST） */
	public final int aeInputFilterMode;

	/** per-tile AE2 输入过滤条目（键为槽位 index，值为条目字符串，V15 位置固定语义） */
	public final Map<Integer, String> aeInputFilterEntries;

	/** per-tile AE2 输入精确模式开关（true 时蜜脾和蜜脾块分别匹配，AE2 未加载时为 false） */
	public final boolean preciseMode;

	/** per-tile AE2 物品输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeItemOutputEnabled;

	/** per-tile AE2 流体输出开关（升级时保留用户设置，AE2 未加载时为 false） */
	public final boolean aeFluidOutputEnabled;

	/**
	 * Task 5: 多流体槽 NBT — 等级升级时持久化 MultiFluidTankHolder 内容
	 * <br/>
	 * <b>设计原理：</b>等级升级（原版版↔ME↔EME）应与扳手拆卸/区块存档一样保留流体槽数据。
	 * buildUpgradeData 时若 holder 是 {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder},
	 * 调用 writeToNBT 序列化所有非空槽位；applyUpgradeData 时若新 holder 仍是 MultiFluidTankHolder,
	 * 调用 readFromNBT 恢复。null 表示无多流体槽数据（SINGLE 模式或未启用多槽）。
	 * <p>
	 * <b>容量丢失警告：</b>readFromNBT 内部会检查 insert 返回值,容量不足时记录 DevLog.warn,
	 * 避免静默丢弃（降级时容量减小触发）。
	 */
	public final CompoundTag multiFluidTanksNbt;

	/**
	 * 离心机工厂升级数据构造函数
	 *
	 * @param provider              注册表访问器
	 * @param redstone              红石控制状态
	 * @param controlType           红石控制模式
	 * @param energyContainer       能量容器（用于读取当前能量值）
	 * @param progress              进度数组（工厂为多进程，长度等于 tier.processes）
	 * @param energySlot            能量槽（含物品内容）
	 * @param inputSlots            输入槽列表（工厂为多进程输入槽）
	 * @param outputSlots           输出槽列表（工厂为多进程主输出槽）
	 * @param sorting               排序开关状态
	 * @param components            组件列表（ITileComponent，由父类序列化为 CompoundTag）
	 * @param pbUpgrades            PB 升级数量映射（键为 PbUpgradeType.name()）
	 * @param pbUpgradeInputNbt     PB 升级输入槽 NBT（保存槽内待安装的升级物品）
	 * @param pbUpgradeOutputNbt    PB 升级输出槽 NBT（保存槽内已卸载的升级物品）
	 * @param aeItemInputEnabled    per-tile AE2 输入拉取开关
	 * @param aeInputNbtIgnore      per-tile AE2 输入 NBT 忽略开关
	 * @param aeInputFilterMode     per-tile AE2 输入过滤模式 ordinal
	 * @param aeInputFilterEntries  per-tile AE2 输入过滤条目（键为槽位 index，值为条目字符串）
	 * @param preciseMode           per-tile AE2 输入精确模式开关
	 * @param aeItemOutputEnabled   per-tile AE2 物品输出开关
	 * @param aeFluidOutputEnabled  per-tile AE2 流体输出开关
	 * @param multiFluidTanksNbt    Task 5: 多流体槽 NBT（null 表示无多槽数据,见字段注释）
	 */
	public CentrifugeUpgradeData(HolderLookup.Provider provider, boolean redstone, RedstoneControl controlType,
			IEnergyContainer energyContainer, int[] progress, EnergyInventorySlot energySlot,
			List<IInventorySlot> inputSlots, List<IInventorySlot> outputSlots, boolean sorting,
			List<ITileComponent> components,
			Map<String, Integer> pbUpgrades,
			CompoundTag pbUpgradeInputNbt, CompoundTag pbUpgradeOutputNbt,
			boolean aeItemInputEnabled, boolean aeInputNbtIgnore,
			int aeInputFilterMode, Map<Integer, String> aeInputFilterEntries, boolean preciseMode,
			boolean aeItemOutputEnabled, boolean aeFluidOutputEnabled,
			@Nullable CompoundTag multiFluidTanksNbt) {
		super(provider, redstone, controlType, energyContainer, progress, energySlot,
				inputSlots, outputSlots, sorting, components);
		this.pbUpgrades = pbUpgrades;
		this.pbUpgradeInputNbt = pbUpgradeInputNbt;
		this.pbUpgradeOutputNbt = pbUpgradeOutputNbt;
		this.aeItemInputEnabled = aeItemInputEnabled;
		this.aeInputNbtIgnore = aeInputNbtIgnore;
		this.aeInputFilterMode = aeInputFilterMode;
		this.aeInputFilterEntries = aeInputFilterEntries;
		this.preciseMode = preciseMode;
		this.aeItemOutputEnabled = aeItemOutputEnabled;
		this.aeFluidOutputEnabled = aeFluidOutputEnabled;
		this.multiFluidTanksNbt = multiFluidTanksNbt;
	}
}
