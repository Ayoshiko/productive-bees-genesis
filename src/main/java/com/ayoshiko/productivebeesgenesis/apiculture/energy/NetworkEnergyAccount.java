package com.ayoshiko.productivebeesgenesis.apiculture.energy;

/** 有限 FE 权威值；只用于同一 checkpoint 内的候选计算，不持有世界或能力对象。 */
public record NetworkEnergyAccount(long stored, long capacity) {
	public static final NetworkEnergyAccount EMPTY = new NetworkEnergyAccount(0, 0);
	public NetworkEnergyAccount {
		if (stored < 0 || capacity < stored) throw new IllegalArgumentException("Invalid network energy account");
	}
	public long accept(long offered) { return Math.min(Math.max(0, offered), capacity - stored); }
	public NetworkEnergyAccount credit(long amount) {
		if (amount < 0 || amount > capacity - stored) throw new IllegalArgumentException("Network energy capacity exceeded");
		return amount == 0 ? this : new NetworkEnergyAccount(stored + amount, capacity);
	}
	public NetworkEnergyAccount spend(long amount) {
		if (amount < 0 || amount > stored) throw new IllegalArgumentException("Unfunded network work");
		return amount == 0 ? this : new NetworkEnergyAccount(stored - amount, capacity);
	}
}
