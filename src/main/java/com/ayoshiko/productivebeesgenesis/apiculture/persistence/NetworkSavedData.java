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
	private long maintenanceTick;
	private boolean maintenancePaid;
	private com.ayoshiko.productivebeesgenesis.apiculture.storage.ProcessingStockIndex processingStock;
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
	/** 首次读取只创建扫描游标，调用方必须按工作预算推进 step；索引不进入存档。 */
	public com.ayoshiko.productivebeesgenesis.apiculture.storage.ProcessingStockIndex processingStock() {
		var current = checkpoint();
		if (processingStock == null) processingStock = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProcessingStockIndex(current.ledger());
		return processingStock;
	}
	public void publish(NetworkCheckpoint next) {
		checkThread(); Objects.requireNonNull(next);
		if (!writable() || !next.identity().equals(identity) || next.revision() <= checkpoint.revision() || next.policyRevision() < checkpoint.policyRevision() || !next.ownedMachines().follows(checkpoint.ownedMachines())
				|| next.ledger().revision() < checkpoint.ledger().revision()
				|| next.ledger().revision() == checkpoint.ledger().revision() && !next.ledger().equals(checkpoint.ledger())) {
			throw new IllegalArgumentException("Invalid authority or checkpoint revision");
		}
		checkpoint = next;
		if (processingStock != null) processingStock.update(next.ledger());
	}
	/** 仅成功推进新工作时收取一次；收据属于权威会话，核心 BE 重建不能重复扣款。 */
	public long maintenanceDue(long tick, long fee) {
		checkpoint();
		if (fee < 0) throw new IllegalArgumentException("Negative maintenance fee");
		return maintenancePaid && maintenanceTick == tick ? 0 : fee;
	}
	public void publishMaintainedWork(NetworkCheckpoint expected, NetworkCheckpoint work, long tick, long fee) {
		if (checkpoint() != expected || work == expected) throw new IllegalArgumentException("Stale or empty maintained work");
		long due = maintenanceDue(tick, fee);
		if (work.energy().stored() > expected.energy().stored() || work.energy().stored() < due) throw new IllegalArgumentException("Unfunded maintained work");
		// 工作进度、工作耗能和维护费作为一个根发布，不先扣费后发现工作被拒绝。
		publish(work.spendMaintenance(due));
		if (due > 0) { maintenanceTick = tick; maintenancePaid = true; }
	}
	@Override protected long revision() { return checkpoint == null ? -1 : checkpoint.revision(); }
	@Override protected boolean writable() { return checkpoint != null && !closed; }
	void closeAuthority() { checkThread(); closed = true; processingStock = null; }
	@Override protected CheckpointPayload capture() {
		return new CheckpointPayload.Network(checkpoint(), SharedConstants.getCurrentVersion().getDataVersion().getVersion());
	}
	@Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
		checkThread(); tag.merge(NetworkCheckpointCodec.encode(checkpoint())); return tag;
	}
}
