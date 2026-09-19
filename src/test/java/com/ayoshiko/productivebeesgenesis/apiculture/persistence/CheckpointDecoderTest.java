package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointDecoderTest {
	@TempDir Path folder;
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private Path write(Consumer<CompoundTag> edit) throws Exception {
		var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
		root.put("data", NetworkCheckpointCodec.encode(rich(identity(), 4))); edit.accept(root);
		var path = folder.resolve("checkpoint.dat"); NbtIo.writeCompressed(root, path); return path;
	}
	private void finish(CheckpointDecoder decoder) {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
			assertTrue(System.nanoTime() < deadline); var before = decoder.progress().steps();
			decoder.step(1, 1_000_000); assertTrue(decoder.progress().steps() - before <= 1); Thread.yield();
		}
	}
	@Test void everyDomainRoundTripsOneEventAtATimeWithoutEarlyPublication() throws Exception {
		var expected = rich(identity(), 4); var file = folder.resolve("domain.dat");
		CheckpointFiles.write(file, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			assertThrows(IllegalStateException.class, decoder::checkpoint); finish(decoder);
			assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(expected, decoder.checkpoint()); assertTrue(decoder.progress().steps() > 100);
		}
	}
	@Test void rejectsSchemaTypesUnknownFieldsDuplicatesAndCrossRecordCorruption() throws Exception {
		java.util.List<Consumer<CompoundTag>> corruptions = java.util.List.of(
				root -> root.getCompound("data").putInt("schema", 999),
				root -> root.getCompound("data").putString("revision", "4"),
				root -> root.getCompound("data").putInt("unknown", 1),
				root -> root.getCompound("data").getList("balances", Tag.TAG_COMPOUND).add(root.getCompound("data").getList("balances", Tag.TAG_COMPOUND).get(0).copy()),
				root -> root.getCompound("data").getList("transactions", Tag.TAG_COMPOUND).getCompound(0).putLong("policy", 999),
				root -> root.getCompound("data").getList("lanes", Tag.TAG_COMPOUND).getCompound(0).putUUID("member", java.util.UUID.randomUUID()),
				root -> root.getCompound("data").getCompound("scheduler").getCompound("watermarks").putBoolean("orphan", true),
				root -> root.getCompound("data").getList("transactions", Tag.TAG_COMPOUND).getCompound(0).getList("inputs", Tag.TAG_COMPOUND).getCompound(0).putByteArray("amount", new byte[]{1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0})
		);
		for (var corruption : corruptions) {
			var file = write(corruption); byte[] original = Files.readAllBytes(file);
			try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
				finish(decoder); assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state());
				assertThrows(IllegalStateException.class, decoder::checkpoint); assertArrayEquals(original, Files.readAllBytes(file));
			}
		}
	}
	@Test void cancellationAndMissingContentDiscardTheCandidate() throws Exception {
		var file = write(root -> { });
		try (var reader = new CheckpointReadService(); var decoder = new CheckpointDecoder(reader.tryOpen(file).orElseThrow(), key -> { throw new IllegalArgumentException("Missing registry"); })) {
			finish(decoder); assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state()); assertThrows(IllegalStateException.class, decoder::checkpoint);
		}
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			decoder.step(2, 1_000_000); decoder.close(); assertEquals(CheckpointDecoder.State.CANCELLED, decoder.progress().state());
			assertThrows(IllegalStateException.class, decoder::checkpoint);
		}
	}
	@Test void duplicateRootFieldIsRejectedBeforeNbtCanOverwriteIt() throws Exception {
		var file = write(root -> { }); byte[] raw;
		try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(file))) { raw = input.readAllBytes(); }
		try (var out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(Files.newOutputStream(file)))) {
			out.write(raw, 0, raw.length - 1); out.writeByte(Tag.TAG_INT); out.writeUTF("DataVersion"); out.writeInt(1); out.writeByte(Tag.TAG_END);
		}
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			finish(decoder); assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state());
			assertTrue(decoder.progress().failure().contains("Duplicate"));
		}
	}
	@Test void oversizedComponentAndBigIntegerRemainExact() throws Exception {
		var components = new CompoundTag(); var bytes = new byte[1024 * 1024]; new java.util.Random(812).nextBytes(bytes); components.putByteArray("payload", bytes);
		var key = new com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey(RAW.kind(), RAW.id(), components);
		var amount = com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount.of(java.math.BigInteger.ONE.shiftLeft(1_000_000));
		var expected = new NetworkCheckpoint(identity(), 1, 1,
				new com.ayoshiko.productivebeesgenesis.apiculture.storage.LedgerCheckpoint(1, java.util.Map.of(key, amount), java.util.List.of()),
				java.util.List.of(), java.util.Set.of(), java.util.List.of(), java.util.List.of(), com.ayoshiko.productivebeesgenesis.apiculture.policy.SchedulerCheckpoint.EMPTY);
		var file = folder.resolve("large.dat"); CheckpointFiles.write(file, new CheckpointPayload.Network(expected, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		try (var reader = new CheckpointReadService(); var decoder = CODEC.decoder(reader.tryOpen(file).orElseThrow())) {
			finish(decoder); assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
			assertEquals(expected, decoder.checkpoint());
		}
	}
}
