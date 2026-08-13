package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.mixin.accessor.TileEntityFactoryAccessor;
import java.util.List;
import java.util.function.Consumer;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.inventory.slot.EnergyInventorySlot;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
	 * Sorting state / upgrade-data application / bulk PB upgrade install support,
	 * split from {@link AbstractMekCentrifugeFactory} (SRP).
	 */
final class AbstractMekCentrifugeFactoryStateSupport {

	private AbstractMekCentrifugeFactoryStateSupport() {
	}

	/** 按钮显示与 ME/EME 一致：始终返回 sorting 字段实际值，不因 AE2 拉取而锁死。 */
	static boolean isSorting(@NotNull AbstractMekCentrifugeFactory factory) {
		return ((TileEntityFactoryAccessor) factory).productivebeesgenesis$getSorting();
	}

	/**
	 * 应用升级数据 — 先委托父类恢复标准字段，再恢复PB升级、AE2设置、多流体槽和深拷贝槽位内容
	 * <br/>
	 * 模块 3 Bug 2：传递新方块（本工厂）的输入槽/输出槽/能量槽给 helper，
	 * 由 helper 从升级数据深拷贝字段覆盖恢复（super.parseUpgradeData 通过引用列表读取到空栈）。
	 * inputSlots/outputSlots 来自父类 TileEntityItemToItemFactory 字段；
	 * energySlot 通过 TileEntityFactoryAccessor 访问（与 getUpgradeData 一致）。
	 */
	static void parseUpgradeData(@NotNull AbstractMekCentrifugeFactory factory, @NotNull HolderLookup.Provider provider,
			@NotNull IUpgradeData upgradeData, @Nullable List<IInventorySlot> inputSlots,
			@Nullable List<IInventorySlot> outputSlots, @NotNull Consumer<IUpgradeData> superParse) {
		EnergyInventorySlot energySlot = ((TileEntityFactoryAccessor) factory).productivebeesgenesis$getEnergySlot();
		CentrifugeFactoryCommonLogic.parseUpgradeData(provider, upgradeData, factory.pbUpgradeDelegate,
				factory.productivebeesgenesis$getAe2StateHolder(), factory.fluidOutputHolder,
				inputSlots, outputSlots, energySlot, superParse);
	}

	/** 批量安装 PB 升级 — 由 Mixin 拦截 PB 原版 useOn 后调用。 */
	static int installPbUpgradeBulk(@NotNull FactoryPbUpgradeDelegate pbUpgradeDelegate, @NotNull PbUpgradeType type,
			int maxAvailable) {
		return pbUpgradeDelegate.installPbUpgradeBulk(type, maxAvailable);
	}
}
