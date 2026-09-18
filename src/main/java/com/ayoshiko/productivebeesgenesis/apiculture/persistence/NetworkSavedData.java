package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.util.Objects;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** 每网一份权威域。恢复对象没有可写空账本，缺失／坏文件不会在世界保存时被覆盖。 */
public final class NetworkSavedData extends AcknowledgedSavedData {
	public enum Status { READY, RECOVERY }
	private final NetworkIdentity identity;
	private NetworkCheckpoint checkpoint;
	private final String recoveryReason;
	private NetworkSavedData(NetworkIdentity identity, NetworkCheckpoint checkpoint, long persistedRevision,
			String recoveryReason, Executor executor, Writer writer) {
		super(persistedRevision, executor, writer);
		this.identity = Objects.requireNonNull(identity); this.checkpoint = checkpoint; this.recoveryReason = recoveryReason;
	}
	static NetworkSavedData create(NetworkCheckpoint checkpoint, Executor executor, Writer writer) {
		return new NetworkSavedData(checkpoint.identity(), checkpoint, -1, "", executor, writer);
	}
	static NetworkSavedData loaded(NetworkCheckpoint checkpoint, Executor executor, Writer writer) {
		return new NetworkSavedData(checkpoint.identity(), checkpoint, checkpoint.revision(), "", executor, writer);
	}
	static NetworkSavedData recovery(NetworkIdentity identity, String reason, Executor executor, Writer writer) {
		return new NetworkSavedData(identity, null, -1, Objects.requireNonNull(reason), executor, writer);
	}
	public NetworkIdentity identity() { return identity; }
	public Status status() { return checkpoint == null ? Status.RECOVERY : Status.READY; }
	public String recoveryReason() { return recoveryReason; }
	public NetworkCheckpoint checkpoint() {
		checkThread();
		if (checkpoint == null) throw new IllegalStateException("No readable authority: " + recoveryReason);
		return checkpoint;
	}
	public void publish(NetworkCheckpoint next) {
		checkThread(); Objects.requireNonNull(next);
		if (!writable() || !next.identity().equals(identity) || next.revision() <= checkpoint.revision()) {
			throw new IllegalArgumentException("Invalid authority or checkpoint revision");
		}
		checkpoint = next;
	}
	@Override protected long revision() { return checkpoint == null ? -1 : checkpoint.revision(); }
	@Override protected boolean writable() { return checkpoint != null; }
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		checkThread(); tag.merge(NetworkCheckpointCodec.encode(checkpoint())); return tag;
	}
}
