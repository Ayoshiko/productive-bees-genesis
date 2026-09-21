package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** 每服共享一份；等待项仅保留域引用，取得唯一写入名额后才捕获快照。 */
final class CheckpointSaveQueue {
	private static final int TICK_SCAN_LIMIT = 32;
	private final Thread owner = Thread.currentThread();
	private final Executor executor;
	private final AcknowledgedSavedData.Writer writer;
	private final ArrayDeque<AcknowledgedSavedData> waiting = new ArrayDeque<>();
	private final Set<AcknowledgedSavedData> enqueued = ConcurrentHashMap.newKeySet();
	private AcknowledgedSavedData active;
	private CompletableFuture<Void> flight;
	private long snapshotRevision;
	private long submitted;
	private long completed;
	CheckpointSaveQueue(Executor executor, AcknowledgedSavedData.Writer writer) {
		this.executor = Objects.requireNonNull(executor); this.writer = Objects.requireNonNull(writer);
	}
	void request(AcknowledgedSavedData data) {
		check(); enqueue(data);
		// 原生保存可能遍历大量域；每次回调只做 O(1) 入队，扫描留给服务器 tick。
		if (active == null && waiting.peekFirst() == data && data.ready()) { take(); start(data); }
	}
	private void enqueue(AcknowledgedSavedData data) {
		if (data != active && data.requested() && enqueued.add(data)) waiting.addLast(data);
	}
	private AcknowledgedSavedData take() { var data = waiting.removeFirst(); enqueued.remove(data); return data; }
	boolean pending() { check(); return active != null || !waiting.isEmpty(); }
	void tick() { tick(TICK_SCAN_LIMIT); }
	void tick(int maxChecks) {
		if (maxChecks <= 0) throw new IllegalArgumentException("Positive save scan budget required");
		check(); poll();
		if (active != null) return;
		int candidates = Math.min(maxChecks, waiting.size());
		for (int i = 0; i < candidates; i++) {
			var data = take();
			if (data.ready()) { start(data); return; }
			enqueue(data);
		}
	}
	private void start(AcknowledgedSavedData data) {
		if (active != null) throw new IllegalStateException("Only one checkpoint may own the buffers");
		try {
			var payload = data.capture(); var target = data.path(); var sink = writer;
			snapshotRevision = payload.revision(); active = data;
			flight = CompletableFuture.runAsync(() -> {
				try { sink.write(target, payload); }
				catch (IOException failure) { throw new CompletionException(failure); }
			}, executor);
			submitted++;
		} catch (RuntimeException failure) { active = null; flight = null; data.failed(failure); enqueue(data); }
	}
	void poll() {
		check();
		if (flight == null || !flight.isDone()) return;
		var data = active;
		try { flight.join(); data.acknowledged(snapshotRevision); completed++; }
		catch (CompletionException failure) { data.failed(failure.getCause()); }
		finally { flight = null; active = null; }
		enqueue(data);
	}
	private void awaitActive() {
		if (flight == null) return;
		try { flight.join(); } catch (CompletionException ignored) { /* poll 在所属线程处理失败回执。 */ }
		poll();
	}
	void flush(AcknowledgedSavedData data) throws IOException {
		check(); awaitActive();
		if (enqueued.remove(data)) waiting.remove(data);
		if (data.isDirty()) { start(data); awaitActive(); }
		if (data.isDirty()) throw new IOException("Checkpoint was not saved: " + data.lastFailure());
	}
	NetworkDirectory.SaveStatus status() {
		check(); return new NetworkDirectory.SaveStatus(waiting.size(), active == null ? 0 : 1,
				active == null ? 0 : CheckpointFiles.RESERVED_BYTES, submitted, completed);
	}
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Checkpoint queue belongs to its server thread");
	}
}
