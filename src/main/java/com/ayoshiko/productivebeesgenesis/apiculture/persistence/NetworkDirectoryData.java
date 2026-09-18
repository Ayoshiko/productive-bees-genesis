package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/** 目录只保存身份，不保存账户；先发布完整域，随后才允许目录引用它。 */
final class NetworkDirectoryData extends AcknowledgedSavedData {
	private final Map<UUID, NetworkIdentity> identities = new ConcurrentHashMap<>();
	private final Map<UUID, UUID> controllers = new ConcurrentHashMap<>();
	private final Map<com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin, UUID> positions = new ConcurrentHashMap<>();
	private long revision;
	private NetworkDirectoryData(long revision, boolean existing, Executor executor, Writer writer) {
		super(existing ? revision : -1, executor, writer); this.revision = revision;
	}
	static NetworkDirectoryData create(Executor executor, Writer writer) { return new NetworkDirectoryData(0, false, executor, writer); }
	static NetworkDirectoryData load(CompoundTag tag, Executor executor, Writer writer) {
		if (StrictNbt.integer(tag, "schema") != 1 || StrictNbt.number(tag, "revision") < 0) throw new IllegalArgumentException("Unknown directory schema or revision");
		var data = new NetworkDirectoryData(StrictNbt.number(tag, "revision"), true, executor, writer);
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
		if (identities.containsKey(identity.networkId()) || controllers.containsKey(identity.controllerId()) || positions.containsKey(identity.origin())) {
			throw new IllegalArgumentException("Duplicate network/controller identity or authority position");
		}
	}
	private void index(NetworkIdentity identity) {
		identities.put(identity.networkId(), identity); controllers.put(identity.controllerId(), identity.networkId()); positions.put(identity.origin(), identity.networkId());
	}
	@Override protected long revision() { return revision; }
	@Override protected boolean writable() { return true; }
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		checkThread(); tag.putInt("schema", 1); tag.putLong("revision", revision); var entries = new ListTag();
		identities.values().forEach(identity -> entries.add(NetworkCheckpointCodec.identity(identity))); tag.put("networks", entries); return tag;
	}
}
