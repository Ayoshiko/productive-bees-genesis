package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.SnapshotRecords;
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
	record Snapshot(long revision, Map<UUID, NetworkIdentity> identities) { }
	private final SnapshotRecords<UUID, NetworkIdentity> identities = new SnapshotRecords<>(Comparator.naturalOrder());
	private final Map<UUID, UUID> controllers = new ConcurrentHashMap<>();
	private final Map<com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin, UUID> positions = new ConcurrentHashMap<>();
	private long revision;
	private NetworkDirectoryData(long revision, boolean existing, CheckpointSaveQueue queue) {
		super(existing ? revision : -1, queue); this.revision = revision;
	}
	static NetworkDirectoryData create(Executor executor, Writer writer) { return create(new CheckpointSaveQueue(executor, writer)); }
	static NetworkDirectoryData create(CheckpointSaveQueue queue) { return new NetworkDirectoryData(0, false, queue); }
	static NetworkDirectoryData load(CompoundTag tag, CheckpointSaveQueue queue) {
		if (StrictNbt.integer(tag, "schema") != 1 || StrictNbt.number(tag, "revision") < 0) throw new IllegalArgumentException("Unknown directory schema or revision");
		var data = new NetworkDirectoryData(StrictNbt.number(tag, "revision"), true, queue);
		for (var raw : StrictNbt.list(tag, "networks")) {
			var identity = NetworkCheckpointCodec.readIdentity((CompoundTag) raw);
			data.validateAddition(identity); data.index(identity);
		}
		return data;
	}
	NetworkIdentity find(UUID networkId) { checkThread(); return identities.get(networkId); }
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
	Snapshot checkpoint() { checkThread(); return new Snapshot(revision, identities.snapshot()); }
	@Override protected CheckpointPayload capture() {
		return new CheckpointPayload.Directory(checkpoint(), SharedConstants.getCurrentVersion().getDataVersion().getVersion());
	}
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		var snapshot = checkpoint(); tag.putInt("schema", 1); tag.putLong("revision", snapshot.revision()); var entries = new ListTag();
		snapshot.identities().values().forEach(identity -> entries.add(NetworkCheckpointCodec.identity(identity))); tag.put("networks", entries); return tag;
	}
}
