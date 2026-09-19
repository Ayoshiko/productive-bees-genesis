package com.ayoshiko.productivebeesgenesis.apiculture.persistence.read;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/** 每个服务只准入一个未消费完的文件，忙碌时调用方下一 tick 再试，不积压文件快照。 */
public final class CheckpointReadService implements AutoCloseable {
	public static final int BATCH_TARGET_BYTES = 32 * 1024;
	public static final int BATCH_EVENT_LIMIT = 256;
	public static final int QUEUED_BYTE_LIMIT = NbtReadMailbox.MAX_QUEUED_BYTES;
	public static final int QUEUED_BATCH_LIMIT = NbtReadMailbox.MAX_QUEUED_BATCHES;
	private final Thread owner = Thread.currentThread();
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
			new ArrayBlockingQueue<>(1), action -> {
				var worker = new Thread(action, "pbg-network-reader"); worker.setDaemon(true); return worker;
			}, new ThreadPoolExecutor.AbortPolicy());
	private NbtReadMailbox current;
	private boolean closed;
	private record ReadJob(Path file, NbtReadMailbox mailbox) implements Runnable {
		@Override public void run() { read(file, mailbox); }
	}
	public Optional<CheckpointReadSession> tryOpen(Path file) {
		check(); Objects.requireNonNull(file);
		if (closed) throw new IllegalStateException("Checkpoint reader is closed");
		if (current != null && !current.reusable()) return Optional.empty();
		var mailbox = new NbtReadMailbox(); var session = new CheckpointReadSession(mailbox);
		Path target = file.toAbsolutePath().normalize(); current = mailbox;
		try { executor.execute(new ReadJob(target, mailbox)); }
		catch (RuntimeException failure) { mailbox.failed(failure); mailbox.finishWorker(); }
		return Optional.of(session);
	}
	private static void read(Path target, NbtReadMailbox mailbox) {
		try {
			mailbox.begin();
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			var batches = new Batches(mailbox);
			long bytes;
			try (var raw = Files.newInputStream(target);
					var gzip = new GZIPInputStream(raw, 16 * 1024);
					var counted = new Counted(new DigestInputStream(new BufferedInputStream(gzip, 16 * 1024), digest));
					var input = new DataInputStream(counted)) {
				new NbtStreamReader(input, batches::accept).read(); bytes = counted.bytes;
			}
			batches.flush(); mailbox.verified(bytes, HexFormat.of().formatHex(digest.digest()));
		} catch (Exception failure) { mailbox.failed(failure); }
		finally { mailbox.finishWorker(); }
	}
	private static final class Batches {
		private final NbtReadMailbox mailbox;
		private final ArrayList<NbtReadEvent> events = new ArrayList<>(BATCH_EVENT_LIMIT);
		private int bytes;
		Batches(NbtReadMailbox mailbox) { this.mailbox = mailbox; }
		void accept(NbtReadEvent event) throws IOException {
			mailbox.check();
			if (!events.isEmpty() && (events.size() == BATCH_EVENT_LIMIT || bytes + event.accountedBytes() > BATCH_TARGET_BYTES)) flush();
			events.add(event); bytes += event.accountedBytes();
			// 单个合法 UTF 字符串可能大于目标批次；独占一批，不改变文件的格式容量。
			if (bytes >= BATCH_TARGET_BYTES) flush();
		}
		void flush() throws IOException {
			if (events.isEmpty()) return;
			var batch = new NbtReadBatch(events, bytes); events.clear(); bytes = 0; mailbox.offer(batch);
		}
	}
	private static final class Counted extends FilterInputStream {
		long bytes;
		Counted(InputStream input) { super(input); }
		@Override public int read() throws IOException { int value = in.read(); if (value >= 0) bytes++; return value; }
		@Override public int read(byte[] buffer, int offset, int length) throws IOException {
			int count = in.read(buffer, offset, length); if (count > 0) bytes += count; return count;
		}
	}
	@Override public void close() {
		check(); if (closed) return; closed = true;
		if (current != null) current.cancel();
		// 线程刚交接时任务可能尚在队列中；被停服移出的任务也必须进入终态。
		for (var queued : executor.shutdownNow()) {
			var job = (ReadJob) queued; job.mailbox().cancel(); job.mailbox().finishWorker();
		}
	}
	private void check() {
		if (Thread.currentThread() != owner) throw new IllegalStateException("Checkpoint reader belongs to its owner thread");
	}
}
