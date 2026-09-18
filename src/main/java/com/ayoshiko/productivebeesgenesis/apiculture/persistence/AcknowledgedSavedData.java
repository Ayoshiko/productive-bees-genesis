package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** 原生 dirty 不代表落盘；完成回执只推进捕获的 revision，失败保留可重试状态。 */
abstract class AcknowledgedSavedData extends SavedData {
	@FunctionalInterface interface Writer { void write(Path target, byte[] bytes) throws IOException; }
	private final Thread owner = Thread.currentThread();
	private final Executor executor;
	private final Writer writer;
	private CompletableFuture<Void> flight;
	private Path boundPath;
	private long snapshotRevision;
	private long persistedRevision;
	private long requestedRevision = -1;
	private long retryAfter;
	private int failures;
	private String lastFailure = "";
	protected AcknowledgedSavedData(long persistedRevision, Executor executor, Writer writer) {
		this.persistedRevision = persistedRevision; this.executor = Objects.requireNonNull(executor); this.writer = Objects.requireNonNull(writer);
	}
	protected abstract long revision();
	protected abstract boolean writable();
	public final long persistedRevision() { checkThread(); return persistedRevision; }
	public final String lastFailure() { checkThread(); return lastFailure; }
	@Override public final boolean isDirty() { checkThread(); return writable() && revision() > persistedRevision; }
	@Override public final void save(File file, HolderLookup.Provider registries) {
		checkThread(); poll();
		requestedRevision = Math.max(requestedRevision, revision());
		retry(file, registries);
	}
	public final void retry(File file, HolderLookup.Provider registries) {
		checkThread(); poll();
		if (!isDirty() || requestedRevision <= persistedRevision || flight != null || failures > 0 && System.nanoTime() - retryAfter < 0) return;
		start(file.toPath(), registries);
	}
	private void start(Path path, HolderLookup.Provider registries) {
		Path target = path.toAbsolutePath().normalize();
		if (boundPath != null && !boundPath.equals(target)) throw new IllegalArgumentException("Saved domain cannot change its file");
		boundPath = target;
		try {
			snapshotRevision = revision();
			byte[] encoded = CheckpointFiles.encode(save(new CompoundTag(), registries));
			flight = CompletableFuture.runAsync(() -> {
				try { writer.write(target, encoded); }
				catch (IOException failure) { throw new CompletionException(failure); }
			}, executor);
		} catch (IOException | RuntimeException failure) { failed(failure); }
	}
	public final void poll() {
		checkThread();
		if (flight == null || !flight.isDone()) return;
		try {
			flight.join(); persistedRevision = snapshotRevision; failures = 0; retryAfter = 0; lastFailure = "";
		} catch (CompletionException failure) { failed(failure.getCause()); }
		finally { flight = null; }
	}
	private void failed(Throwable failure) {
		if (failures == 0) LogUtils.getLogger().error("Cannot publish bee network checkpoint {}; retained for retry", boundPath, failure);
		failures = Math.min(8, failures + 1);
		retryAfter = System.nanoTime() + (1L << failures) * 50_000_000L;
		lastFailure = failure.toString();
	}
	/** 正常停服／迁移边界必须等待实际结果；异常由目录隔离，不能冒充保存成功。 */
	public final void flush(File file, HolderLookup.Provider registries) throws IOException {
		checkThread();
		if (!writable()) throw new IOException("Network domain is quarantined");
		if (flight != null) {
			try { flight.join(); } catch (CompletionException ignored) { /* poll 统一保留失败原因。 */ }
			poll();
		}
		if (isDirty()) {
			start(file.toPath(), registries);
			if (flight != null) {
				try { flight.join(); } catch (CompletionException ignored) { }
				poll();
			}
		}
		if (isDirty()) throw new IOException("Checkpoint was not saved: " + lastFailure);
	}
	protected final void checkThread() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Network persistence belongs to its server thread");
	}
}
