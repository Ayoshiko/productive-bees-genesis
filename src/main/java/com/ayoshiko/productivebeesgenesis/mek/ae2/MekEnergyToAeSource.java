package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.networking.energy.IEnergySource;

import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

/**
 * Mekanism 能量容器到 AE2 能量源的适配器
 * <br/>
 * 将 {@link MachineEnergyContainer}（实现 Mekanism {@code IEnergyContainer}）
 * 包装为 AE2 的 {@link IEnergySource}，供 {@link appeng.api.storage.StorageHelper#poweredInsert} 使用。
 * <p>
 * <b>能量转换</b>：AE2 的 1 AE = {@link #AE_TO_FE_RATIO} FE（Mekanism 能量单位）。
 * poweredInsert 每次插入消耗的能量很少（主要为传输成本），离心机自身 FE 供能。
 * <p>
 * <b>线程安全</b>：适配器本身无状态（仅持有 final container 字段），
 * 所有操作委托给 {@link MachineEnergyContainer}（其内部使用原子类型保证线程安全）。
 * container 引用来自宿主且在宿主生命周期内固定不变，故可安全复用。
 * <p>
 * <b>复用机制</b>：由 {@link Ae2OutputPusher.ReusableBuffers} 懒初始化并跨 tick 持有，
 * 避免每 tick 创建临时对象。物品推送和流体推送共享同一适配器实例。
 */
public final class MekEnergyToAeSource implements IEnergySource {

	/** AE2 到 Mekanism 能量转换比例：1 AE = 2 FE */
	static final double AE_TO_FE_RATIO = 2.0;

	private final MachineEnergyContainer<?> container;

	/**
	 * 构造能量适配器
	 *
	 * @param container 宿主的 Mekanism 能量容器，引用在宿主生命周期内固定不变
	 */
	public MekEnergyToAeSource(MachineEnergyContainer<?> container) {
		this.container = container;
	}

	@Override
	public double extractAEPower(double amount, Actionable mode, PowerMultiplier multiplier) {
		// AE 能量 → FE 能量（应用 multiplier 和转换比例）
		double scaled = multiplier.multiply(amount);
		long feAmount = (long) Math.ceil(scaled * AE_TO_FE_RATIO);
		if (feAmount <= 0) {
			return 0;
		}

		// Mekanism Action：MODULATE → EXECUTE，SIMULATE → SIMULATE
		Action mekAction = mode.isSimulate() ? Action.SIMULATE : Action.EXECUTE;
		long extracted = container.extract(feAmount, mekAction, AutomationType.INTERNAL);
		if (extracted <= 0) {
			return 0;
		}

		// FE 能量 → AE 能量（反向转换并应用 multiplier 除法）
		return multiplier.divide(extracted / AE_TO_FE_RATIO);
	}
}
