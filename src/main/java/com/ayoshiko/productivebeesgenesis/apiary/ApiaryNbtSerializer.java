package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.tile.component.ITileComponent;
import mekanism.common.upgrade.IUpgradeData;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.util.DevLog;

/**
 * 蜂箱 NBT 序列化器
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分，单一职责管理蜂箱数据的持久化与升级数据流转：
 * <ul>
 *   <li>{@link #saveApiaryState} / {@link #loadApiaryState} — 存档读写（蜜蜂槽/喂食槽/PB升级/选中槽）</li>
 *   <li>{@link #saveCustomData} — 扳手拆卸持久化（BLOCK_ENTITY_DATA 组件）</li>
 *   <li>{@link #buildUpgradeData} — ItemTierInstaller 升级时保存完整状态</li>
 *   <li>{@link #applyUpgradeData} — 升级后恢复状态到新方块</li>
 * </ul>
 * <p>
 * 通过组合关系持有 {@link TileEntityMekApiary} 引用。super.saveAdditional/loadAdditional
 * 由调用方（@Override 方法）负责，本类仅处理蜂箱特有数据。
 * <p>
 * 线程安全：序列化在服务端主线程执行。
 */
class ApiaryNbtSerializer {

	/** NBT key — 选中蜜蜂槽位 */
	static final String NBT_KEY_SELECTED_BEE = "productivebeesgenesis_selected_bee";

	/**
	 * NBT key — 蜂箱内部流体罐内容
	 * <br/>
	 * 修复 v14：存档保存和扳手拆卸时持久化蜂箱流体罐内容。
	 * 结构与 {@link #buildUpgradeData} 中的 fluidNbt 对齐，均使用 "Fluid" 子标签，
	 * 保持 DRY（同一序列化格式在存档/拆卸/升级三个路径复用）。
	 */
	static final String NBT_KEY_APIARY_FLUID = "productivebeesgenesis_apiary_fluid";

	/** NBT key — schema 版本字段名，用于版本化数据完整性校验 */
	private static final String NBT_KEY_SCHEMA_VERSION = "productivebeesgenesis_nbt_schema_version";

	/** 当前 NBT schema 版本（向后兼容：旧存档无此字段时按 version=1 处理） */
	private static final int NBT_SCHEMA_VERSION = 1;

	/** 所属方块实体 */
	private final TileEntityMekApiary tile;

	/**
	 * 构造蜂箱 NBT 序列化器
	 *
	 * @param tile 所属方块实体
	 */
	ApiaryNbtSerializer(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	// ===== 存档读写 =====

	/**
	 * 保存蜂箱特有状态到 NBT（不含 super 和 AE2 节点）
	 * <br/>
	 * 由 {@link TileEntityMekApiary#saveAdditional} 在调用 super 后调用。
	 */
	void saveApiaryState(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		writeApiaryStateTo(nbt, provider);
	}

	/**
	 * 将蜂箱特有状态写入 NBT — saveApiaryState 和 saveCustomData 的共用逻辑（DRY）
	 * <br/>
	 * 修复 v14：追加流体罐序列化，确保存档保存和扳手拆卸时蜂箱内部流体不丢失。
	 * 流体 NBT 结构（{@code { "Fluid": FluidStack }）与 {@link #buildUpgradeData} 对齐，
	 * 保持三路径（存档/拆卸/升级）序列化格式一致。
	 *
	 * @param nbt      目标 NBT 标签
	 * @param provider 注册表访问器
	 */
	private void writeApiaryStateTo(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		nbt.putInt(NBT_KEY_SCHEMA_VERSION, NBT_SCHEMA_VERSION);
		tile.getSlotManager().saveBeeSlots(nbt);
		tile.feederSlotManager.saveFeederSlots(nbt, provider);
		tile.pbUpgradeHandler.savePbUpgradeCounts(nbt);
		tile.pbUpgradeHandler.saveSlots(nbt, provider);
		nbt.putInt(NBT_KEY_SELECTED_BEE, tile.getSelectedBeeSlot());
		// 修复 v14：序列化流体罐内容（非空时写入，避免空标签）
		FluidStack fluid = tile.getFluidTank().getFluid();
		if (!fluid.isEmpty()) {
			CompoundTag fluidNbt = new CompoundTag();
			fluidNbt.put("Fluid", fluid.save(provider));
			nbt.put(NBT_KEY_APIARY_FLUID, fluidNbt);
		}
	}

	/**
	 * 从 NBT 加载蜂箱特有状态（不含 super 和 AE2 节点）
	 * <br/>
	 * 由 {@link TileEntityMekApiary#loadAdditional} 在调用 super 后调用。
	 * 修复 v14：追加流体罐反序列化，恢复存档/扳手拆卸时保存的流体内容。
	 * 使用 {@code insert(..., AutomationType.INTERNAL)} 尊重当前罐容量，
	 * 与 {@link #applyUpgradeData} 中的流体恢复逻辑一致（DRY）。
	 * 修复 v14 loadSlots/loadCounts 顺序：必须先 loadSlots 恢复槽位,再 loadPbUpgradeCounts 恢复数量。
	 * 原理:loadPbUpgradeCounts 内部 applyCountWithLimit 在数量超过配置上限时,会将超出部分注入输出槽。
	 * 若 loadSlots 未先执行,输出槽为空,注入的超出部分会被后续 loadSlots 覆盖,导致升级物品凭空消失。
	 */
	void loadApiaryState(@NotNull CompoundTag nbt, @NotNull HolderLookup.Provider provider) {
		int schemaVersion = nbt.getInt(NBT_KEY_SCHEMA_VERSION);
		if (schemaVersion < 1) {
			schemaVersion = 1; // 向后兼容：旧存档无此字段时按 version=1 处理
		}
		tile.getSlotManager().loadBeeSlots(nbt);
		tile.feederSlotManager.loadFeederSlots(nbt, provider);
		tile.pbUpgradeHandler.loadSlots(nbt, provider);
		tile.pbUpgradeHandler.loadPbUpgradeCounts(nbt, provider);
		// 边界检查：读取的选中槽位超出当前蜂箱容量时重置为未选择（参考 applyUpgradeData 第 226-231 行）
		int selectedBeeSlot = nbt.getInt(NBT_KEY_SELECTED_BEE);
		int maxBeeSlot = tile.getBeeSlotCount();
		if (selectedBeeSlot < -1 || selectedBeeSlot >= maxBeeSlot) {
			selectedBeeSlot = -1;
		}
		tile.setSelectedBeeSlot(selectedBeeSlot);
		// 修复 v14：反序列化流体罐内容（向后兼容：旧存档无此字段时跳过）
		if (nbt.contains(NBT_KEY_APIARY_FLUID, Tag.TAG_COMPOUND)) {
			CompoundTag fluidNbt = nbt.getCompound(NBT_KEY_APIARY_FLUID);
			if (fluidNbt.contains("Fluid", Tag.TAG_COMPOUND)) {
				FluidStack fluid = FluidStack.parseOptional(provider, fluidNbt.getCompound("Fluid"));
				if (!fluid.isEmpty()) {
					tile.getFluidTank().insert(fluid, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}
		}
	}

	// ===== 扳手拆卸持久化 =====

	/**
	 * 保存自定义数据为 NBT — 供扳手拆卸持久化使用（Bug 6）
	 * <br/>
	 * 仅保存蜂箱特有数据，标准 MEK 数据由 collectComponents 通过 DataComponents 流转。
	 * 放置时通过 BLOCK_ENTITY_DATA 组件自动调用 loadAdditional 恢复。
	 * <p>
	 * Bug 4修复：BLOCK_ENTITY_DATA 组件要求 NBT 顶层包含 id 字段（方块实体类型注册键），
	 * 通过 BlockEntity.addEntityType 添加。
	 *
	 * @param provider 注册表访问器
	 * @return 包含自定义数据的 NBT
	 */
	@NotNull
	CompoundTag saveCustomData(@NotNull HolderLookup.Provider provider) {
		CompoundTag nbt = new CompoundTag();
		writeApiaryStateTo(nbt, provider);
		// 保存 AE2 per-tile 输出开关，避免扳手拆卸后丢失用户设置
		tile.saveAe2PerTileState(nbt);
		// Bug 4修复：在NBT顶层添加方块实体类型注册键id字段，通过CODEC_WITH_ID验证
		BlockEntity.addEntityType(nbt, tile.getType());
		return nbt;
	}

	// ===== ItemTierInstaller 升级数据 =====

	/**
	 * 构建升级数据 — 保存蜂箱完整状态供 ItemTierInstaller 升级时流转
	 * <br/>
	 * 返回 {@link ApiaryUpgradeData}（非标准 MachineUpgradeData），
	 * 确保蜜蜂槽、喂食槽、PB升级、流体罐、蜂笼输出槽等蜂箱特有状态不丢失。
	 * <p>
	 * sorting 参数由调用方通过模板方法 {@link TileEntityMekApiary#getSortingForUpgradeData}
	 * 提供：基础版蜂箱传 false，工厂版传 {@code isSorting()}，避免工厂子类重复实现本方法。
	 *
	 * @param provider 注册表访问器
	 * @param redstone 红石控制状态（父类 protected 字段，boolean 类型）
	 * @param sorting  排序开关状态（基础版为 false，工厂版为 isSorting()）
	 * @return 包含完整蜂箱状态的升级数据
	 */
	@NotNull
	ApiaryUpgradeData buildUpgradeData(HolderLookup.Provider provider, boolean redstone, boolean sorting) {
		List<mekanism.api.inventory.IInventorySlot> inputSlots =
				Collections.singletonList(tile.getCageInSlot());
		List<mekanism.api.inventory.IInventorySlot> outputSlots = new ArrayList<>(tile.getOutputSlots());

		CompoundTag beeSlotsNbt = new CompoundTag();
		tile.getSlotManager().saveBeeSlots(beeSlotsNbt);

		CompoundTag feederSlotsNbt = new CompoundTag();
		tile.feederSlotManager.saveFeederSlots(feederSlotsNbt, provider);

		CompoundTag pbUpgradeCountsNbt = new CompoundTag();
		tile.pbUpgradeHandler.savePbUpgradeCounts(pbUpgradeCountsNbt);

		CompoundTag pbUpgradeInputNbt = tile.getPbUpgradeInputSlot().serializeNBT(provider);
		CompoundTag pbUpgradeOutputNbt = tile.getPbUpgradeOutputSlot().serializeNBT(provider);

		CompoundTag fluidNbt = new CompoundTag();
		FluidStack fluid = tile.getFluidTank().getFluid();
		if (!fluid.isEmpty()) {
			fluidNbt.put("Fluid", fluid.save(provider));
		}

		// 蜂笼输出槽序列化（独立于产物输出槽）
		CompoundTag cageOutSlotNbt = tile.getCageOutSlot().serializeNBT(provider);

		// 读取 AE2 per-tile 输出开关（AE2 未加载时默认 false，避免保留无意义的开关状态）
		boolean aeItemOutputEnabled = Ae2IntegrationLoader.isAe2Loaded()
				&& tile.productivebeesgenesis$isAeItemOutputEnabled();
		boolean aeFluidOutputEnabled = Ae2IntegrationLoader.isAe2Loaded()
				&& tile.productivebeesgenesis$isAeFluidOutputEnabled();

		return new ApiaryUpgradeData(provider, redstone, tile.getControlType(),
				tile.getEnergyContainer(), new int[]{tile.getOperatingTicks()}, tile.getEnergySlot(),
				inputSlots, outputSlots, sorting, tile.getComponents(),
				beeSlotsNbt, feederSlotsNbt, pbUpgradeCountsNbt,
				pbUpgradeInputNbt, pbUpgradeOutputNbt,
				fluidNbt, cageOutSlotNbt, tile.getSelectedBeeSlot(),
				aeItemOutputEnabled, aeFluidOutputEnabled);
	}

	/**
	 * 应用升级数据 — 将旧方块状态恢复到新蜂箱
	 * <br/>
	 * 支持 {@link ApiaryUpgradeData}（蜂箱间升级/降级/替换），返回 true 表示已处理。
	 * 其他类型返回 false，由调用方委托父类处理。
	 * <p>
	 * 修复 HIGH-7: 包裹 try-catch 防止单点异常导致整体崩溃。
	 * 失败时记录 ERROR 日志并保留 upgradeData 对象引用（toString）便于管理员手动恢复。
	 * 返回 true 以避免调用方 super.parseUpgradeData 覆盖已恢复的部分字段。
	 * <p>
	 * 修复 MEDIUM-2: 通过模板方法 {@link TileEntityMekApiary#setSortingFromUpgradeData}
	 * 显式恢复 SORTING 字段。基础蜂箱为 no-op（无 sorting 字段），工厂版重写实际设置 sorting。
	 *
	 * @param provider    注册表访问器
	 * @param upgradeData 升级数据
	 * @return true 如果数据已处理（含部分恢复），false 表示未识别（调用方应委托父类）
	 */
	boolean applyUpgradeData(HolderLookup.Provider provider, @NotNull IUpgradeData upgradeData) {
		if (!(upgradeData instanceof ApiaryUpgradeData data)) return false;

		try {
			tile.setRedstoneControl(data.redstone);
			tile.setControlType(data.controlType);
			tile.getEnergyContainer().setEnergy(data.energyContainer.getEnergy());
			tile.getEnergySlot().setStack(data.energySlot.getStack().copy());
			if (data.progress.length > 0) {
				tile.callSetOperatingTicks(data.progress[0]);
			}
			if (!data.inputSlots.isEmpty()) {
				tile.getCageInSlot().deserializeNBT(provider, data.inputSlots.get(0).serializeNBT(provider));
			}
			List<BasicInventorySlot> currentOutputs = tile.getOutputSlots();
			for (int i = 0; i < data.outputSlots.size() && i < currentOutputs.size(); i++) {
				// .copy() 防止共享 ItemStack 引用导致新旧方块状态联动
				currentOutputs.get(i).setStack(data.outputSlots.get(i).getStack().copy());
			}
			for (ITileComponent component : tile.getComponents()) {
				component.read(data.components, provider);
			}
			// 恢复蜂箱特有数据
			// 修复 v14 loadSlots/loadCounts 顺序：必须先恢复槽位,再恢复数量。
			// 原理:loadPbUpgradeCounts 内部 applyCountWithLimit 在数量超过配置上限时,会将超出部分注入输出槽。
			// 若输出槽未先恢复,注入的超出部分会被后续 deserializeNBT 覆盖,导致升级物品凭空消失。
			tile.getSlotManager().loadBeeSlots(data.beeSlotsNbt);
			tile.feederSlotManager.loadFeederSlots(data.feederSlotsNbt, provider);
			tile.getPbUpgradeInputSlot().deserializeNBT(provider, data.pbUpgradeInputNbt);
			tile.getPbUpgradeOutputSlot().deserializeNBT(provider, data.pbUpgradeOutputNbt);
			tile.pbUpgradeHandler.loadPbUpgradeCounts(data.pbUpgradeCountsNbt, provider);
			// 恢复流体罐（使用 insert 尊重当前罐容量）
			if (data.fluidNbt.contains("Fluid", Tag.TAG_COMPOUND)) {
				FluidStack fluid = FluidStack.parseOptional(provider, data.fluidNbt.getCompound("Fluid"));
				if (!fluid.isEmpty()) {
					tile.getFluidTank().insert(fluid, Action.EXECUTE, AutomationType.INTERNAL);
				}
			}
			// 恢复蜂笼输出槽
			tile.getCageOutSlot().deserializeNBT(provider, data.cageOutSlotNbt);
			// 恢复选中蜜蜂槽（边界检查，超出当前槽位数量时重置为未选择）
			int maxBeeSlot = tile.getBeeSlotCount();
			if (data.selectedBeeSlot >= 0 && data.selectedBeeSlot < maxBeeSlot) {
				tile.setSelectedBeeSlot(data.selectedBeeSlot);
			} else {
				tile.setSelectedBeeSlot(-1);
			}
			// 恢复 AE2 per-tile 输出开关（AE2 未加载时跳过，新方块使用默认值 true）
			if (Ae2IntegrationLoader.isAe2Loaded()) {
				tile.productivebeesgenesis$setAeItemOutputEnabled(data.aeItemOutputEnabled);
				tile.productivebeesgenesis$setAeFluidOutputEnabled(data.aeFluidOutputEnabled);
			}
			// 修复 MEDIUM-2: 显式恢复 SORTING 字段（模板方法,基础蜂箱为 no-op,工厂版重写设置 sorting）
			tile.setSortingFromUpgradeData(data.sorting);
		} catch (RuntimeException e) {
			// 修复 HIGH-7: 失败时记录 ERROR 日志并保留 upgradeData 对象引用,便于管理员手动恢复
			// 返回 true 以避免 super.parseUpgradeData 覆盖已恢复的部分字段（部分恢复比全覆盖更安全）
			DevLog.error("蜂箱升级数据应用失败,upgradeData=" + upgradeData + ",新方块可能处于部分恢复状态", e);
		}
		return true;
	}
}
