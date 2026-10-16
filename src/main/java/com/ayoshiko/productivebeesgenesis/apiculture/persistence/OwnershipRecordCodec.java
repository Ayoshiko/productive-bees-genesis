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
		tag.put("bees", BeeRecordCodec.encode(record.bees())); tag.put("centrifuge", CentrifugeRecordCodec.encode(record.centrifuge()));
		tag.put("combined", record.combined() == null ? new CompoundTag() : com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedWorkCodec.encode(record.combined())); return tag;
	}
	static OwnedMachineRecord readOwned(CompoundTag tag, java.util.function.Consumer<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey> validate,
			java.util.function.Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> validateFeeding, java.util.function.ToIntFunction<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey> itemLimits, int schema) {
		var fields = new java.util.HashSet<>(java.util.Set.of("claim", "phase", "assets", "fingerprint", "failure", "bees", "centrifuge"));
		if (schema >= 13) fields.add("combined");
		if (!tag.getAllKeys().equals(fields)) throw new IllegalArgumentException("Unknown or missing ownership fields");
		if (schema == 13 && !StrictNbt.compound(tag, "combined").isEmpty() && StrictNbt.integer(StrictNbt.compound(tag, "combined"), "schema") >= 6)
			throw new IllegalArgumentException("New combined work inside an old network schema");
		if (schema >= 13 && schema < 16 && !StrictNbt.compound(tag, "combined").isEmpty()
				&& StrictNbt.integer(StrictNbt.compound(tag, "combined"), "schema") >= 7)
			throw new IllegalArgumentException("Dynamic combined work inside an old network schema");
		if (schema >= 13 && !StrictNbt.compound(tag, "combined").isEmpty()) {
			int combinedSchema = StrictNbt.integer(StrictNbt.compound(tag, "combined"), "schema");
			if (schema < 17 && combinedSchema >= 8 || schema < 18 && combinedSchema >= 9)
				throw new IllegalArgumentException("New combined work inside an old network schema");
		}
		if (schema < 18) {
			var counts = StrictNbt.compound(tag, "assets").getCompound("extra")
					.getCompound(com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS);
			for (var key : counts.getAllKeys()) {
				var type = com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.byId(key);
				if (type == com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.GENE_SAMPLER
						|| type == com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.GENE_TYPE_ONLY
						|| type == com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.GENE_FULL_PURITY
						|| type == com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.ESSENCE_CONVERSION
						|| type == com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType.USELESS_BYPRODUCT)
					throw new IllegalArgumentException("New apiary upgrade inside an old network schema");
			}
		}
		return new OwnedMachineRecord(readClaim(StrictNbt.compound(tag, "claim")), StrictNbt.choice(tag, "phase", OwnedMachineRecord.Phase.class),
				new AssetImage(StrictNbt.compound(tag, "assets")), StrictNbt.string(tag, "fingerprint"), StrictNbt.string(tag, "failure"), BeeRecordCodec.decode(StrictNbt.compound(tag, "bees"), validate, validateFeeding, schema),
				CentrifugeRecordCodec.decode(StrictNbt.compound(tag, "centrifuge"), validate),
				schema < 13 ? null : combined(StrictNbt.compound(tag, "combined"), validate, validateFeeding, itemLimits));
	}
	static com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork combined(CompoundTag tag,
			java.util.function.Consumer<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey> validate,
			java.util.function.Consumer<com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem> feeding, java.util.function.ToIntFunction<com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey> itemLimits) {
		return tag.isEmpty() ? null : com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedWorkCodec.decode(tag, validate, itemLimits, feeding);
	}
	private OwnershipRecordCodec() { }
}
