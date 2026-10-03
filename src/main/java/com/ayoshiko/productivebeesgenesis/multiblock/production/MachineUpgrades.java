package com.ayoshiko.productivebeesgenesis.multiblock.production;

import java.util.ArrayList;
import java.util.List;

/** 四个原生插件槽：蜂箱速度、蜂箱节能、离心速度、离心节能；数量是本机真实资产。 */
public record MachineUpgrades(long revision, List<Integer> counts) {
	public static final int SLOTS = 4, LIMIT = 8;
	public static final MachineUpgrades EMPTY = new MachineUpgrades(0, List.of(0, 0, 0, 0));
	public MachineUpgrades {
		counts = List.copyOf(counts);
		if (revision < 0 || counts.size() != SLOTS || counts.stream().anyMatch(n -> n < 0 || n > LIMIT))
			throw new IllegalArgumentException("Invalid machine upgrades");
	}
	public int count(int slot) { return counts.get(slot); }
	public MachineUpgrades change(int slot, int delta) {
		if (delta == 0) return this;
		var next = new ArrayList<>(counts); next.set(slot, Math.addExact(count(slot), delta));
		return new MachineUpgrades(Math.incrementExact(revision), next);
	}
}
