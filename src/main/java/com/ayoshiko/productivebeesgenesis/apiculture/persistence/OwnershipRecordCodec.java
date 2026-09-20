package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import net.minecraft.nbt.CompoundTag;

final class OwnershipRecordCodec {
	static CompoundTag claim(MemberClaim claim) {
		var tag = new CompoundTag(); tag.putUUID("network", claim.network()); tag.putUUID("member", claim.member()); tag.putUUID("transfer", claim.transfer());
		tag.put("origin", CapacityRecordCodec.origin(claim.origin())); tag.putString("machine", claim.machine()); return tag;
	}
	static MemberClaim readClaim(CompoundTag tag) {
		return new MemberClaim(StrictNbt.uuid(tag, "network"), StrictNbt.uuid(tag, "member"), StrictNbt.uuid(tag, "transfer"), CapacityRecordCodec.readOrigin(StrictNbt.compound(tag, "origin")), StrictNbt.string(tag, "machine"));
	}
	static CompoundTag owned(OwnedMachineRecord record) {
		var tag = new CompoundTag(); tag.put("claim", claim(record.claim())); tag.putString("phase", record.phase().name());
		tag.put("assets", record.assets().copy()); tag.putString("fingerprint", record.fingerprint()); tag.putString("failure", record.failure());
		tag.put("bees", BeeRecordCodec.encode(record.bees())); return tag;
	}
	static OwnedMachineRecord readOwned(CompoundTag tag, java.util.function.Consumer<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey> validate) {
		return new OwnedMachineRecord(readClaim(StrictNbt.compound(tag, "claim")), StrictNbt.choice(tag, "phase", OwnedMachineRecord.Phase.class),
				new AssetImage(StrictNbt.compound(tag, "assets")), StrictNbt.string(tag, "fingerprint"), StrictNbt.string(tag, "failure"), BeeRecordCodec.decode(StrictNbt.compound(tag, "bees"), validate));
	}
	private OwnershipRecordCodec() { }
}
