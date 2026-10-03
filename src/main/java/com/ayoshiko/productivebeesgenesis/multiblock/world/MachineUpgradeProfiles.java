package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryUpgradeMath;
import com.ayoshiko.productivebeesgenesis.apiary.StaticApiaryAdapter;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeEnergyScaling;
import com.ayoshiko.productivebeesgenesis.mek.StaticCentrifugeAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.production.MachineUpgrades;
import mekanism.api.Upgrade;
import mekanism.api.math.MathUtils;
import mekanism.common.config.MekanismConfig;
import mekanism.common.tile.prefab.TileEntityElectricMachine;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.world.item.ItemStack;

/** 单机两路各用自己的插件；读取基础公式，不创建或临时修改物理机器。 */
final class MachineUpgradeProfiles {
	static ItemStack unit(int slot) {
		if (slot < 0 || slot >= MachineUpgrades.SLOTS) throw new IllegalArgumentException("Invalid upgrade slot");
		return UpgradeUtils.getStack(slot % 2 == 0 ? Upgrade.SPEED : Upgrade.ENERGY, 1);
	}
	static StaticApiaryAdapter.Profile apiary(MachineUpgrades upgrades) {
		validate(upgrades);
		float multiplier = MekanismConfig.general.maxUpgradeMultiplier.get();
		return new StaticApiaryAdapter.Profile(ApiaryUpgradeMath.computeMekSpeedTimeMultiplier(upgrades.count(0), Upgrade.SPEED.getMax(), multiplier),
				price(ModConfig.SERVER.apiaryEnergyPerTick.get(), upgrades.count(0), upgrades.count(1), multiplier), 1, false);
	}
	static StaticCentrifugeAdapter.Profile centrifuge(MachineUpgrades upgrades) {
		validate(upgrades);
		int baseTicks = TileEntityElectricMachine.BASE_TICKS_REQUIRED;
		float multiplier = MekanismConfig.general.maxUpgradeMultiplier.get();
		double speedFactor = Math.pow(multiplier, -upgrades.count(2) / (double) Upgrade.SPEED.getMax());
		int parallel = MathUtils.clampToInt(Math.max(1, 1 / (baseTicks * speedFactor)));
		int limit = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
		if (limit > 0) parallel = Math.min(parallel, limit);
		return new StaticCentrifugeAdapter.Profile(baseTicks, (float) Math.min(1, speedFactor), parallel,
				price(ModConfig.SERVER.mekCentrifugeEnergyPerTick.get(), upgrades.count(2), upgrades.count(3), multiplier), 1, 0, false);
	}
	private static void validate(MachineUpgrades upgrades) {
		if (upgrades.counts().stream().noneMatch(count -> count > 0)) return;
		var mods = net.neoforged.fml.ModList.get();
		if (Upgrade.SPEED.getMax() != MachineUpgrades.LIMIT || Upgrade.ENERGY.getMax() != MachineUpgrades.LIMIT
				|| mods.isLoaded("mekanism_unleashed") || mods.isLoaded("mekanism_empowered"))
			throw new IllegalArgumentException("Modified native upgrade formulas require a machine adapter");
	}
	private static long price(long configured, int speed, int saving, float multiplier) {
		double value = MekCentrifugeEnergyScaling.balancedBaseEnergyPerTick(configured)
				* Math.pow(multiplier, 2 * speed / (double) Upgrade.SPEED.getMax() - saving / (double) Upgrade.ENERGY.getMax());
		if (!Double.isFinite(value) || value < 0 || value >= 0x1.0p63) throw new IllegalArgumentException("Unrepresentable machine energy price");
		return MathUtils.ceilToLong(value);
	}
	private MachineUpgradeProfiles() { }
}
