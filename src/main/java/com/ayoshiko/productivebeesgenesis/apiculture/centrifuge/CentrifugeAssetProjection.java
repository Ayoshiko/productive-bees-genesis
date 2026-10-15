package com.ayoshiko.productivebeesgenesis.apiculture.centrifuge;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage;

/** 只迁出有限 FE；原有有限槽、升级与流体仍由封存映像保管，不会复制进公共余额。 */
public final class CentrifugeAssetProjection {
	public static final String MARKER = "centrifugeAuthority";
	public static AssetImage detach(AssetImage source) {
		var tag = source.copy();
		if (tag.contains(MARKER)) throw new IllegalArgumentException("Already detached centrifuge");
		tag.putBoolean(MARKER, true); tag.putLong("energy", 0); return new AssetImage(tag);
	}
	public static void validate(AssetImage source, CentrifugeWorkState state) {
		var tag = source.copy();
		if (!tag.contains(MARKER, 1) || tag.getByte(MARKER) != 1 || !tag.contains("energy", 4) || tag.getLong("energy") != 0
				|| !tag.contains("energyCapacity", 4) || tag.getLong("energyCapacity") != state.energyCapacity() || state.laneCount() != lanes(source) || !state.delivered().isEmpty())
			throw new IllegalArgumentException("Duplicate or inconsistent centrifuge energy authority");
	}
	public static void validateMigration(AssetImage source, CentrifugeWorkState state) {
		var tag = source.copy(); var extra = tag.getCompound("extra");
		if (state.revision() != 0 || state.networkPowered() || !state.drained() || state.laneCount() != lanes(source) || !tag.contains("energy", 4) || !tag.contains("energyCapacity", 4)
				|| state.energy() != tag.getLong("energy") || state.energyCapacity() != tag.getLong("energyCapacity"))
			throw new IllegalArgumentException("Centrifuge migration differs from sealed assets");
		// 旧物理进度没有记录周期内并行数，不能凭当前输入猜测已付费工作量。
		boolean factory = lanes(source) > 1;
		if (!factory && (extra.getInt("nativeProgress") != 0 || !extra.getList("productivebeesgenesis_pb_committed_pending", 10).isEmpty()))
			throw new IllegalArgumentException("Finish sealed physical work before network activation");
		for (int progress : extra.getIntArray("productivebeesgenesis_pb_progress")) if (progress != 0) throw new IllegalArgumentException("Unresolved physical progress");
		if (factory) {
			for (int progress : extra.getIntArray("nativeProgress")) if (progress != 0) throw new IllegalArgumentException("Unresolved native progress");
		} else for (long amount : extra.getLongArray("productivebeesgenesis_myriad_pending_fluid")) if (amount != 0) throw new IllegalArgumentException("Unresolved physical fluid");
	}
	private static int lanes(AssetImage source) {
		var tag = source.copy(); var extra = tag.getCompound("extra");
		if (!extra.contains("factoryProcesses")) return 1;
		int expected = com.ayoshiko.productivebeesgenesis.apiculture.capacity.CentrifugeWorkLayout.lanes(tag.getString("machine"));
		if (expected <= 1 || !extra.contains("factoryProcesses", 3) || extra.getInt("factoryProcesses") != expected
				|| extra.getIntArray("nativeProgress").length != expected) throw new IllegalArgumentException("Invalid sealed factory layout");
		return expected;
	}
	public static AssetImage attach(AssetImage residual, CentrifugeWorkState state) {
		validate(residual, state);
		if (!state.drained()) throw new IllegalStateException("Settle or cancel held centrifuge work before return");
		var tag = residual.copy(); tag.remove(MARKER); tag.putLong("energy", state.energy()); return new AssetImage(tag);
	}
	private CentrifugeAssetProjection() { }
}
