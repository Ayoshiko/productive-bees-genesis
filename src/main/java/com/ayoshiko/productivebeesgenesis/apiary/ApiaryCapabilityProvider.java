package com.ayoshiko.productivebeesgenesis.apiary;

import mekanism.api.IContentsListener;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import mekanism.common.capabilities.holder.slot.IInventorySlotHolder;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
	 * 蜂箱能力分发提供器
	 * <br/>
	 * 封装 MEK 通用机械蜂箱方块实体的能力（物品/流体/能量）初始化分发逻辑。
	 * Mekanism 的能力系统通过 getInitialInventory/getInitialFluidTanks 钩子构建能力提供者，
	 * 本类将这些构建调用集中管理，使 {@link TileEntityMekApiary} 专注于蜂箱业务逻辑。
	 * <p>
	 * 能力分发职责：
	 * <ol>
	 *   <li>物品能力 — 通过 {@link #buildInventory} 构建物品槽位持有者</li>
	 *   <li>流体能力 — 通过 {@link #buildFluidTanks} 构建流体罐持有者</li>
	 *   <li>能量能力 — 由父类 TileEntityElectricMachine 基于能量容器自动注册</li>
	 * </ol>
	 * <p>
	 * 线程语义：仅在主线程（方块实体构造/加载）调用，非线程安全。
	 *
	 * @since 1.0.0
	 */
public class ApiaryCapabilityProvider {

	/** 槽位管理器懒加载提供器 — super() 构造期间尚未初始化，需懒求值 */
	private final Supplier<ApiarySlotManager> slotManagerSupplier;

	/**
	 * 构造能力分发提供器
	 *
	 * @param slotManagerSupplier	槽位管理器懒加载提供器（super() 构造期间尚未初始化，需懒求值）
	 */
	public ApiaryCapabilityProvider(Supplier<ApiarySlotManager> slotManagerSupplier) {
		this.slotManagerSupplier = slotManagerSupplier;
	}

	/**
	 * 构建物品能力槽位持有者 — 委托给槽位管理器
	 * <br/>
	 * 由 Mekanism 父类在构造期间通过 getInitialInventory 钩子调用，
	 * 构建物品自动化（物品处理）能力提供者。
	 *
	 * @param listener						通用内容变更监听器
	 * @param recipeCacheListener			配方缓存监听器
	 * @param recipeCacheUnpauseListener	配方缓存取消暂停监听器
	 * @return 物品槽位持有者
	 */
	@NotNull
	public IInventorySlotHolder buildInventory(@NotNull IContentsListener listener,
			@NotNull IContentsListener recipeCacheListener,
			@NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManagerSupplier.get().buildInventory(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}

	/**
	 * 构建流体能力罐持有者 — 委托给槽位管理器
	 * <br/>
	 * 由 Mekanism 父类在构造期间通过 getInitialFluidTanks 钩子调用，
	 * 构建流体自动化（流体处理）能力提供者。
	 *
	 * @param listener						通用内容变更监听器
	 * @param recipeCacheListener			配方缓存监听器
	 * @param recipeCacheUnpauseListener	配方缓存取消暂停监听器
	 * @return 流体罐持有者
	 */
	@NotNull
	public IFluidTankHolder buildFluidTanks(@NotNull IContentsListener listener,
											@NotNull IContentsListener recipeCacheListener,
											@NotNull IContentsListener recipeCacheUnpauseListener) {
		return slotManagerSupplier.get().buildFluidTanks(listener, recipeCacheListener, recipeCacheUnpauseListener);
	}
}
