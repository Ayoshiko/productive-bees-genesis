package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECreativeEnergySupport;
import mekanism.api.Upgrade;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

import java.util.concurrent.atomic.AtomicBoolean;

/**
	 * CREATIVE升级能量容器辅助类
	 * <br/>
	 * 1:1复刻MEKExtras {@code MixinMachineEnergyContainer.mekanism_Extras$extraRecalculateUpgrades} 中
	 * CREATIVE安装时的无限电容量逻辑：
	 * <ul>
	 *   <li>{@code setMaxEnergy(Long.MAX_VALUE)} — 设置最大电容量为无限</li>
	 *   <li>{@code setEnergy(Long.MAX_VALUE)} — 设置当前能量为满</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本公共门面不在字段或方法签名中引用 MEKExtras 类型，
	 * 只有加载守卫通过后才进入隔离兼容类。
	 *
	 * @see com.jerry.mekextras.mixin.MixinMachineEnergyContainer#mekanism_Extras$extraRecalculateUpgrades
	 */
public final class MekCreativeEnergyHelper {
	private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

	private MekCreativeEnergyHelper() {}

	/**
	 * 应用CREATIVE升级的无限电容量
	 * <br/>
	 * 1:1复刻MEKExtras源码：
	 * <pre>{@code
	 * if (upgrade == ExtraUpgrade.CREATIVE) {
	 *     mekanism_Extras$extraUpdateMaxEnergy();
	 *     if (getMaxEnergy() == Long.MAX_VALUE) {
	 *         setEnergy(Long.MAX_VALUE);
	 *     }
	 * }
	 * }</pre>
	 *
	 * @param energyContainer 机器能量容器（必须实现IMixinMachineEnergyContainer）
	 */
	public static void applyCreativeMaxEnergy(MachineEnergyContainer<?> energyContainer) {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) return;
		try {
			MECreativeEnergySupport.applyCreativeMaxEnergy(energyContainer);
		} catch (LinkageError | RuntimeException error) {
			logFailure(error);
		}
	}

	/**
	 * Reconciles the creative capacity after Mekanism has handled an upgrade change.
	 * Installing creative must restore the unlimited capacity that a SPEED/ENERGY
	 * recalculation may overwrite. Removing creative delegates to Mekanism Extras so
	 * the normal energy-upgrade capacity is restored immediately.
	 */
	public static void recalculateCreativeEnergy(MachineEnergyContainer<?> energyContainer,
			Upgrade upgrade, boolean creativeInstalled) {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) return;
		try {
			MECreativeEnergySupport.recalculateCreativeEnergy(energyContainer, upgrade, creativeInstalled);
		} catch (LinkageError | RuntimeException error) {
			logFailure(error);
		}
	}

	private static void logFailure(Throwable error) {
		if (FAILURE_LOGGED.compareAndSet(false, true)) {
			ProductiveBeesGenesis.LOGGER.warn("Mekanism Extras 创造升级兼容层不可用，已安全降级", error);
		}
	}
}
