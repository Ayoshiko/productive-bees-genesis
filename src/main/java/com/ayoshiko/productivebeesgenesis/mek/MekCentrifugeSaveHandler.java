package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeData;
import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableInt;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.tile.interfaces.IRedstoneControl.RedstoneControl;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
	 * 基础MEK离心机持久化处理器
	 * <br/>
	 * 从 {@link TileEntityMekCentrifuge} 抽取的 NBT 序列化/反序列化、容器同步器注册、
	 * 配置卡数据复制和升级数据构建/应用逻辑。
	 * <p>
	 * 职责：
	 * <ul>
	 *   <li>PB 配方处理进度的 NBT 保存/加载（委托给 {@link PbRecipeProcessor}）</li>
	 *   <li>PB 升级数量/槽位的 NBT 保存/加载</li>
	 *   <li>AE2 网格节点和 per-tile 状态的 NBT 保存/加载（委托给 {@link MekCentrifugeAe2Handler}）</li>
	 *   <li>容器同步器注册（PB 进度、PB 升级数量/安装进度、AE2 开关）</li>
	 *   <li>配置卡数据写入/读取/应用（PB 升级粘贴含生存模式物品消耗）</li>
	 *   <li>升级数据构建（供工厂安装器升级时状态流转）</li>
	 * </ul>
	 * <p>
	 * 设计原则：单一职责，只负责持久化与同步，不涉及槽位管理或配方处理。
	 */
class MekCentrifugeSaveHandler {

	/** PB配方处理器 — 提供 NBT 序列化/反序列化支持 */
	private final PbRecipeProcessor pbProcessor;

	/** 所属方块实体引用 */
	private final TileEntityMekCentrifuge tile;

	/** PB升级处理器 — 提供升级数量/槽位的 NBT 保存/加载 */
	private final MekCentrifugePbUpgradeHandler pbUpgradeHandler;

	/** AE2处理器 — 提供网格节点和 per-tile 状态的 NBT 保存/加载 */
	private final MekCentrifugeAe2Handler ae2Handler;

	MekCentrifugeSaveHandler(PbRecipeProcessor pbProcessor, TileEntityMekCentrifuge tile,
			MekCentrifugePbUpgradeHandler pbUpgradeHandler, MekCentrifugeAe2Handler ae2Handler) {
		this.pbProcessor = pbProcessor;
		this.tile = tile;
		this.pbUpgradeHandler = pbUpgradeHandler;
		this.ae2Handler = ae2Handler;
	}

	// ===== PB 配方进度 NBT =====

	/** 保存PB配方处理进度到NBT — 委托给 {@link PbRecipeProcessor#saveAdditional} */
	void save(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbProcessor.saveAdditional(nbt, provider);
	}

	/** 加载PB配方处理进度 — 委托给 PbRecipeProcessor */
	void load(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		pbProcessor.loadAdditional(nbt, provider);
	}

	/** 同步PB进度到客户端 — 委托给 {@link PbRecipeProcessor#addContainerTrackers} */
	void addPbTrackers(@NotNull MekanismContainer container) {
		pbProcessor.addContainerTrackers(container);
	}

	// ===== PB 升级同步器 =====

	/**
	 * 注册 PB 升级数量和安装进度同步器
	 * <br/>
	 * 按类型差异化同步，离心机仅同步支持的类型，不支持类型恒为0。
	 */
	void addPbUpgradeTrackers(@NotNull MekanismContainer container) {
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (type.isBuiltin()) continue;
			container.track(SyncableInt.create(
					() -> pbUpgradeHandler.getInstalledCount(type),
					count -> pbUpgradeHandler.setClientUpgradeCount(type, count)));
		}
		container.track(SyncableInt.create(pbUpgradeHandler::getInstallTicks, pbUpgradeHandler::setClientInstallTicks));
	}

	// ===== 完整持久化（saveAdditional / loadAdditional） =====

	/**
	 * 保存完整状态 — PB进度 + PB升级 + AE2节点 + AE2 per-tile 状态 + 单流体槽
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#saveAdditional} 在调用 super 后委托。
	 * 修复 v14：追加基础离心机单流体槽序列化（工厂版由 MultiFluidTankHolder 独立处理）。
	 */
	void saveAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		save(nbt, provider);
		pbUpgradeHandler.saveCounts(nbt);
		pbUpgradeHandler.saveSlots(nbt, provider);
		ae2Handler.saveNodeNBT(nbt);
		ae2Handler.savePerTileState(nbt);
		// 修复 v14：序列化基础离心机单流体槽（DRY：复用 serializeFluidTank）
		CompoundTag fluidNbt = serializeFluidTank(provider);
		if (fluidNbt != null) {
			nbt.put(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID, fluidNbt);
		}
	}

	/**
	 * 加载完整状态 — PB进度 + PB升级 + AE2节点 + AE2 per-tile 状态 + 单流体槽
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#loadAdditional} 在调用 super 后委托。
	 * 修复 v14：追加基础离心机单流体槽反序列化（向后兼容：旧存档无此字段时跳过）。
	 * PB 升级槽位与数量均从 NBT 恢复；持久化数量不受当前安装上限裁剪。
	 */
	void loadAdditional(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		load(nbt, provider);
		pbUpgradeHandler.loadSlots(nbt, provider);
		pbUpgradeHandler.loadCounts(nbt);
		ae2Handler.loadNodeNBT(nbt);
		ae2Handler.loadPerTileState(nbt);
		// 修复 v14：反序列化基础离心机单流体槽（DRY：复用 deserializeFluidTank）
		deserializeFluidTank(provider, nbt);
		// 模块 3 Bug 1：反序列化输出槽/输入槽/能量槽（冗余备份，向后兼容：旧存档无此键时跳过）
		deserializeDropSlots(nbt, provider);
	}

	/**
	 * 保存自定义数据为 NBT（防御性 API）
	 * <br/>
	 * 说明：NeoForge 21.1.214 的 saveToItem 实际只调用 saveAdditional + collectComponents，
	 * 本方法保留供未来迁移/外部调用使用；实际掉落持久化由 saveAdditional 保证。
	 * 内容：PB 配方处理进度、PB 升级、AE2 per-tile 状态（含过滤器）、单流体槽与冗余槽位备份。
	 */
	@NotNull
	CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider) {
		CompoundTag nbt = new CompoundTag();
		save(nbt, provider);
		pbUpgradeHandler.saveCounts(nbt);
		pbUpgradeHandler.saveSlots(nbt, provider);
		ae2Handler.savePerTileState(nbt);
		// 修复 v14：序列化基础离心机单流体槽（DRY：复用 serializeFluidTank）
		CompoundTag fluidNbt = serializeFluidTank(provider);
		if (fluidNbt != null) {
			nbt.put(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID, fluidNbt);
		}
		// 模块 3 Bug 1：序列化输出槽/输入槽/能量槽到自定义 NBT 键（冗余备份）
		serializeDropSlots(nbt, provider);
		BlockEntity.addEntityType(nbt, tile.getType());
		return nbt;
	}

	// ===== 模块 3 Bug 1: 冗余槽位 NBT 序列化（DRY） =====

	/**
	 * 序列化输出槽/输入槽/能量槽到自定义 NBT 键
	 * <br/>
	 * 模块 3 Bug 1：镐子破坏离心机时，输出槽/输入槽/能量槽物品通过 collectComponents 已写入
	 * BLOCK_ENTITY_DATA，但作为冗余备份，额外保存到自定义 NBT 键，确保 collectComponents
	 * 不完整时（如部分槽位未注册到 InventorySlotHolder）数据仍可恢复。
	 * <p>
	 * 结构：
	 * <ul>
	 *   <li>{@link MekCentrifugeNbtKeys#NBT_KEY_DROP_OUTPUT_SLOTS}：ListTag，元素为
	 *       [主输出槽.serializeNBT, 副输出槽1.serializeNBT, 副输出槽2.serializeNBT]</li>
	 *   <li>{@link MekCentrifugeNbtKeys#NBT_KEY_DROP_INPUT_SLOT}：输入槽.serializeNBT</li>
	 *   <li>{@link MekCentrifugeNbtKeys#NBT_KEY_DROP_ENERGY_SLOT}：能量槽.serializeNBT</li>
	 * </ul>
	 *
	 * @param nbt      目标 NBT（追加键）
	 * @param provider 注册表访问器
	 */
	private void serializeDropSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		// 输出槽列表（主+副1+副2）
		ListTag outputList = new ListTag();
		outputList.add(tile.accessor().productivebeesgenesis$getOutputSlot().serializeNBT(provider));
		outputList.add(tile.slotManager().getSecondaryOutputSlot().serializeNBT(provider));
		outputList.add(tile.slotManager().getTertiaryOutputSlot().serializeNBT(provider));
		nbt.put(MekCentrifugeNbtKeys.NBT_KEY_DROP_OUTPUT_SLOTS, outputList);
		// 输入槽
		nbt.put(MekCentrifugeNbtKeys.NBT_KEY_DROP_INPUT_SLOT,
				tile.accessor().productivebeesgenesis$getInputSlot().serializeNBT(provider));
		// 能量槽
		nbt.put(MekCentrifugeNbtKeys.NBT_KEY_DROP_ENERGY_SLOT,
				tile.accessor().productivebeesgenesis$getEnergySlot().serializeNBT(provider));
	}

	/**
	 * 反序列化输出槽/输入槽/能量槽从自定义 NBT 键
	 * <br/>
	 * 模块 3 Bug 1：从冗余备份 NBT 恢复槽位内容。
	 * 向后兼容：旧存档（无此键）时跳过，不抛 NPE。
	 * <p>
	 * 注意：此方法会覆盖 MEK 标准 loadAdditional 已恢复的槽位内容。
	 * 由于冗余备份与 BLOCK_ENTITY_DATA 在同一时刻写入（saveCustomDataForItem），
	 * 数据源一致，覆盖不会导致状态不一致。
	 *
	 * @param nbt      源 NBT
	 * @param provider 注册表访问器
	 */
	private void deserializeDropSlots(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		// 输出槽列表（主+副1+副2）
		if (nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_DROP_OUTPUT_SLOTS, Tag.TAG_LIST)) {
			ListTag outputList = nbt.getList(MekCentrifugeNbtKeys.NBT_KEY_DROP_OUTPUT_SLOTS, Tag.TAG_COMPOUND);
			BasicInventorySlot[] outputSlots = {
					tile.accessor().productivebeesgenesis$getOutputSlot(),
					tile.slotManager().getSecondaryOutputSlot(),
					tile.slotManager().getTertiaryOutputSlot()
			};
			for (int i = 0; i < outputList.size() && i < outputSlots.length; i++) {
				outputSlots[i].deserializeNBT(provider, outputList.getCompound(i));
			}
		}
		// 输入槽
		if (nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_DROP_INPUT_SLOT, Tag.TAG_COMPOUND)) {
			tile.accessor().productivebeesgenesis$getInputSlot().deserializeNBT(
					provider, nbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_DROP_INPUT_SLOT));
		}
		// 能量槽
		if (nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_DROP_ENERGY_SLOT, Tag.TAG_COMPOUND)) {
			tile.accessor().productivebeesgenesis$getEnergySlot().deserializeNBT(
					provider, nbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_DROP_ENERGY_SLOT));
		}
	}

	// ===== 单流体槽序列化（DRY） =====

	/**
	 * 序列化基础离心机单流体槽内容
	 * <br/>
	 * 修复 v14：抽取自 saveAdditional 和 saveCustomDataForItem 的公共序列化逻辑，消除重复代码。
	 * 非空流体时返回包含 "Fluid" 子标签的 CompoundTag，空流体返回 null（避免空标签）。
	 * NBT 结构与 {@code ApiaryNbtSerializer.NBT_KEY_APIARY_FLUID} 对齐，保持跨机器一致性。
	 *
	 * @param provider 注册表访问器
	 * @return 包含流体内容的 CompoundTag，空流体返回 null
	 */
	@Nullable
	private CompoundTag serializeFluidTank(@NotNull HolderLookup.Provider provider) {
		FluidStack fluid = tile.fluidOutputTank().getFluid();
		if (fluid.isEmpty()) return null;
		CompoundTag fluidNbt = new CompoundTag();
		fluidNbt.put("Fluid", fluid.save(provider));
		return fluidNbt;
	}

	/**
	 * 反序列化基础离心机单流体槽内容
	 * <br/>
	 * 修复 v14：从 NBT 读取流体内容并插入到流体槽。
	 * 使用 {@code insert(..., AutomationType.INTERNAL)} 尊重当前罐容量，
	 * 与 {@code ApiaryNbtSerializer.loadApiaryState} 的流体恢复逻辑一致（DRY）。
	 * 向后兼容：旧存档无此字段时跳过。
	 *
	 * @param provider 注册表访问器
	 * @param nbt      包含流体内容的 NBT
	 */
	private void deserializeFluidTank(@NotNull HolderLookup.Provider provider, @NotNull CompoundTag nbt) {
		if (!nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID, Tag.TAG_COMPOUND)) return;
		CompoundTag fluidNbt = nbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_CENTRIFUGE_FLUID);
		if (!fluidNbt.contains("Fluid", Tag.TAG_COMPOUND)) return;
		FluidStack fluid = FluidStack.parseOptional(provider, fluidNbt.getCompound("Fluid"));
		if (!fluid.isEmpty()) {
			tile.fluidOutputTank().insert(fluid, Action.EXECUTE, AutomationType.INTERNAL);
		}
	}

	// ===== 容器同步器总入口 =====

	/**
	 * 注册所有容器同步器 — PB进度 + PB升级 + AE2开关
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#addContainerTrackers} 在调用 super 后委托。
	 */
	void addContainerTrackers(@NotNull MekanismContainer container) {
		addPbTrackers(container);
		addPbUpgradeTrackers(container);
		ae2Handler.addAe2Trackers(container);
	}

	// ===== 配置卡数据复制 =====

	/**
	 * 写入配置卡数据 — 添加PB升级数量和AE2 per-tile状态
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#writeSustainedData} 在调用 super 后委托。
	 */
	void writeSustainedData(@NotNull CompoundTag data) {
		PbConfigCardDataHelper.writePbUpgrades(data, pbUpgradeHandler.getPbUpgradeCounts(),
				PbConfigCardDataHelper.MachineType.CENTRIFUGE);
		ae2Handler.savePerTileState(data);
	}

	/**
	 * 从配置卡数据读取 — 恢复AE2 per-tile状态
	 * <br/>
	 * PB升级的粘贴需要消耗物品，在 {@link #setConfigurationData} 中处理。
	 */
	void readSustainedData(@NotNull CompoundTag data) {
		ae2Handler.loadPerTileState(data);
	}

	/**
	 * 设置配置卡数据 — 处理PB升级粘贴（含生存模式物品消耗）
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#setConfigurationData} 在调用 super 后委托。
	 */
	void setConfigurationData(@NotNull CompoundTag data, @Nullable Player player) {
		PbConfigCardDataHelper.readAndApplyPbUpgrades(data, player,
				pbUpgradeHandler::installPbUpgrade,
				this::clearAllPbUpgrades,
				PbConfigCardDataHelper.MachineType.CENTRIFUGE);
	}

	/**
	 * 清空所有已安装PB升级 — 供配置卡粘贴前调用
	 * <br/>
	 * 修复物品守恒：使用 removePbUpgrade 直接清空并返还物品栈列表，
	 * 由调用方（PbConfigCardDataHelper.readAndApplyPbUpgrades）注入玩家物品栏或掉落地面。
	 * 不再依赖 extractPbUpgradeByType（输出槽空间不足会截断物品）。
	 *
	 * @return 被清空的 PB 升级物品栈列表
	 */
	@NotNull
	private java.util.List<net.minecraft.world.item.ItemStack> clearAllPbUpgrades() {
		java.util.List<net.minecraft.world.item.ItemStack> dropped = new java.util.ArrayList<>();
		for (PbUpgradeType type : PbUpgradeType.values()) {
			if (!type.isBuiltin() && pbUpgradeHandler.getInstalledCount(type) > 0) {
				dropped.addAll(pbUpgradeHandler.removePbUpgrade(type, true));
			}
		}
		return dropped;
	}

	// ===== 工厂安装器升级数据 =====

	/**
	 * 构建升级数据 — 返回 {@link CentrifugeUpgradeData} 保存完整状态供工厂安装器升级时流转
	 * <br/>
	 * 委托 {@link CentrifugeUpgradeDataHelper#buildUpgradeData} 统一构建逻辑。
	 * 基础机器单进程，进度数组长度为1。PB 处理进度由本 handler 独立保存。
	 * <p>
	 * v13: 传入包装单流体槽的 IFluidTankHolder,修复基础离心机升级为工厂时内部流体丢失。
	 * 基础离心机的 {@link IExtendedFluidTank} 由 {@link MekCentrifugeSlotManager} 管理,
	 * 无独立 IFluidTankHolder 引用,此处用匿名实现包装暴露给序列化逻辑。
	 *
	 * @param provider       注册表访问器
	 * @param redstone       红石控制状态（protected 字段，由调用方从 tile 传入）
	 * @param controlType    红石控制模式
	 * @param operatingTicks 当前操作进度
	 * @param components     组件列表
	 * @return 包含完整离心机状态的升级数据
	 */
	@NotNull
	CentrifugeUpgradeData buildUpgradeData(@NotNull HolderLookup.Provider provider,
			boolean redstone, @NotNull RedstoneControl controlType,
			int operatingTicks, @NotNull List<ITileComponent> components) {
		List<mekanism.api.inventory.IInventorySlot> inputSlotList =
				Collections.singletonList(tile.accessor().productivebeesgenesis$getInputSlot());
		List<mekanism.api.inventory.IInventorySlot> outputSlotList = List.of(
				tile.accessor().productivebeesgenesis$getOutputSlot(),
				tile.slotManager().getSecondaryOutputSlot(),
				tile.slotManager().getTertiaryOutputSlot());
		// v13: 传入基础离心机的单流体槽 holder(包装 IExtendedFluidTank)
		// 修复流体丢失:基础离心机升级为工厂时保留内部流体
		IFluidTankHolder singleFluidHolder = new IFluidTankHolder() {
			@Override
			@NotNull
			public List<IExtendedFluidTank> getTanks(@Nullable Direction side) {
				return Collections.singletonList(tile.fluidOutputTank());
			}
		};
		return CentrifugeUpgradeDataHelper.buildUpgradeData(
				provider, redstone, controlType,
				tile.energyContainer(), new int[]{ operatingTicks },
				tile.accessor().productivebeesgenesis$getEnergySlot(),
				inputSlotList, outputSlotList,
				false, components,
				pbUpgradeHandler,
				ae2Handler.getStateHolder(),
				singleFluidHolder);
	}

	/**
	 * 应用升级数据 — 恢复 PB 升级、AE2 per-tile 设置和深拷贝槽位内容
	 * <br/>
	 * 由 {@link TileEntityMekCentrifuge#parseUpgradeData} 在调用 super 后委托。
	 * 仅对 {@link CentrifugeUpgradeData} 类型执行恢复，其他类型由 super 处理。
	 * <p>
	 * 模块 3 Bug 2：传递新方块（基础离心机自身）的输入/输出/能量槽列表给 helper，
	 * 由 helper 从升级数据中的深拷贝字段恢复槽位内容。
	 * 原理：旧方块的 getUpgradeData 调用 saveAllItemsForDrop 已清空槽位，
	 * super.parseUpgradeData 通过引用列表读取到空栈，必须从深拷贝字段覆盖恢复。
	 * <p>
	 * 目标槽位列表与 {@link #buildUpgradeData} 中的源槽位列表结构一致：
	 * <ul>
	 *   <li>输入槽：singletonList(inputSlot)</li>
	 *   <li>输出槽：[主输出槽, 副输出槽1, 副输出槽2]</li>
	 *   <li>能量槽：energySlot</li>
	 * </ul>
	 */
	void applyUpgradeData(@NotNull HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		if (upgradeData instanceof CentrifugeUpgradeData data) {
			// 构建新方块（基础离心机自身）的目标槽位列表
			List<IInventorySlot> targetInputSlots =
					Collections.singletonList(tile.accessor().productivebeesgenesis$getInputSlot());
			List<IInventorySlot> targetOutputSlots = List.of(
					tile.accessor().productivebeesgenesis$getOutputSlot(),
					tile.slotManager().getSecondaryOutputSlot(),
					tile.slotManager().getTertiaryOutputSlot());
			IInventorySlot targetEnergySlot = tile.accessor().productivebeesgenesis$getEnergySlot();
			// 修复（升级目标缺口）：传入基础离心机自身的单流体槽 holder（与 buildUpgradeData 对称），
			// 使源数据中的多流体槽 NBT 在目标为 SINGLE 模式时能合并进单流体槽而非被丢弃
			IFluidTankHolder singleFluidHolder = new IFluidTankHolder() {
				@Override
				@NotNull
				public List<IExtendedFluidTank> getTanks(@Nullable Direction side) {
					return Collections.singletonList(tile.fluidOutputTank());
				}
			};
			CentrifugeUpgradeDataHelper.applyUpgradeData(
					provider, data,
					pbUpgradeHandler,
					ae2Handler.getStateHolder(),
					singleFluidHolder,
					targetInputSlots,
					targetOutputSlots,
					targetEnergySlot);
		}
	}

	/**
	 * 保存全部数据后清空所有槽位 — 防止 setRemoved 触发 Ejector 重复 popResource
	 * <br/>
	 * 模块 3 Bug 1：镐子破坏离心机时，{@code getDrops} 已将所有数据保存到掉落物的
	 * BLOCK_ENTITY_DATA 组件后调用本方法，清空所有槽位，使 {@code setRemoved} 触发的
	 * Ejector 组件检查到槽位为空，不执行 {@code popResource}，避免物品复制 bug。
	 * <p>
	 * 模块 3 Bug 2：工厂安装器升级时，{@code getUpgradeData} 已通过深拷贝保存槽位内容到
	 * {@link CentrifugeUpgradeData} 后调用本方法，清空旧方块槽位，防止 Ejector 重复弹出。
	 * <p>
	 * 清空范围：主输出槽、副输出槽1、副输出槽2、输入槽、能量槽、流体罐、PB 升级输入/输出槽。
	 * 性能：直接清空槽位，不需要先序列化（数据已通过 saveCustomDataForItem 或深拷贝保存）。
	 */
	void saveAllItemsForDrop() {
		// 异常隔离：每组清空操作独立 try-catch，防止单点异常导致后续槽位未清空（Ejector 仍 popResource）
		try {
			// 主输出槽 + 副输出槽1 + 副输出槽2
			tile.accessor().productivebeesgenesis$getOutputSlot().setStack(ItemStack.EMPTY);
			tile.slotManager().getSecondaryOutputSlot().setStack(ItemStack.EMPTY);
			tile.slotManager().getTertiaryOutputSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			DevLog.error("离心机 saveAllItemsForDrop: 清空输出槽失败", e);
		}
		try {
			// 输入槽（蜜脾槽）
			tile.accessor().productivebeesgenesis$getInputSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			DevLog.error("离心机 saveAllItemsForDrop: 清空输入槽失败", e);
		}
		try {
			// 能量槽
			tile.accessor().productivebeesgenesis$getEnergySlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			DevLog.error("离心机 saveAllItemsForDrop: 清空能量槽失败", e);
		}
		try {
			// 流体罐（extract 清空所有流体，使用 MEK 3 参数版本与 insert 对称）
			// IExtendedFluidTank.drain 只有 2 参数版本，extract 提供 Action + AutomationType 3 参数版本
			tile.slotManager().getFluidOutputTank().extract(Integer.MAX_VALUE, Action.EXECUTE, AutomationType.INTERNAL);
		} catch (RuntimeException e) {
			DevLog.error("离心机 saveAllItemsForDrop: 清空流体罐失败", e);
		}
		try {
			// PB 升级输入/输出槽
			pbUpgradeHandler.getInputSlot().setStack(ItemStack.EMPTY);
			pbUpgradeHandler.getOutputSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			DevLog.error("离心机 saveAllItemsForDrop: 清空 PB 升级槽失败", e);
		}
		tile.setChanged();
	}
}
