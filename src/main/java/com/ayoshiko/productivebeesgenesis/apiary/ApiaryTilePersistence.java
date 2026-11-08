package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.PbConfigCardDataHelper;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 机械蜂箱持久化与配置卡桥接（纯静态，无状态）
 * <br/>
 * 从 {@link TileEntityMekApiary} 拆分而来，职责（SRP）：NBT 保存/加载、
 * 升级数据构建/应用、配置卡数据读写、破坏掉落前槽位清空。
 * 所有方法通过方块实体的包私有访问器协作，不直接持有其内部状态。
 */
final class ApiaryTilePersistence {

	private ApiaryTilePersistence() {
	}

	/** 保存附加 NBT — super.saveAdditional 已由调用方执行 */
	static void saveAdditional(TileEntityMekApiary tile, @NotNull CompoundTag nbt,
			@NotNull HolderLookup.Provider provider) {
		tile.nbtSerializer().saveApiaryState(nbt, provider);
		tile.ae2HostAdapter().saveNodeNBT(nbt);
		tile.ae2HostAdapter().savePerTileState(nbt);
		tile.ae2HostAdapter().savePendingItems(nbt);
	}

	/**
	 * 保存到物品的自定义数据（防御性 API）
	 * <br/>
	 * 说明：NeoForge 21.1.214 的 BlockEntity.saveToItem 实际只调用 saveAdditional
	 * + collectComponents（ATTACHED_ITEMS 组件），不调用本方法；
	 * 完整掉落持久化由 saveAdditional 保证。本方法保留供未来迁移/外部调用使用。
	 */
	@NotNull
	static CompoundTag saveCustomDataForItem(TileEntityMekApiary tile, @NotNull HolderLookup.Provider provider) {
		CompoundTag nbt = tile.nbtSerializer().saveCustomData(provider);
		tile.ae2HostAdapter().savePendingItems(nbt);
		return nbt;
	}

	/** 加载附加 NBT — super.loadAdditional 已由调用方执行 */
	static void loadAdditional(TileEntityMekApiary tile, @NotNull CompoundTag nbt,
			@NotNull HolderLookup.Provider provider) {
		tile.nbtSerializer().loadApiaryState(nbt, provider);
		tile.ae2HostAdapter().loadNodeNBT(nbt);
		tile.ae2HostAdapter().loadPerTileState(nbt);
		tile.ae2HostAdapter().loadPendingItems(nbt);
		MekCentrifugeEnergyScaling.normalizeCapacity(tile);
	}

	/** 构建升级数据（构建后立即清空旧方块所有槽位，防止 setRemoved 重复掉落） */
	@org.jetbrains.annotations.Nullable
	static ApiaryUpgradeData getUpgradeData(TileEntityMekApiary tile, HolderLookup.Provider provider) {
		if (tile.upgradeRecovery().blocked()
				|| !com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.canUpgrade(tile)) return null;
		ApiaryUpgradeData data;
		try {
			data = tile.nbtSerializer().buildUpgradeData(
					provider, tile.getRedstoneControl(), tile.getSortingForUpgradeData());
			data.resourceUpgrade = com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost.prepareUpgrade(tile);
		} catch (RuntimeException | LinkageError failure) {
			ProductiveBeesGenesis.LOGGER.error("蜂箱升级预检或来源编码失败，原机未清空：{}", tile.getBlockPos(), failure);
			return null;
		}
		tile.upgradeRecovery().begin(data.request, data.sourceSnapshot, "source_clear");
		try {
			clearForUpgrade(tile, provider);
			tile.upgradeRecovery().sourceCleared();
			return data;
		} catch (RuntimeException | LinkageError failure) {
			tile.upgradeRecovery().fail(failure);
			ProductiveBeesGenesis.LOGGER.error("蜂箱升级源端清空失败，完整快照已保管：{}", tile.getBlockPos(), failure);
			return null;
		}
	}

	/** 只有未识别数据才交父类；已保管和完整应用保持不同结果。 */
	static ApiaryNbtSerializer.UpgradeResult applyUpgradeData(TileEntityMekApiary tile, HolderLookup.Provider provider,
			@NotNull IUpgradeData upgradeData) {
		return tile.nbtSerializer().applyUpgradeData(provider, upgradeData);
	}

	/** 安装器源端不吞清空异常；中断后保管记录继续拥有尚未明确转出的部分。 */
	private static void clearForUpgrade(TileEntityMekApiary tile, HolderLookup.Provider provider) {
		tile.getGeneTreatRestock().clearAfterTransfer();
		for (BeeSlot slot : tile.getBeeSlots()) {
			slot.clear();
		}
		for (FeederInventorySlot slot : tile.feederSlotManager.getFeederInventorySlots()) {
			slot.setStack(ItemStack.EMPTY);
		}
		for (var slot : tile.getInventorySlots(null)) {
			slot.setStack(ItemStack.EMPTY);
		}
		tile.getFluidTank().setEmpty();
		tile.setPendingHoneyFluid(0, null);
		tile.getOutputBuffer().clear();
		tile.productionState().load(provider, new CompoundTag());
		tile.setSelectedBeeSlot(-1);
		tile.setChanged();
	}

	/**
	 * 保存全部数据后清空所有槽位（模块 3 Bug 1 + Bug 2）
	 * <br/>
	 * 供 {@link TileEntityMekApiary#saveAllItemsForDrop}（镐子破坏/扳手拆卸）和
	 * {@link #getUpgradeData}（ItemTierInstaller 升级）在保存 BLOCK_ENTITY_DATA /
	 * 升级数据后调用，防止 {@code setRemoved} 触发 Ejector 组件 {@code popResource}
	 * 重复掉落物品到世界。
	 * <p>
	 * 清空范围：蜜蜂槽、喂食槽、PB升级槽（输入+输出）、产物输出槽、蜂笼输入槽、
	 * 蜂笼输出槽、基因小食槽、能量槽、流体罐、产物缓冲区（outputBuffer）、选中槽位。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>性能：直接清空槽位，不重复序列化（数据已通过 BLOCK_ENTITY_DATA / 升级数据保存）</li>
	 *   <li>安全：清空所有可能持有物品的槽位，避免遗漏导致 Ejector 重复 popResource</li>
	 *   <li>异常处理：单点异常不影响其他槽位清空（防御性 try-catch）</li>
	 * </ul>
	 */
	static void saveAllItemsForDrop(TileEntityMekApiary tile) {
		tile.getGeneTreatRestock().clearAfterTransfer();
		// 蜜蜂槽数组清空（BeeSlot.clear() 重置全部字段并标记 dirty）
		try {
			for (BeeSlot slot : tile.slotManager().getBeeSlots()) {
				slot.clear();
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空蜜蜂槽异常", e);
		}
		// 喂食槽清空
		try {
			for (FeederInventorySlot slot : tile.feederSlotManager.getFeederInventorySlots()) {
				slot.setStack(ItemStack.EMPTY);
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空喂食槽异常", e);
		}
		// PB 升级输入/输出槽清空
		try {
			tile.pbUpgradeHandler().getInputSlot().setStack(ItemStack.EMPTY);
			tile.pbUpgradeHandler().getOutputSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空 PB 升级槽异常", e);
		}
		// 产物输出槽清空
		try {
			for (BasicInventorySlot slot : tile.slotManager().getOutputSlots()) {
				slot.setStack(ItemStack.EMPTY);
			}
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空产物输出槽异常", e);
		}
		// 蜂笼输入/输出槽清空
		try {
			tile.slotManager().getCageInSlot().setStack(ItemStack.EMPTY);
			tile.slotManager().getCageOutSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空蜂笼槽异常", e);
		}
		try {
			tile.slotManager().getGeneTreatSlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空基因小食槽异常", e);
		}
		// 能量槽清空
		try {
			tile.slotManager().getEnergySlot().setStack(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空能量槽异常", e);
		}
		// 流体罐清空（setEmpty 内部调用 setStack(FluidStack.EMPTY)，触发 onContentsChanged）
		try {
			tile.slotManager().getFluidTank().setEmpty();
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空流体罐异常", e);
		}
		// 产物溢出缓冲区清空（不掉落，仅清空内存引用）
		try {
			tile.getOutputBuffer().clear();
		} catch (RuntimeException e) {
			ProductiveBeesGenesis.LOGGER.warn("saveAllItemsForDrop: 清空产物缓冲区异常", e);
		}
		// 选中蜜蜂槽位重置为未选择
		tile.setSelectedBeeSlot(-1);
		tile.setChanged();
	}

	/** 写入配置卡数据 — 追加PB升级、AE2状态和产物路由开关到 MEK 配置卡 */
	static void writeSustainedData(TileEntityMekApiary tile, @NotNull HolderLookup.Provider provider,
			@NotNull CompoundTag data) {
		PbConfigCardDataHelper.writePbUpgrades(data, tile.pbUpgradeHandler().getPbUpgradeCounts(),
				PbConfigCardDataHelper.MachineType.APIARY);
		PbConfigCardDataHelper.writeAe2PerTileState(data,
				tile.ae2HostAdapter().isAeItemOutputEnabled(), tile.ae2HostAdapter().isAeFluidOutputEnabled());
		data.putBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_EJECT, tile.isDirectEjectEnabled());
		data.putBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_AE_OUTPUT, tile.isDirectAeOutputEnabled());
		data.putBoolean(ApiaryNbtSerializer.NBT_KEY_CENTRIFUGE_PRIORITY, tile.isCentrifugePriorityEnabled());
		// 喂食槽转化开关：存档（writeApiaryStateTo）、扳手拆卸（saveCustomData）与
		// 等级升级（ApiaryUpgradeData.feederConversionEnabled）三条路径都已持久化，
		// 唯独配置卡漏了，导致复制配置时该开关被静默重置为默认关闭。
		data.putBoolean(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION, tile.isFeederConversionEnabled());
		data.putBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_CONTAINER_OUTPUT,
				tile.isDirectContainerOutputEnabled());
	}

	/** 从配置卡读取 — 恢复AE2状态和产物路由开关（PB升级粘贴在 setConfigurationData 中处理） */
	static void readSustainedData(TileEntityMekApiary tile, @NotNull HolderLookup.Provider provider,
			@NotNull CompoundTag data) {
		boolean[] ae2State = PbConfigCardDataHelper.readAe2PerTileState(data);
		if (ae2State != null) {
			tile.ae2HostAdapter().setAeItemOutputEnabled(ae2State[0]);
			tile.ae2HostAdapter().setAeFluidOutputEnabled(ae2State[1]);
		}
		if (data.contains(ApiaryNbtSerializer.NBT_KEY_DIRECT_EJECT)) {
			tile.setDirectEjectEnabled(data.getBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_EJECT));
		}
		if (data.contains(ApiaryNbtSerializer.NBT_KEY_DIRECT_AE_OUTPUT)) {
			tile.setDirectAeOutputEnabled(data.getBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_AE_OUTPUT));
		}
		if (data.contains(ApiaryNbtSerializer.NBT_KEY_CENTRIFUGE_PRIORITY)) {
			tile.setCentrifugePriorityEnabled(data.getBoolean(ApiaryNbtSerializer.NBT_KEY_CENTRIFUGE_PRIORITY));
		}
		// 与 writeSustainedData 对称：旧配置卡无此键时保持当前值，不强制关闭。
		if (data.contains(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION)) {
			tile.setFeederConversionEnabled(data.getBoolean(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION));
		}
		if (data.contains(ApiaryNbtSerializer.NBT_KEY_DIRECT_CONTAINER_OUTPUT)) {
			tile.setDirectContainerOutputEnabled(
					data.getBoolean(ApiaryNbtSerializer.NBT_KEY_DIRECT_CONTAINER_OUTPUT));
		}
	}

	/** 设置配置卡数据；返回 false 时宿主保持全部配置。 */
	static boolean setConfigurationData(TileEntityMekApiary tile,
			@Nullable net.minecraft.world.entity.player.Player player, @NotNull CompoundTag data) {
		return tile.pbUpgradeHandler().applyConfiguration(data, player);
	}


	/** 保存AE2 per-tile状态到NBT — 供 ApiaryNbtSerializer 扳手拆卸持久化调用 */
	static void saveAe2PerTileState(TileEntityMekApiary tile, CompoundTag nbt) {
		tile.ae2HostAdapter().savePerTileState(nbt);
	}
}
