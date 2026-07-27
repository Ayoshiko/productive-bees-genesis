package com.ayoshiko.productivebeesgenesis.mek;

import com.jerry.mekextras.api.mixin.IMixinMachineEnergyContainer;

import mekanism.common.capabilities.energy.MachineEnergyContainer;

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
 * <b>类加载安全</b>：本类直接引用{@link IMixinMachineEnergyContainer}（MEKExtras的API类），
 * 仅在MEKExtras加载时由{@link AbstractMekCentrifugeFactory#recalculateUpgrades}委托调用。
 * 未安装MEKExtras时本类不会被加载。
 *
 * @see com.jerry.mekextras.mixin.MixinMachineEnergyContainer#mekanism_Extras$extraRecalculateUpgrades
 */
public final class MekCreativeEnergyHelper {

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
		if (energyContainer instanceof IMixinMachineEnergyContainer mixin) {
			mixin.mekanism_Extras$extraUpdateMaxEnergy();
			if (energyContainer.getMaxEnergy() == Long.MAX_VALUE) {
				energyContainer.setEnergy(Long.MAX_VALUE);
			}
		}
	}
}
