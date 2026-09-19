package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadSession.State;

/** 字节和批次数双重背压；只有后台生产者可以等待，所属线程 poll 永不等待读盘。 */
final class NbtReadMailbox {
	static final int MAX_QUEUED_BYTES = 512 * 1024;
	static final int MAX_QUEUED_BATCHES = 8;
	private final ArrayDeque<NbtReadBatch> queue = new ArrayDeque<>();
	private State state = State.READING;
	private Thread worker;
	private boolean workerFinished;
	private int queuedBytes;
	private int peakBytes;
	private int peakBatches;
	private long deliveredEvents;
	private long decompressedBytes;
	private String digest = "";
	private String failure = "";
	private long backpressureWaits;
	synchronized void begin() throws InterruptedIOException { check(); worker = Thread.currentThread(); }
	synchronized void check() throws InterruptedIOException {
		if (state != State.READING || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Checkpoint read cancelled");
	}
	synchronized void offer(NbtReadBatch batch) throws InterruptedIOException {
		if (batch.accountedBytes() > MAX_QUEUED_BYTES) throw new IllegalArgumentException("Read event exceeds queue budget");
		check();
		while (queue.size() >= MAX_QUEUED_BATCHES || queuedBytes + batch.accountedBytes() > MAX_QUEUED_BYTES) {
			backpressureWaits++;
			try { wait(); } catch (InterruptedException cancelled) {
				Thread.currentThread().interrupt(); throw new InterruptedIOException("Checkpoint read interrupted during backpressure");
			}
			check();
		}
		queue.addLast(batch); queuedBytes += batch.accountedBytes();
		peakBytes = Math.max(peakBytes, queuedBytes); peakBatches = Math.max(peakBatches, queue.size());
	}
	synchronized NbtReadBatch poll() {
		var batch = queue.pollFirst();
		if (batch != null) { queuedBytes -= batch.accountedBytes(); deliveredEvents += batch.events().size(); notifyAll(); }
		return batch;
	}
	synchronized void verified(long bytes, String sha256) {
		if (state != State.READING) return;
		decompressedBytes = bytes; digest = sha256; state = State.VERIFIED;
	}
	synchronized void failed(Throwable error) {
		if (state != State.READING) return;
		failure = error.toString(); state = State.FAILED; discard();
	}
	synchronized void finishWorker() { worker = null; workerFinished = true; notifyAll(); }
	synchronized void cancel() {
		if (state != State.FAILED) state = State.CANCELLED;
		discard();
		if (worker != null) worker.interrupt();
	}
	private void discard() { queue.clear(); queuedBytes = 0; notifyAll(); }
	synchronized boolean reusable() { return workerFinished && queue.isEmpty(); }
	synchronized CheckpointReadSession.Status status() {
		return new CheckpointReadSession.Status(state, queue.size(), queuedBytes, peakBatches, peakBytes, deliveredEvents,
				decompressedBytes, digest, failure, workerFinished, backpressureWaits);
	}
}
