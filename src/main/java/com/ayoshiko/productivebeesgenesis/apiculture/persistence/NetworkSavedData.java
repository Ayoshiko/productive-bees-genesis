package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.util.Objects;
import java.util.concurrent.Executor;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

/** 每网一份权威域。恢复对象没有可写空账本，缺失／坏文件不会在世界保存时被覆盖。 */
public final class NetworkSavedData extends AcknowledgedSavedData {
	public enum Status { READY, RECOVERY }
	private final NetworkIdentity identity;
	private NetworkCheckpoint checkpoint;
	private final String recoveryReason;
	private boolean closed;
	private NetworkSavedData(NetworkIdentity identity, NetworkCheckpoint checkpoint, long persistedRevision,
			String recoveryReason, CheckpointSaveQueue queue) {
		super(persistedRevision, queue);
		this.identity = Objects.requireNonNull(identity); this.checkpoint = checkpoint; this.recoveryReason = recoveryReason;
	}
	static NetworkSavedData create(NetworkCheckpoint checkpoint, Executor executor, Writer writer) {
		return create(checkpoint, new CheckpointSaveQueue(executor, writer));
	}
	static NetworkSavedData create(NetworkCheckpoint checkpoint, CheckpointSaveQueue queue) {
		return new NetworkSavedData(checkpoint.identity(), checkpoint, -1, "", queue);
	}
	static NetworkSavedData loaded(NetworkCheckpoint checkpoint, CheckpointSaveQueue queue) {
		return new NetworkSavedData(checkpoint.identity(), checkpoint, checkpoint.revision(), "", queue);
	}
	static NetworkSavedData recovery(NetworkIdentity identity, String reason, CheckpointSaveQueue queue) {
		return new NetworkSavedData(identity, null, -1, Objects.requireNonNull(reason), queue);
	}
	public NetworkIdentity identity() { return identity; }
	public Status status() { return checkpoint == null ? Status.RECOVERY : Status.READY; }
	public boolean active() { checkThread(); return writable(); }
	public String recoveryReason() { return recoveryReason; }
	public NetworkCheckpoint checkpoint() {
		checkThread();
		if (closed) throw new IllegalStateException("Network authority session closed");
		if (checkpoint == null) throw new IllegalStateException("No readable authority: " + recoveryReason);
		return checkpoint;
	}
	public void publish(NetworkCheckpoint next) {
		checkThread(); Objects.requireNonNull(next);
		if (!writable() || !next.identity().equals(identity) || next.revision() <= checkpoint.revision() || next.policyRevision() < checkpoint.policyRevision() || !next.ownedMachines().follows(checkpoint.ownedMachines())
				|| next.ledger().revision() < checkpoint.ledger().revision()
				|| next.ledger().revision() == checkpoint.ledger().revision() && !next.ledger().equals(checkpoint.ledger())) {
			throw new IllegalArgumentException("Invalid authority or checkpoint revision");
		}
		checkpoint = next;
	}
	@Override protected long revision() { return checkpoint == null ? -1 : checkpoint.revision(); }
	@Override protected boolean writable() { return checkpoint != null && !closed; }
	void closeAuthority() { checkThread(); closed = true; }
	@Override protected CheckpointPayload capture() {
		return new CheckpointPayload.Network(checkpoint(), SharedConstants.getCurrentVersion().getDataVersion().getVersion());
	}
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		checkThread(); tag.merge(NetworkCheckpointCodec.encode(checkpoint())); return tag;
	}
}
