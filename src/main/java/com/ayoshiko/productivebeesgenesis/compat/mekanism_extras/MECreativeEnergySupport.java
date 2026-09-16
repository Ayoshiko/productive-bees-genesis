package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import com.jerry.mekextras.api.mixin.IMixinMachineEnergyContainer;
import mekanism.api.Upgrade;
import mekanism.common.capabilities.energy.MachineEnergyContainer;

/** Mekanism Extras creative-energy operations isolated behind a load guard. */
public final class MECreativeEnergySupport {

	private MECreativeEnergySupport() {
	}

	public static void applyCreativeMaxEnergy(MachineEnergyContainer<?> energyContainer) {
		if (energyContainer instanceof IMixinMachineEnergyContainer mixin) {
			mixin.mekanism_Extras$extraUpdateMaxEnergy();
			if (energyContainer.getMaxEnergy() == Long.MAX_VALUE) {
				energyContainer.setEnergy(Long.MAX_VALUE);
			}
		}
	}

	public static void recalculateCreativeEnergy(MachineEnergyContainer<?> energyContainer,
			Upgrade upgrade, boolean creativeInstalled) {
		if (!(energyContainer instanceof IMixinMachineEnergyContainer mixin)) return;
		if (creativeInstalled) {
			applyCreativeMaxEnergy(energyContainer);
		} else if (MekUpgradeSupport.isCreativeUpgrade(upgrade)) {
			mixin.mekanism_Extras$extraRecalculateUpgrades(upgrade);
		}
	}
}
