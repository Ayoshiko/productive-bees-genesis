package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 目录只保存身份，不保存账户；先发布完整域，随后才允许目录引用它。 */
final class NetworkDirectoryData extends AcknowledgedSavedData {
	record Snapshot(long revision, Map<UUID, NetworkIdentity> identities, Map<Origin, MemberClaim> claims) { }
	static final Comparator<Origin> CLAIM_ORDER = Comparator.comparing(Origin::dimension).thenComparingInt(Origin::x).thenComparingInt(Origin::y).thenComparingInt(Origin::z);
	private SnapshotRecords<Origin, MemberClaim> claims = new SnapshotRecords<>(CLAIM_ORDER);
	private Map<UUID, Origin> claimedMembers = new ConcurrentHashMap<>();
	private Map<UUID, Origin> claimedTransfers = new ConcurrentHashMap<>();
	private SnapshotRecords<UUID, NetworkIdentity> identities = new SnapshotRecords<>(Comparator.naturalOrder());
	private Map<UUID, UUID> controllers = new ConcurrentHashMap<>();
	private Map<com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin, UUID> positions = new ConcurrentHashMap<>();
	private long revision;
	private NetworkDirectoryData(long revision, boolean existing, CheckpointSaveQueue queue) {
		super(existing ? revision : -1, queue); this.revision = revision;
	}
	static NetworkDirectoryData create(Executor executor, Writer writer) { return create(new CheckpointSaveQueue(executor, writer)); }
	static NetworkDirectoryData create(CheckpointSaveQueue queue) { return new NetworkDirectoryData(0, false, queue); }
	static NetworkDirectoryData restore(CheckpointSchema.DirectoryState state, CheckpointSaveQueue queue) {
		var data = new NetworkDirectoryData(state.revision, true, queue);
		data.identities = state.identities; data.controllers = state.controllers; data.positions = state.positions;
		data.claims = state.claims; data.claimedMembers = state.claimedMembers;
		data.claimedTransfers = state.claimedTransfers;
		return data;
	}
	static NetworkDirectoryData load(CompoundTag tag, CheckpointSaveQueue queue) {
		if (StrictNbt.integer(tag, "schema") != 2 || StrictNbt.number(tag, "revision") < 0) throw new IllegalArgumentException("Unknown directory schema or revision");
		var data = new NetworkDirectoryData(StrictNbt.number(tag, "revision"), true, queue);
		for (var raw : StrictNbt.list(tag, "networks")) {
			var identity = NetworkCheckpointCodec.readIdentity((CompoundTag) raw);
			data.validateAddition(identity); data.index(identity);
		}
		for (var raw : StrictNbt.list(tag, "claims")) data.putClaim(OwnershipRecordCodec.readClaim((CompoundTag) raw));
		return data;
	}
	NetworkIdentity find(UUID networkId) { checkThread(); return identities.get(networkId); }
	MemberClaim claimAt(Origin origin) { checkThread(); return claims.get(origin); }
	boolean hasClaims() { checkThread(); return !claims.isEmpty(); }
	long reserve(MemberClaim claim) {
		checkThread(); var existing = claims.get(claim.origin());
		if (claim.equals(existing)) return revision;
		putClaim(claim); revision = Math.incrementExact(revision); return revision;
	}
	private void putClaim(MemberClaim claim) {
		var identity = identities.get(claim.network());
		if (identity == null || !identity.origin().dimension().equals(claim.origin().dimension()) || claims.get(claim.origin()) != null || claimedMembers.containsKey(claim.member()) || claimedTransfers.containsKey(claim.transfer())) throw new IllegalArgumentException("Conflicting or orphaned member claim");
		claims.put(claim.origin(), claim); claimedMembers.put(claim.member(), claim.origin()); claimedTransfers.put(claim.transfer(), claim.origin());
	}
	long release(MemberClaim claim) {
		checkThread(); if (!claim.equals(claims.get(claim.origin()))) throw new IllegalArgumentException("Member claim changed");
		claims.remove(claim.origin()); claimedMembers.remove(claim.member()); claimedTransfers.remove(claim.transfer()); revision = Math.incrementExact(revision); return revision;
	}
	void add(NetworkIdentity identity) {
		checkThread();
		validateAddition(identity); revision = Math.incrementExact(revision); index(identity);
	}
	void validateAddition(NetworkIdentity identity) {
		checkThread();
		if (identities.get(identity.networkId()) != null || controllers.containsKey(identity.controllerId()) || positions.containsKey(identity.origin())) {
			throw new IllegalArgumentException("Duplicate network/controller identity or authority position");
		}
	}
	private void index(NetworkIdentity identity) {
		identities.put(identity.networkId(), identity); controllers.put(identity.controllerId(), identity.networkId()); positions.put(identity.origin(), identity.networkId());
	}
	@Override protected long revision() { return revision; }
	@Override protected boolean writable() { return true; }
	Snapshot checkpoint() { checkThread(); return new Snapshot(revision, identities.snapshot(), claims.snapshot()); }
	@Override protected CheckpointPayload capture() {
		return new CheckpointPayload.Directory(checkpoint(), SharedConstants.getCurrentVersion().getDataVersion().getVersion());
	}
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		var snapshot = checkpoint(); tag.putInt("schema", 2); tag.putLong("revision", snapshot.revision()); var entries = new ListTag();
		snapshot.identities().values().forEach(identity -> entries.add(NetworkCheckpointCodec.identity(identity))); tag.put("networks", entries);
		var claimEntries = new ListTag(); snapshot.claims().values().forEach(claim -> claimEntries.add(OwnershipRecordCodec.claim(claim))); tag.put("claims", claimEntries); return tag;
	}
}
