package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import mekanism.api.Upgrade;
import mekanism.api.SerializationConstants;
import mekanism.api.math.MathUtils;
import mekanism.common.config.MekanismConfig;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.ModList;

/** 基础机的封存能力视图，不将升级临时装回实体，也不执行隐藏 ticker。 */
final class SealedCentrifugeProfile {
	private final Map<PbUpgradeType, Integer> pb;
	private final float time;
	private final int parallel;
	private final long energy;
	SealedCentrifugeProfile(TileEntityMekCentrifuge tile, AssetImage assets) {
		if (ModList.get().isLoaded("mekanism_unleashed") || ModList.get().isLoaded("mekanism_empowered"))
			throw new IllegalArgumentException("Modified Mekanism upgrade formulas require a dedicated network adapter");
		var tag = assets.copy();
		if (!tag.contains("upgrades", Tag.TAG_COMPOUND) || !tag.contains("extra", Tag.TAG_COMPOUND)
				|| !tag.getCompound("extra").contains(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS, Tag.TAG_COMPOUND))
			throw new IllegalArgumentException("Missing sealed upgrade records");
		var nativeUpgrades = nativeCounts(tag.getCompound("upgrades"));
		for (var entry : nativeUpgrades.entrySet()) {
			if (entry.getKey() != Upgrade.SPEED && entry.getKey() != Upgrade.ENERGY)
				throw new IllegalArgumentException("Unsupported network upgrade: " + entry.getKey());
			if (entry.getValue() < 0 || entry.getValue() > entry.getKey().getMax())
				throw new IllegalArgumentException("Unsupported native upgrade count");
		}
		var counts = tag.getCompound("extra").getCompound(MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS);
		Map<PbUpgradeType, Integer> values = new ConcurrentHashMap<>();
		for (var key : counts.getAllKeys()) {
			var type = PbUpgradeType.byId(key);
			if (type == null || !supported(type) || !counts.contains(key, Tag.TAG_INT) || counts.getInt(key) <= 0)
				throw new IllegalArgumentException("Unsupported sealed PB upgrade: " + key);
			values.put(type, counts.getInt(key));
		}
		pb = Map.copyOf(values);
		double speed = nativeUpgrades.getOrDefault(Upgrade.SPEED, 0) / (double) Upgrade.SPEED.getMax();
		double saving = nativeUpgrades.getOrDefault(Upgrade.ENERGY, 0) / (double) Upgrade.ENERGY.getMax();
		double multiplier = MekanismConfig.general.maxUpgradeMultiplier.get();
		// 对齐固定依赖 MekanismUtils；改写此公式的已知集成在入口隔离。
		double speedFactor = Math.pow(multiplier, -speed);
		time = CentrifugePbMultipliers.time(this::count, (float) Math.min(1, speedFactor));
		int operations = MathUtils.clampToInt(Math.max(1, 1 / (tile.baseTicksRequired() * speedFactor)));
		int limit = ModConfig.SERVER.mekCentrifugeMaxOpsPerTick.get();
		if (limit > 0) operations = Math.min(operations, limit);
		parallel = SaturatingMath.saturatingToInt((long) operations * CentrifugePbMultipliers.parallel(this::count));
		double price = tile.energyContainer().getBaseEnergyPerTick() * Math.pow(multiplier, 2 * speed - saving);
		if (!Double.isFinite(price) || price < 0 || price >= 0x1.0p63) throw new IllegalArgumentException("Unrepresentable native energy price");
		energy = MathUtils.ceilToLong(price);
	}
	private static Map<Upgrade, Integer> nativeCounts(net.minecraft.nbt.CompoundTag tag) {
		Map<Upgrade, Integer> result = new ConcurrentHashMap<>();
		if (!tag.contains(SerializationConstants.UPGRADES)) return result;
		if (!(tag.get(SerializationConstants.UPGRADES) instanceof net.minecraft.nbt.ListTag list)
				|| !list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) throw new IllegalArgumentException("Malformed native upgrades");
		for (var raw : list) {
			var entry = (net.minecraft.nbt.CompoundTag) raw;
			int ordinal = entry.getInt(SerializationConstants.TYPE), count = entry.getInt(SerializationConstants.AMOUNT);
			if (entry.size() != 2 || !entry.contains(SerializationConstants.TYPE, Tag.TAG_INT) || !entry.contains(SerializationConstants.AMOUNT, Tag.TAG_INT)
					|| ordinal < 0 || ordinal >= Upgrade.values().length || count <= 0
					|| result.putIfAbsent(Upgrade.values()[ordinal], count) != null) throw new IllegalArgumentException("Invalid or duplicate native upgrade");
		}
		return result;
	}
	private static boolean supported(PbUpgradeType type) {
		return switch (type) {
			case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4, TIME, TIME_2, STABILITY, USELESS_BYPRODUCT -> true;
			default -> false;
		};
	}
	private int count(PbUpgradeType type) { return pb.getOrDefault(type, 0); }
	int ticks(int base) { return MekExtrasUpgradeSemantics.processingTicks(false, base, time); }
	int parallel() { return parallel; }
	long energy() { return energy; }
	int productivity() { return Math.max(1, (int) Math.floor(CentrifugePbMultipliers.productivity(this::count))); }
	float stability() { return CentrifugePbMultipliers.stability(this::count); }
	boolean discardByproducts() { return count(PbUpgradeType.USELESS_BYPRODUCT) > 0; }
}
