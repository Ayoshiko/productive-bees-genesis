package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

/** 只表示文件语法与传输进度，VERIFIED 绝不授予领域 READY 或可写资格。 */
public final class CheckpointReadSession implements AutoCloseable {
	public enum State { READING, VERIFIED, FAILED, CANCELLED }
	public record Status(State state, int queuedBatches, int queuedBytes, int peakBatches, int peakBytes,
			long deliveredEvents, long decompressedBytes, String sha256, String failure, boolean workerFinished, long backpressureWaits) {
		public boolean drained() { return state == State.VERIFIED && workerFinished && queuedBatches == 0; }
	}
	private final Thread owner = Thread.currentThread();
	private final NbtReadMailbox mailbox;
	CheckpointReadSession(NbtReadMailbox mailbox) { this.mailbox = mailbox; }
	/** null 表示目前无可用批次；只有 status 能区分等待、成功和失败。 */
	public NbtReadBatch poll() { check(); return mailbox.poll(); }
	public Status status() { check(); return mailbox.status(); }
	@Override public void close() { check(); mailbox.cancel(); }
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Consume checkpoint input on its owner thread");
	}
}
