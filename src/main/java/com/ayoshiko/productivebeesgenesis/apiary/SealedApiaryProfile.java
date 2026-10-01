package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.NativeUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.PbApiaryUpgradeCounts;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import mekanism.api.Upgrade;
import mekanism.api.math.MathUtils;
import mekanism.common.config.MekanismConfig;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.ModList;

/** 基础蜂箱从唯一封存升级读取能力；物理升级组件在托管期间保持为空。 */
final class SealedApiaryProfile {
	private final float time;
	private final long energy;
	SealedApiaryProfile(TileEntityMekApiary hive, AssetImage assets) {
		if (hive.getClass() != TileEntityMekApiary.class || ModList.get().isLoaded("mekanism_unleashed")
				|| ModList.get().isLoaded("mekanism_empowered"))
			throw new IllegalArgumentException("Modified apiary upgrade formulas need their own adapter");
		var image = assets.copy(); var extra = image.getCompound("extra");
		if (!image.contains("upgrades", Tag.TAG_COMPOUND) || !image.contains("extra", Tag.TAG_COMPOUND)
				|| !extra.contains(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION, Tag.TAG_BYTE)
				|| extra.getBoolean(ApiaryNbtSerializer.NBT_KEY_FEEDER_CONVERSION))
			throw new IllegalArgumentException("Static apiaries require sealed upgrades and disabled feeder conversion");
		var pb = PbApiaryUpgradeCounts.read(extra);
		var upgrades = NativeUpgradeCounts.read(image.getCompound("upgrades"));
		for (var entry : upgrades.entrySet())
			if ((entry.getKey() != Upgrade.SPEED && entry.getKey() != Upgrade.ENERGY) || entry.getValue() > entry.getKey().getMax())
				throw new IllegalArgumentException("Unsupported apiary native upgrade");
		int speed = upgrades.getOrDefault(Upgrade.SPEED, 0), saving = upgrades.getOrDefault(Upgrade.ENERGY, 0);
		float multiplier = MekanismConfig.general.maxUpgradeMultiplier.get();
		float mekTime = ApiaryUpgradeMath.computeMekSpeedTimeMultiplier(speed, Upgrade.SPEED.getMax(), multiplier);
		float pbTime = ApiaryUpgradeMath.computePbTimeDivisor(pb.getOrDefault(PbUpgradeType.TIME, 0), pb.getOrDefault(PbUpgradeType.TIME_2, 0), PbUpgradeConfig.timeBonus());
		time = SaturatingMath.positiveFiniteFloat((double) mekTime / pbTime, 1.0f);
		double price = hive.energyContainer().getBaseEnergyPerTick()
				* Math.pow(multiplier, 2 * speed / (double) Upgrade.SPEED.getMax() - saving / (double) Upgrade.ENERGY.getMax());
		if (!Double.isFinite(price) || price < 0 || price >= 0x1.0p63)
			throw new IllegalArgumentException("Unrepresentable apiary energy price");
		energy = MathUtils.ceilToLong(price);
	}
	float time() { return time; }
	long energy() { return energy; }
}
