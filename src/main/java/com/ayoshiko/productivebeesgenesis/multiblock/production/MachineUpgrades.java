package com.ayoshiko.productivebeesgenesis.multiblock.production;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 四个原生槽和两路独立 PB 插件；数量是实物资产，当前配置只限制新安装。 */
public record MachineUpgrades(long revision, List<Integer> counts, Map<PbUpgradeType, Integer> apiary, Map<PbUpgradeType, Integer> centrifuge) {
	public static final int NATIVE_SLOTS = 4, LIMIT = 8;
	public static final List<PbUpgradeType> APIARY_TYPES = List.of(PbUpgradeType.PRODUCTIVITY, PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3, PbUpgradeType.PRODUCTIVITY_4, PbUpgradeType.TIME, PbUpgradeType.TIME_2, PbUpgradeType.BLOCK);
	public static final List<PbUpgradeType> CENTRIFUGE_TYPES = List.of(PbUpgradeType.PRODUCTIVITY, PbUpgradeType.PRODUCTIVITY_2,
			PbUpgradeType.PRODUCTIVITY_3, PbUpgradeType.PRODUCTIVITY_4, PbUpgradeType.TIME, PbUpgradeType.TIME_2,
			PbUpgradeType.STABILITY, PbUpgradeType.USELESS_BYPRODUCT);
	public static final int SLOTS = NATIVE_SLOTS + APIARY_TYPES.size() + CENTRIFUGE_TYPES.size();
	public static final MachineUpgrades EMPTY = new MachineUpgrades(0, List.of(0, 0, 0, 0));
	public MachineUpgrades(long revision, List<Integer> counts) { this(revision, counts, Map.of(), Map.of()); }
	public MachineUpgrades {
		counts = List.copyOf(counts); apiary = Map.copyOf(apiary); centrifuge = Map.copyOf(centrifuge);
		if (revision < 0 || counts.size() != NATIVE_SLOTS || counts.stream().anyMatch(n -> n < 0 || n > LIMIT))
			throw new IllegalArgumentException("Invalid machine upgrades");
		validate(apiary, APIARY_TYPES); validate(centrifuge, CENTRIFUGE_TYPES);
	}
	private static void validate(Map<PbUpgradeType, Integer> counts, List<PbUpgradeType> supported) {
		for (var entry : counts.entrySet()) if (!supported.contains(entry.getKey()) || entry.getValue() <= 0)
			throw new IllegalArgumentException("Unsupported or invalid machine PB upgrade");
	}
	public static boolean apiarySlot(int slot) { return slot >= NATIVE_SLOTS && slot < NATIVE_SLOTS + APIARY_TYPES.size(); }
	public static PbUpgradeType pbType(int slot) {
		if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("Invalid machine upgrade slot");
		return slot < NATIVE_SLOTS ? null : apiarySlot(slot) ? APIARY_TYPES.get(slot - NATIVE_SLOTS)
				: CENTRIFUGE_TYPES.get(slot - NATIVE_SLOTS - APIARY_TYPES.size());
	}
	public static int slot(boolean apiary, PbUpgradeType type) {
		int index = (apiary ? APIARY_TYPES : CENTRIFUGE_TYPES).indexOf(type);
		if (index < 0) throw new IllegalArgumentException("Unsupported machine PB upgrade");
		return NATIVE_SLOTS + (apiary ? 0 : APIARY_TYPES.size()) + index;
	}
	public Map<PbUpgradeType, Integer> pbCounts(boolean bee) { return bee ? apiary : centrifuge; }
	public int count(int slot) {
		var type = pbType(slot);
		return type == null ? counts.get(slot) : pbCounts(apiarySlot(slot)).getOrDefault(type, 0);
	}
	public MachineUpgrades change(int slot, int delta) {
		var type = pbType(slot);
		if (delta == 0) return this;
		int count = Math.addExact(count(slot), delta);
		if (count < 0) throw new IllegalArgumentException("Negative machine upgrade count");
		long nextRevision = Math.incrementExact(revision);
		if (type == null) {
			var next = new ArrayList<>(counts); next.set(slot, count);
			return new MachineUpgrades(nextRevision, next, apiary, centrifuge);
		}
		boolean bee = apiarySlot(slot); var next = new EnumMap<PbUpgradeType, Integer>(PbUpgradeType.class); next.putAll(pbCounts(bee));
		if (count == 0) next.remove(type); else next.put(type, count);
		return new MachineUpgrades(nextRevision, counts, bee ? next : apiary, bee ? centrifuge : next);
	}
}
