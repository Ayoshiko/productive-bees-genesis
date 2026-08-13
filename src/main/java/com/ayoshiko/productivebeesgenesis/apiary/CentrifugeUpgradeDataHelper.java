package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.mek.ICentrifugePbUpgradeAccess;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeNbtKeys;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2InputFilter;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2OutputStateHolder;
import com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.energy.IEnergyContainer;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
	 * 离心机工厂升级数据构建/应用工具类
	 * <br/>
	 * 将 {@link com.ayoshiko.productivebeesgenesis.mek.AbstractMekCentrifugeFactory} 中
	 * getUpgradeData/parseUpgradeData 的 PB 升级 + AE2 per-tile 状态保存/恢复逻辑提取为静态方法，
	 * 供三个工厂体系（原版/ME/EME）复用，消除因 Java 单继承限制导致的逻辑重复。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅处理升级数据的构建与应用，不涉及 tick 处理或槽位管理</li>
	 *   <li>开闭原则：通过参数传入委托和状态持有者，不依赖具体工厂类</li>
	 *   <li>依赖倒置：参数类型使用 {@link com.ayoshiko.productivebeesgenesis.mek.ICentrifugePbUpgradeAccess}
	 *       和 {@link Ae2OutputStateHolder}，而非具体工厂类</li>
	 * </ul>
	 * <p>
	 * 线程安全：所有方法仅在服务端主线程的升级流程中调用，无需额外同步。
	 *
	 * @since Task 22
	 */
public final class CentrifugeUpgradeDataHelper {

	/** 过滤条目 index 合理上限 — 防止损坏数据触发过大数组分配（16页 × 9槽 = 144，取 256 余量） */
	private static final int MAX_FILTER_INDEX = 256;

	private CentrifugeUpgradeDataHelper() {
	}

	/**
	 * 构建离心机工厂升级数据 — 保存完整状态供 ItemTierInstaller 升级时流转
	 * <br/>
	 * 标准字段（能量/进度/槽位/组件/排序）由 {@link CentrifugeUpgradeData} 父类
	 * {@link mekanism.common.upgrade.MachineUpgradeData} 承载，
	 * 额外字段（PB 升级/AE2 设置）由 {@link CentrifugeUpgradeData} 扩展。
	 * <p>
	 * AE2 未加载时使用默认值 false/0/空，与 ApiaryUpgradeData 模式一致。
	 *
	 * @param provider           注册表访问器
	 * @param redstone           红石控制状态
	 * @param controlType        红石控制模式
	 * @param energyContainer    能量容器
	 * @param progress           进度数组（工厂为多进程）
	 * @param energySlot         能量槽（含物品内容）
	 * @param inputSlots         输入槽列表
	 * @param outputSlots        输出槽列表
	 * @param sorting            排序开关状态
	 * @param components         组件列表
	 * @param pbUpgradeAccess    PB 升级访问接口（提供升级数量映射与槽位，基础机/工厂机均适用）
	 * @param ae2StateHolder     AE2 状态持有者（提供 per-tile 设置）
	 * @param fluidOutputHolder  Task 5: 流体输出槽持有者（MultiFluidTankHolder 时序列化多槽内容）
	 * @return 包含完整离心机工厂状态的升级数据
	 */
	public static CentrifugeUpgradeData buildUpgradeData(
			HolderLookup.Provider provider,
			boolean redstone, RedstoneControl controlType,
			IEnergyContainer energyContainer, int[] progress,
			EnergyInventorySlot energySlot,
			List<IInventorySlot> inputSlots, List<IInventorySlot> outputSlots,
			boolean sorting, List<ITileComponent> components,
			ICentrifugePbUpgradeAccess pbUpgradeAccess,
			Ae2OutputStateHolder ae2StateHolder,
			IFluidTankHolder fluidOutputHolder) {

		// PB 升级数量映射（键为 PbUpgradeType.name()，避免枚举硬依赖）
		Map<String, Integer> pbUpgrades = new HashMap<>();
		for (Map.Entry<PbUpgradeType, Integer> entry : pbUpgradeAccess.getPbUpgradeCounts().entrySet()) {
			if (entry.getValue() > 0) {
				pbUpgrades.put(entry.getKey().name(), entry.getValue());
			}
		}

		// PB 升级输入/输出槽内容（保存槽内待安装/已卸载的升级物品，防止升级时丢失）
		CompoundTag pbUpgradeInputNbt = pbUpgradeAccess.getPbUpgradeInputSlot().serializeNBT(provider);
		CompoundTag pbUpgradeOutputNbt = pbUpgradeAccess.getPbUpgradeOutputSlot().serializeNBT(provider);

		// AE2 per-tile 设置（AE2 未加载时使用默认值 false/0/空，与 ApiaryUpgradeData 模式一致）
		boolean aeLoaded = Ae2IntegrationLoader.isAe2Loaded();
		boolean aeItemInputEnabled = aeLoaded && ae2StateHolder.isAeItemInputEnabled();
		boolean aeInputNbtIgnore = aeLoaded && ae2StateHolder.isAeInputNbtIgnore();
		boolean aeItemOutputEnabled = aeLoaded && ae2StateHolder.isAeItemOutputEnabled();
		boolean aeFluidOutputEnabled = aeLoaded && ae2StateHolder.isAeFluidOutputEnabled();
		// smelting compat does not depend on AE2: always preserve the per-tile switch
		boolean smeltingCompatEnabled = ae2StateHolder.isSmeltingCompatEnabled();
		boolean centrifugeDirectAeOutputEnabled = aeLoaded
				&& ae2StateHolder.isCentrifugeDirectAeOutputEnabled();
		int aeInputFilterMode = 0;
		Map<Integer, String> aeInputFilterEntries = new HashMap<>();
		Map<Integer, Long> aeInputFilterAmounts = new HashMap<>();
		Map<Integer, Boolean> aeInputFilterUnlimited = new HashMap<>();
		boolean preciseMode = false;
		if (aeLoaded) {
			Ae2InputFilter filter = ae2StateHolder.getOrCreateInputFilter();
			aeInputFilterMode = filter.getFilterMode().ordinal();
			preciseMode = filter.isPreciseMode();
			// V15: 使用 getNonEmptyEntries() 获取带位置索引的条目（保留位置固定语义）
			for (Ae2InputFilter.IndexedEntry ie : filter.getNonEmptyEntries()) {
				aeInputFilterEntries.put(ie.index(), ie.entry());
				// 修复：同步保存直连条目的拉取配额与无限提供标志（否则升级后配额回退默认 64）
				aeInputFilterAmounts.put(ie.index(), filter.getDirectAmountAt(ie.index()));
				aeInputFilterUnlimited.put(ie.index(), filter.isDirectUnlimitedAt(ie.index()));
			}
		}

		// Task 5: 多流体槽 NBT 序列化 — 等级升级持久化与扳手拆卸/区块存档一致
		// v13: 基础离心机单流体槽也序列化(复用 multiFluidTanksNbt 字段),修复升级为工厂时流体丢失
		CompoundTag multiFluidTanksNbt = null;
		if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
			CompoundTag nbtRoot = new CompoundTag();
			multiHolder.writeToNBT(nbtRoot, provider);
			multiFluidTanksNbt = nbtRoot;
		} else if (fluidOutputHolder != null) {
			// v13: 基础离心机单流体槽序列化 — 复用 multiFluidTanksNbt 字段
			// 构造单 entry 的 ListTag NBT,与 MultiFluidTankHolder 格式兼容,供工厂 readFromNBT 恢复
			List<IExtendedFluidTank> tanks = fluidOutputHolder.getTanks(null);
			if (tanks != null && !tanks.isEmpty()) {
				FluidStack fluid = tanks.get(0).getFluid();
				if (!fluid.isEmpty()) {
					multiFluidTanksNbt = serializeSingleFluidTank(fluid, provider);
				}
			}
		}

		// 模块 3 Bug 2: 深拷贝输出槽/输入槽/能量槽物品，独立于父类引用列表
		// 原理：getUpgradeData 调用 buildUpgradeData 后会清空旧方块槽位（saveAllItemsForDrop），
		// 父类 outputSlots/inputSlots/energySlot 引用指向空栈，applyUpgradeData 必须从深拷贝恢复。
		// O(N) 复杂度可接受（N=输出槽+输入槽数量，基础机 4 个，工厂 2×processes+1 个）
		List<ItemStack> outputItems = new ArrayList<>(outputSlots.size());
		for (IInventorySlot slot : outputSlots) {
			outputItems.add(slot.getStack().copy());
		}
		List<ItemStack> inputItems = new ArrayList<>(inputSlots.size());
		for (IInventorySlot slot : inputSlots) {
			inputItems.add(slot.getStack().copy());
		}
		ItemStack energyItem = energySlot.getStack().copy();

		return new CentrifugeUpgradeData(provider, redstone, controlType,
				energyContainer, progress, energySlot, inputSlots, outputSlots,
				sorting, components,
				pbUpgrades,
				pbUpgradeInputNbt, pbUpgradeOutputNbt,
				aeItemInputEnabled, aeInputNbtIgnore,
				aeInputFilterMode, aeInputFilterEntries, aeInputFilterAmounts, aeInputFilterUnlimited, preciseMode,
				aeItemOutputEnabled, aeFluidOutputEnabled,
				smeltingCompatEnabled, centrifugeDirectAeOutputEnabled,
				multiFluidTanksNbt,
				outputItems,
				inputItems,
				energyItem);
	}

	/**
	 * 序列化单流体槽为兼容 MultiFluidTankHolder 格式的 NBT
	 * <br/>
	 * v13: 基础离心机升级为工厂时,单流体槽内容需持久化到 multiFluidTanksNbt 字段。
	 * 构造单 entry 的 ListTag,与 {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankNbtCodec#writeToNBT}
	 * 输出格式一致(省略 count 字段,readFromNBT 不读取该字段),使工厂的
	 * {@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder#readFromNBT}
	 * 能直接读取并注入到首个槽位。
	 *
	 * @param fluid    非空流体栈
	 * @param provider 注册表访问器(FluidStack.save 需要)
	 * @return 兼容 multiFluidTanksNbt 格式的 NBT
	 */
	private static CompoundTag serializeSingleFluidTank(
		@NotNull FluidStack fluid,
		@NotNull HolderLookup.Provider provider
	) {
		CompoundTag root = new CompoundTag();
		CompoundTag multiRoot = new CompoundTag();
		ListTag tanksList = new ListTag();
		CompoundTag entry = new CompoundTag();
		entry.put("fluidStack", fluid.save(provider));
		tanksList.add(entry);
		multiRoot.put("tanks", tanksList);
		root.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, multiRoot);
		return root;
	}

	/**
	 * 应用离心机工厂升级数据 — 恢复 PB 升级和 AE2 per-tile 设置
	 * <br/>
	 * 调用方需先通过父类 {@code super.parseUpgradeData} 恢复标准字段
	 * （能量/进度/槽位/组件/排序），本方法仅恢复扩展字段。
	 * <p>
	 * null 守卫：
	 * <ul>
	 *   <li>AE2 未加载时跳过 AE2 相关恢复，新方块使用默认值</li>
	 *   <li>PB 升级映射为空或 null 时跳过 PB 恢复</li>
	 * </ul>
	 * <p>
	 * 模块 3 Bug 2：追加从深拷贝字段恢复输出槽/输入槽/能量槽物品。
	 * 由于 super.parseUpgradeData 通过父类引用列表读取槽位内容，
	 * 而 getUpgradeData 调用 saveAllItemsForDrop 已清空旧方块槽位，
	 * 引用指向空栈，必须从深拷贝恢复。
	 * 向后兼容：深拷贝字段为 null 时跳过（旧升级数据，由 super.parseUpgradeData 引用路径处理）。
	 *
	 * @param provider           注册表访问器
	 * @param data               离心机工厂升级数据
	 * @param pbUpgradeAccess    PB 升级访问接口（接收升级数量恢复，基础机/工厂机均适用）
	 * @param ae2StateHolder     AE2 状态持有者（接收 per-tile 设置恢复）
	 * @param fluidOutputHolder  Task 5: 流体输出槽持有者（MultiFluidTankHolder 时恢复多槽内容）
	 * @param targetInputSlots   模块 3 Bug 2: 新方块输入槽列表（用于深拷贝恢复，null 时跳过）
	 * @param targetOutputSlots  模块 3 Bug 2: 新方块输出槽列表（用于深拷贝恢复，null 时跳过）
	 * @param targetEnergySlot   模块 3 Bug 2: 新方块能量槽（用于深拷贝恢复，null 时跳过）
	 */
	public static void applyUpgradeData(
			HolderLookup.Provider provider,
			CentrifugeUpgradeData data,
			ICentrifugePbUpgradeAccess pbUpgradeAccess,
			Ae2OutputStateHolder ae2StateHolder,
			IFluidTankHolder fluidOutputHolder,
			@Nullable List<IInventorySlot> targetInputSlots,
			@Nullable List<IInventorySlot> targetOutputSlots,
			@Nullable IInventorySlot targetEnergySlot) {

		// 恢复 PB 升级输入/输出槽内容（防止升级时槽内物品丢失，null 守卫）
		// 修复 v14 loadSlots/loadCounts 顺序：必须先恢复槽位,再恢复数量。
		// 原理:loadCounts 内部 applyCountWithLimit 在数量超过配置上限时,会将超出部分注入输出槽。
		// 若输出槽未先恢复,注入的超出部分会被后续 deserializeNBT 覆盖,导致升级物品凭空消失。
		if (data.pbUpgradeInputNbt != null) {
			pbUpgradeAccess.getPbUpgradeInputSlot().deserializeNBT(provider, data.pbUpgradeInputNbt);
		}
		if (data.pbUpgradeOutputNbt != null) {
			pbUpgradeAccess.getPbUpgradeOutputSlot().deserializeNBT(provider, data.pbUpgradeOutputNbt);
		}

		// 恢复 PB 升级数量（null/空守卫）
		if (data.pbUpgrades != null && !data.pbUpgrades.isEmpty()) {
			CompoundTag pbNbt = new CompoundTag();
			CompoundTag countsTag = new CompoundTag();
			for (Map.Entry<String, Integer> entry : data.pbUpgrades.entrySet()) {
				try {
					PbUpgradeType type = PbUpgradeType.valueOf(entry.getKey());
					if (!type.isBuiltin()) {
						countsTag.putInt(type.getId(), entry.getValue());
					}
				} catch (IllegalArgumentException ignored) {
					// 未知升级类型名称，跳过（向前兼容）
				}
			}
			pbNbt.put(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS, countsTag);
			// 复用 loadCounts 反序列化路径（含上限 clamp 和 supported 校验）
			pbUpgradeAccess.loadCounts(pbNbt);
		}

		// 恢复 AE2 per-tile 设置（AE2 未加载时跳过，新方块使用默认值）
		// Restore per-tile smelting compat (independent of AE2)
		ae2StateHolder.setSmeltingCompatEnabled(data.smeltingCompatEnabled);
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			ae2StateHolder.setCentrifugeDirectAeOutputEnabled(data.centrifugeDirectAeOutputEnabled);
			ae2StateHolder.setAeItemInputEnabled(data.aeItemInputEnabled);
			ae2StateHolder.setAeInputNbtIgnore(data.aeInputNbtIgnore);
			ae2StateHolder.setAeItemOutputEnabled(data.aeItemOutputEnabled);
			ae2StateHolder.setAeFluidOutputEnabled(data.aeFluidOutputEnabled);
			// 恢复输入过滤器和条目
			Ae2InputFilter filter = ae2StateHolder.getOrCreateInputFilter();
			Ae2InputFilter.FilterMode[] modes = Ae2InputFilter.FilterMode.values();
			if (data.aeInputFilterMode >= 0 && data.aeInputFilterMode < modes.length) {
				filter.setFilterMode(modes[data.aeInputFilterMode]);
			}
			filter.setPreciseMode(data.preciseMode);
			filter.clearEntries();
			if (data.aeInputFilterEntries != null) {
				// V15: 按 index 恢复条目（保留位置固定语义和 #block 后缀）
				// 防御性范围检查：跳过超出合理上限的 index，防止损坏数据触发过大数组分配
				for (Map.Entry<Integer, String> entry : data.aeInputFilterEntries.entrySet()) {
					int idx = entry.getKey();
					if (idx >= 0 && idx < MAX_FILTER_INDEX) {
						filter.setEntryAtIndex(idx, entry.getValue());
						// 修复：恢复直连条目的拉取配额与无限提供标志
						// （setEntryAtIndex 对直连条目固定写默认 64/false，必须显式覆盖）
						if (data.aeInputFilterAmounts != null) {
							Long amount = data.aeInputFilterAmounts.get(idx);
							if (amount != null) {
								filter.setDirectAmountAt(idx, amount);
							}
						}
						if (data.aeInputFilterUnlimited != null
								&& Boolean.TRUE.equals(data.aeInputFilterUnlimited.get(idx))) {
							// setEntryAtIndex 后 unlimited=false，toggle 到 true 恢复无限提供
							filter.toggleDirectUnlimitedAt(idx);
						}
					}
				}
			}
		}

		// Task 5: 恢复多流体槽 — 等级升级持久化与扳手拆卸/区块存档一致
		// null 守卫：multiFluidTanksNbt 为 null 时跳过（SINGLE 模式或无多槽数据）
		// 修复 MEDIUM-3: MULTI→SINGLE 降级时不再直接丢弃数据,改为合并多流体槽内容到单流体槽
		if (data.multiFluidTanksNbt != null) {
			if (fluidOutputHolder instanceof MultiFluidTankHolder multiHolder) {
				multiHolder.readFromNBT(data.multiFluidTanksNbt, provider);
			} else {
				// 修复 MEDIUM-3: holder 类型不匹配（MULTI→SINGLE 降级）,合并多流体槽内容到单流体槽
				mergeMultiFluidIntoSingleTank(data.multiFluidTanksNbt, provider, fluidOutputHolder);
			}
		}

		// 模块 3 Bug 2: 从深拷贝字段恢复输出槽/输入槽/能量槽物品
		// 原理：super.parseUpgradeData 通过父类引用列表读取槽位内容，
		// 但 getUpgradeData 调用 saveAllItemsForDrop 已清空旧方块槽位，
		// 引用指向空栈，必须从深拷贝字段恢复（覆盖 super.parseUpgradeData 写入的空栈）。
		// 向后兼容：深拷贝字段为 null 时跳过（旧升级数据，super.parseUpgradeData 引用路径已处理）
		restoreItemStackDeepCopies(data, targetInputSlots, targetOutputSlots, targetEnergySlot);
	}

	/**
	 * 从深拷贝字段恢复输出槽/输入槽/能量槽物品到新方块
	 * <br/>
	 * 模块 3 Bug 2：applyUpgradeData 末尾调用，覆盖 super.parseUpgradeData 写入的空栈。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP：仅负责从深拷贝字段恢复槽位，不涉及 PB 升级/AE2/流体恢复</li>
	 *   <li>OCP：通过参数传入目标槽位列表，不依赖具体 TileEntity 类型</li>
	 *   <li>向后兼容：深拷贝字段或目标槽位为 null 时跳过（旧数据/未传入目标槽位）</li>
	 * </ul>
	 *
	 * @param data              离心机工厂升级数据
	 * @param targetInputSlots  新方块输入槽列表（null 时跳过输入槽恢复）
	 * @param targetOutputSlots 新方块输出槽列表（null 时跳过输出槽恢复）
	 * @param targetEnergySlot  新方块能量槽（null 时跳过能量槽恢复）
	 */
	private static void restoreItemStackDeepCopies(
			CentrifugeUpgradeData data,
			@Nullable List<IInventorySlot> targetInputSlots,
			@Nullable List<IInventorySlot> targetOutputSlots,
			@Nullable IInventorySlot targetEnergySlot) {
		// 输出槽深拷贝恢复（顺序与 data.outputItems 一致）
		if (data.outputItems != null && targetOutputSlots != null) {
			for (int i = 0; i < data.outputItems.size() && i < targetOutputSlots.size(); i++) {
				targetOutputSlots.get(i).setStack(data.outputItems.get(i));
			}
		}
		// 输入槽深拷贝恢复
		if (data.inputItems != null && targetInputSlots != null) {
			for (int i = 0; i < data.inputItems.size() && i < targetInputSlots.size(); i++) {
				targetInputSlots.get(i).setStack(data.inputItems.get(i));
			}
		}
		// 能量槽深拷贝恢复
		if (data.energyItem != null && targetEnergySlot != null) {
			targetEnergySlot.setStack(data.energyItem);
		}
	}

	/**
	 * 合并多流体槽 NBT 内容到单流体槽（MULTI→SINGLE 降级数据保护）
	 * <br/>
	 * 修复 MEDIUM-3: 等级升级后配置变更导致 holder 类型不匹配（MULTI→SINGLE）时,
	 * 不直接丢弃多流体槽数据,而是解析 NBT 并按顺序插入到单流体槽中。
	 * 同种流体会合并（受单槽容量限制）,不同种流体按 NBT 顺序填入,容量不足时丢弃超出部分。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>SRP: 仅处理 MULTI→SINGLE 降级合并,不涉及 MULTI→MULTI 恢复（由 readFromNBT 处理）</li>
	 *   <li>OCP: 通过 IFluidTankHolder 抽象访问槽位,不依赖具体 holder 实现</li>
	 *   <li>异常处理: 单个 FluidStack 解析失败不影响其他流体恢复</li>
	 * </ul>
	 *
	 * @param multiFluidTanksNbt 多流体槽 NBT（包含 NBT_KEY_MULTI_FLUID_TANKS 键）
	 * @param provider           注册表访问器（FluidStack.parseOptional 需要）
	 * @param fluidOutputHolder  单流体槽持有者（SINGLE 模式,可为 null）
	 */
	private static void mergeMultiFluidIntoSingleTank(@NotNull CompoundTag multiFluidTanksNbt,
			@NotNull HolderLookup.Provider provider,
			@Nullable IFluidTankHolder fluidOutputHolder) {
		if (fluidOutputHolder == null) {
			DevLog.warn("fluid_tank", "MULTI→SINGLE 降级:目标 holder 为 null,丢弃多流体槽数据");
			return;
		}
		if (!multiFluidTanksNbt.contains(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, Tag.TAG_COMPOUND)) {
			return;
		}
		CompoundTag root = multiFluidTanksNbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS);
		ListTag list = root.getList("tanks", Tag.TAG_COMPOUND);
		if (list.isEmpty()) {
			return;
		}
		// 获取单流体槽（SINGLE 模式仅一个槽,null 表示内部访问）
		List<IExtendedFluidTank> tanks = fluidOutputHolder.getTanks(null);
		if (tanks.isEmpty()) {
			DevLog.warn("fluid_tank", "MULTI→SINGLE 降级:目标 holder 无可用槽,丢弃 {} 个流体栈", list.size());
			return;
		}
		IExtendedFluidTank targetTank = tanks.get(0);
		long totalMerged = 0;
		long totalDiscarded = 0;
		int processedCount = 0;
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			FluidStack stack = FluidStack.parseOptional(provider, entry.getCompound("fluidStack"));
			if (stack.isEmpty()) {
				continue; // 解析失败跳过,不影响其他槽位
			}
			FluidStack remaining = targetTank.insert(stack, Action.EXECUTE, AutomationType.INTERNAL);
			int merged = stack.getAmount() - remaining.getAmount();
			totalMerged += merged;
			processedCount++;
			if (!remaining.isEmpty()) {
				totalDiscarded += remaining.getAmount();
			}
		}
		if (totalDiscarded > 0) {
			DevLog.warn("fluid_tank", "MULTI→SINGLE 降级:合并 {} 个流体栈共 {} mB,容量不足丢弃 {} mB",
					processedCount, totalMerged, totalDiscarded);
		} else if (totalMerged > 0) {
			DevLog.info("fluid_tank", "MULTI→SINGLE 降级:成功合并 {} 个流体栈共 {} mB 到单槽",
					processedCount, totalMerged);
		}
	}
}
