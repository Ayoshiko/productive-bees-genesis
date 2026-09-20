package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.energy.NetworkEnergyAccount;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;

final class EnergyRecordCodec {
	static CompoundTag encode(NetworkEnergyAccount value) {
		var tag = new CompoundTag(); tag.putLong("stored", value.stored()); tag.putLong("capacity", value.capacity()); return tag;
	}
	static NetworkEnergyAccount decode(CompoundTag tag) {
		if (!tag.getAllKeys().equals(Set.of("stored", "capacity"))) throw new IllegalArgumentException("Invalid energy record fields");
		return new NetworkEnergyAccount(StrictNbt.number(tag, "stored"), StrictNbt.number(tag, "capacity"));
	}
	private EnergyRecordCodec() { }
}
