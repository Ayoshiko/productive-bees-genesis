package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.*;
import java.io.*;
import java.nio.ReadOnlyBufferException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointReadTest {
	@TempDir Path folder;
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	@FunctionalInterface interface Write { void run(DataOutputStream output) throws IOException; }
	private Path file(String name, Write writer) throws IOException {
		Path path = folder.resolve(name + ".dat");
		try (var output = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(path)))) { writer.run(output); }
		return path;
	}
	private static void root(DataOutput output) throws IOException { output.writeByte(Tag.TAG_COMPOUND); output.writeUTF(""); }
	private static void waitFor(BooleanSupplier condition) {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() - deadline >= 0) fail("Checkpoint worker did not advance before deadline");
			LockSupport.parkNanos(200_000);
		}
	}
	private static byte[] copy(CheckpointReadSession session) throws IOException {
		var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (true) {
			var batch = session.poll();
			if (batch != null) for (var event : batch.events()) write(event, output);
			var status = session.status();
			assertNotEquals(CheckpointReadSession.State.FAILED, status.state(), status.failure());
			assertNotEquals(CheckpointReadSession.State.CANCELLED, status.state());
			if (status.drained()) return bytes.toByteArray();
			if (System.nanoTime() - deadline >= 0) fail("Checkpoint read timed out");
			if (batch == null) LockSupport.parkNanos(200_000);
		}
	}
	/** 测试中重建标准字节，独立交给原生 NBT 和严格领域 codec 验证；生产端不重建整域。 */
	private static void write(NbtReadEvent event, DataOutputStream output) throws IOException {
		switch (event) {
			case NbtReadEvent.Start start -> {
				if (start.name() != null) { output.writeByte(start.type()); output.writeUTF(start.name()); }
				if (start.type() == Tag.TAG_LIST) output.writeByte(start.elementType());
				if (start.type() != Tag.TAG_COMPOUND) output.writeInt(start.length());
			}
			case NbtReadEvent.End end -> { if (end.type() == Tag.TAG_COMPOUND) output.writeByte(Tag.TAG_END); }
			case NbtReadEvent.Scalar scalar -> {
				if (scalar.name() != null) { output.writeByte(scalar.value().getId()); output.writeUTF(scalar.name()); }
				scalar.value().write(output);
			}
			case NbtReadEvent.ArrayChunk chunk -> {
				var view = chunk.bytes(); byte[] bytes = new byte[view.remaining()]; view.get(bytes); output.write(bytes);
			}
		}
	}
	private static byte[] plain(Path file) throws IOException {
		try (var input = new GZIPInputStream(Files.newInputStream(file))) { return input.readAllBytes(); }
	}
	@Test void realCheckpointAndDirectoryKeepEveryFieldAndExactQuantity() throws Exception {
		var checkpoint = CheckpointTestData.rich(CheckpointTestData.identity(), 9);
		var path = folder.resolve("network.dat"); CheckpointFiles.write(path, new CheckpointPayload.Network(checkpoint, 3955));
		try (var reader = new CheckpointReadService()) {
			var session = reader.tryOpen(path).orElseThrow(); byte[] bytes = copy(session);
			assertArrayEquals(plain(path), bytes);
			assertEquals(checkpoint, new NetworkCheckpointCodec(key -> {}).decode(NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap()).getCompound("data")));
			assertEquals(bytes.length, session.status().decompressedBytes());
			assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), session.status().sha256());
			var directory = NetworkDirectoryData.create(Runnable::run, CheckpointFiles::write); directory.add(checkpoint.identity());
			var index = folder.resolve("directory.dat"); directory.flush(index.toFile(), null);
			assertArrayEquals(plain(index), copy(reader.tryOpen(index).orElseThrow()));
		}
	}
	@Test void allTagKindsNestedListsAndChunkedArraysMatchNativeNbt() throws Exception {
		var tag = new CompoundTag(); tag.putByte("byte", (byte) -7); tag.putShort("short", (short) 300);
		tag.putInt("int", Integer.MIN_VALUE); tag.putLong("long", Long.MAX_VALUE); tag.putFloat("float", -1.5f); tag.putDouble("double", 1.0 / 3);
		tag.putString("string", "蜜蜂\u0000\ud83d\udc1d\ud800"); tag.putString("max", "x".repeat(65535));
		byte[] array = new byte[1024 * 1024 + 3]; new Random(42).nextBytes(array); tag.putByteArray("bytes", array);
		tag.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}); tag.putLongArray("longs", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE});
		tag.putByteArray("emptyArray", new byte[0]); tag.put("emptyList", new ListTag()); tag.put("emptyCompound", new CompoundTag());
		var inner = new ListTag(); inner.add(StringTag.valueOf("nested")); var outer = new ListTag(); outer.add(inner); outer.add(new ListTag()); tag.put("nested", outer);
		var path = folder.resolve("types.dat"); NbtIo.writeCompressed(tag, path);
		try (var reader = new CheckpointReadService()) { assertArrayEquals(plain(path), copy(reader.tryOpen(path).orElseThrow())); }
	}
	@Test void duplicateFieldNamesArePreservedForTheDomainValidator() throws Exception {
		var path = file("duplicates", out -> { root(out); for (int i = 0; i < 2; i++) { out.writeByte(3); out.writeUTF("same"); out.writeInt(i); } out.writeByte(0); });
		try (var reader = new CheckpointReadService()) { assertArrayEquals(plain(path), copy(reader.tryOpen(path).orElseThrow())); }
	}
	@Test void syntaxErrorsAndHugeDeclaredArraysFailWithoutAllocatingTheDeclaredSize() throws Exception {
		List<Write> broken = List.of(
			out -> { out.writeByte(3); out.writeUTF(""); out.writeInt(1); },
			out -> { root(out); out.writeByte(13); out.writeUTF("invalid"); },
			out -> { root(out); out.writeByte(7); out.writeUTF("negative"); out.writeInt(-1); },
			out -> { root(out); out.writeByte(12); out.writeUTF("huge"); out.writeInt(Integer.MAX_VALUE); out.writeLong(1); },
			out -> { root(out); out.writeByte(9); out.writeUTF("negative"); out.writeByte(10); out.writeInt(-1); },
			out -> { root(out); out.writeByte(9); out.writeUTF("noType"); out.writeByte(0); out.writeInt(1); },
			out -> { root(out); out.writeByte(9); out.writeUTF("badType"); out.writeByte(13); out.writeInt(0); },
			out -> { root(out); out.writeByte(8); out.writeUTF("utf"); out.writeShort(1); out.writeByte(0xff); },
			out -> { root(out); out.writeByte(0); out.writeByte(1); },
			out -> { root(out); out.writeByte(3); out.writeUTF("missing"); });
		try (var reader = new CheckpointReadService()) {
			for (int i = 0; i < broken.size(); i++) {
				var session = reader.tryOpen(file("bad" + i, broken.get(i))).orElseThrow(); assertFailed(session);
			}
			assertFailed(reader.tryOpen(folder.resolve("missing.dat")).orElseThrow());
		}
	}
	private static void assertFailed(CheckpointReadSession session) {
		waitFor(() -> { session.poll(); return session.status().workerFinished(); });
		assertEquals(CheckpointReadSession.State.FAILED, session.status().state());
		assertFalse(session.status().failure().isEmpty()); assertEquals("", session.status().sha256());
		assertFalse(session.status().drained()); assertNull(session.poll());
	}
	@Test void crcAndTruncatedTrailerFailEvenAfterTheRootHasEnded() throws Exception {
		var path = file("valid", out -> { root(out); out.writeByte(0); }); var original = Files.readAllBytes(path);
		try (var reader = new CheckpointReadService()) {
			for (int removed : new int[] {1, 8, 9}) {
				var broken = folder.resolve("truncated" + removed); Files.write(broken, Arrays.copyOf(original, original.length - removed));
				assertFailed(reader.tryOpen(broken).orElseThrow()); assertArrayEquals(Arrays.copyOf(original, original.length - removed), Files.readAllBytes(broken));
			}
			byte[] badCrc = original.clone(); badCrc[badCrc.length - 8] ^= 1; var broken = folder.resolve("crc"); Files.write(broken, badCrc);
			assertFailed(reader.tryOpen(broken).orElseThrow()); assertArrayEquals(badCrc, Files.readAllBytes(broken));
		}
	}
	@Test void depthLimitMatchesNativeWithoutRecursiveJavaCalls() throws Exception {
		try (var reader = new CheckpointReadService()) {
			for (int depth : new int[] {512, 513}) {
				var path = file("depth" + depth, out -> { root(out); for (int i = 1; i < depth; i++) { out.writeByte(10); out.writeUTF("nested"); } for (int i = 0; i < depth; i++) out.writeByte(0); });
				var session = reader.tryOpen(path).orElseThrow();
				if (depth == 512) assertArrayEquals(plain(path), copy(session)); else assertFailed(session);
			}
		}
	}
	@Test void slowConsumerBackpressureAndCancellationReleaseTheSingleFileSlot() throws Exception {
		var path = file("many", out -> {
			root(out); out.writeByte(9); out.writeUTF("records"); out.writeByte(10); out.writeInt(30_000);
			for (int i = 0; i < 30_000; i++) { out.writeByte(3); out.writeUTF("index"); out.writeInt(i); out.writeByte(0); } out.writeByte(0);
		});
		try (var reader = new CheckpointReadService()) {
			var session = reader.tryOpen(path).orElseThrow(); waitFor(() -> session.status().backpressureWaits() > 0);
			assertEquals(CheckpointReadService.QUEUED_BATCH_LIMIT, session.status().queuedBatches());
			assertTrue(session.status().peakBytes() <= CheckpointReadService.QUEUED_BYTE_LIMIT);
			assertTrue(reader.tryOpen(path).isEmpty()); session.close(); waitFor(() -> session.status().workerFinished());
			assertEquals(CheckpointReadSession.State.CANCELLED, session.status().state()); assertNull(session.poll());
			assertEquals(0, session.status().queuedBytes()); assertFalse(session.status().drained());
			assertArrayEquals(plain(path), copy(reader.tryOpen(path).orElseThrow())); assertNull(session.poll());
		}
	}
	@Test void maximumUtfEventsAreBoundedByBytesBeforeTheBatchCountLimit() throws Exception {
		var path = file("wideUtf", out -> {
			root(out); for (int i = 0; i < 12; i++) { out.writeByte(8); out.writeUTF("n".repeat(65535)); out.writeUTF("v".repeat(65535)); } out.writeByte(0);
		});
		try (var reader = new CheckpointReadService()) {
			var session = reader.tryOpen(path).orElseThrow(); waitFor(() -> session.status().backpressureWaits() > 0);
			assertTrue(session.status().queuedBatches() < CheckpointReadService.QUEUED_BATCH_LIMIT);
			assertTrue(session.status().peakBytes() <= CheckpointReadService.QUEUED_BYTE_LIMIT);
			assertArrayEquals(plain(path), copy(session));
		}
	}
	@Test void eventsAreImmutableAndOwnerThreadCannotBeBypassed() throws Exception {
		var path = file("array", out -> { root(out); out.writeByte(7); out.writeUTF("a"); out.writeInt(1); out.writeByte(7); out.writeByte(0); });
		try (var reader = new CheckpointReadService()) {
			var session = reader.tryOpen(path).orElseThrow(); waitFor(() -> session.status().workerFinished());
			assertEquals(CheckpointReadSession.State.VERIFIED, session.status().state());
			assertFalse(session.status().drained()); assertTrue(reader.tryOpen(path).isEmpty());
			var batch = session.poll(); assertNotNull(batch); assertThrows(UnsupportedOperationException.class, () -> batch.events().clear());
			var chunk = (NbtReadEvent.ArrayChunk) batch.events().stream().filter(NbtReadEvent.ArrayChunk.class::isInstance).findFirst().orElseThrow();
			assertThrows(ReadOnlyBufferException.class, () -> chunk.bytes().put(0, (byte) 0)); assertEquals(7, chunk.bytes().get());
			CompletableFuture.runAsync(() -> {
				assertThrows(IllegalStateException.class, session::poll); assertThrows(IllegalStateException.class, session::close);
				assertThrows(IllegalStateException.class, () -> reader.tryOpen(path));
			}).join();
		}
	}
	@Test void aLateFailureInvalidatesAlreadyDeliveredPartialInputAndAllowsExplicitRetry() throws Exception {
		var path = file("lateFailure", out -> { root(out); out.writeByte(7); out.writeUTF("large"); out.writeInt(2_000_000); out.write(new byte[2_000_000]); out.writeByte(0); });
		byte[] valid = Files.readAllBytes(path), damaged = valid.clone(); damaged[damaged.length - 8] ^= 1; Files.write(path, damaged);
		try (var reader = new CheckpointReadService()) {
			var session = reader.tryOpen(path).orElseThrow(); waitFor(() -> session.status().backpressureWaits() > 0);
			assertNotNull(session.poll()); assertTrue(session.status().deliveredEvents() > 0);
			assertFailed(session); assertArrayEquals(damaged, Files.readAllBytes(path));
			Files.write(path, valid); assertArrayEquals(plain(path), copy(reader.tryOpen(path).orElseThrow()));
			assertEquals(CheckpointReadSession.State.FAILED, session.status().state()); assertNull(session.poll());
		}
	}
	@Test void serviceCloseCancelsAProducerBlockedByTheConsumer() throws Exception {
		var path = file("close", out -> { root(out); out.writeByte(7); out.writeUTF("array"); out.writeInt(2_000_000); out.write(new byte[2_000_000]); out.writeByte(0); });
		var reader = new CheckpointReadService(); var session = reader.tryOpen(path).orElseThrow();
		try { waitFor(() -> session.status().backpressureWaits() > 0); }
		finally { reader.close(); }
		waitFor(() -> session.status().workerFinished()); assertEquals(0, session.status().queuedBytes());
		assertThrows(IllegalStateException.class, () -> reader.tryOpen(path)); reader.close();
	}
	@Test void closeDuringWorkerHandoffAlwaysFinishesTheNewSession() throws Exception {
		var path = file("handoff", out -> { root(out); out.writeByte(0); });
		for (int i = 0; i < 50; i++) {
			try (var reader = new CheckpointReadService()) {
				copy(reader.tryOpen(path).orElseThrow());
				var next = reader.tryOpen(path).orElseThrow(); reader.close();
				waitFor(() -> next.status().workerFinished());
				assertEquals(CheckpointReadSession.State.CANCELLED, next.status().state()); assertNull(next.poll());
			}
		}
	}
}
