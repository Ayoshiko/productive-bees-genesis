package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.saveddata.SavedData;

/** 原生 dirty 不代表落盘；请求只记录版本，后台成功回执才推进持久化版本。 */
abstract class AcknowledgedSavedData extends SavedData {
	@FunctionalInterface interface Writer { void write(Path target, CheckpointPayload payload) throws IOException; }
	private final Thread owner = Thread.currentThread();
	private final CheckpointSaveQueue queue;
	private Path boundPath;
	private long persistedRevision;
	private long requestedRevision = -1;
	private long retryAfter;
	private int failures;
	private String lastFailure = "";
	protected AcknowledgedSavedData(long persistedRevision, CheckpointSaveQueue queue) {
		this.persistedRevision = persistedRevision; this.queue = Objects.requireNonNull(queue);
	}
	protected abstract long revision();
	protected abstract boolean writable();
	protected abstract CheckpointPayload capture();
	public final long persistedRevision() { checkThread(); return persistedRevision; }
	public final String lastFailure() { checkThread(); return lastFailure; }
	@Override public final boolean isDirty() { checkThread(); return writable() && revision() > persistedRevision; }
	@Override public final void save(File file, HolderLookup.Provider registries) {
		checkThread();
		if (!isDirty()) return;
		bind(file); requestedRevision = Math.max(requestedRevision, revision()); queue.request(this);
	}
	private void bind(File file) {
		Path target = file.toPath().toAbsolutePath().normalize();
		if (boundPath != null && !boundPath.equals(target)) throw new IllegalArgumentException("Saved domain cannot change its file");
		boundPath = target;
	}
	final Path path() { return boundPath; }
	final boolean requested() { return isDirty() && requestedRevision > persistedRevision; }
	final boolean ready() { return requested() && (failures == 0 || System.nanoTime() - retryAfter >= 0); }
	final void acknowledged(long snapshotRevision) {
		checkThread(); persistedRevision = snapshotRevision; failures = 0; retryAfter = 0; lastFailure = "";
	}
	final void failed(Throwable failure) {
		checkThread();
		if (failures == 0) LogUtils.getLogger().error("Cannot publish bee network checkpoint {}; retained for retry", boundPath, failure);
		failures = Math.min(8, failures + 1);
		retryAfter = System.nanoTime() + (1L << failures) * 50_000_000L; lastFailure = failure.toString();
	}
	public final void poll() { checkThread(); queue.poll(); }
	/** 停服允许等待 IO；失败仍返回错误，既不清 dirty，也不无界重试。 */
	public final void flush(File file, HolderLookup.Provider registries) throws IOException {
		checkThread();
		if (!writable()) throw new IOException("Network domain is quarantined");
		bind(file); requestedRevision = Math.max(requestedRevision, revision()); queue.flush(this);
	}
	protected final void checkThread() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Network persistence belongs to its server thread");
	}
}
