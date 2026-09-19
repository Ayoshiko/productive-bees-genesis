package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpoint;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointStream;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.*;
import com.google.gson.JsonObject;
import java.io.DataOutputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

final class CheckpointReadProbe {
	private static CheckpointReadService reader;
	private static CheckpointReadSession session;
	private static NbtEventDigest consumed;
	private static String expected;
	private static int ticks;
	private static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointDecoder decoder;
	private static NetworkCheckpoint expectedCheckpoint;
	private CheckpointReadProbe() { }
	static void start(Path file, NetworkCheckpoint checkpoint, net.minecraft.core.HolderLookup.Provider registries) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		NetworkCheckpointStream.write(checkpoint, new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
		expected = HexFormat.of().formatHex(digest.digest()); consumed = new NbtEventDigest(); ticks = 0;
		reader = new CheckpointReadService(); session = reader.tryOpen(file).orElseThrow();
		expectedCheckpoint = checkpoint;
		decoder = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointDecoder(session,
				key -> com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec.validatePersisted(key, registries));
	}
	static boolean advance(JsonObject report) throws Exception {
		if (reader == null) return true;
		ticks++; var progress = decoder.step(32, 1_000_000);
		require(progress.state() != com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointDecoder.State.FAILED, progress.failure());
		var status = session.status(); require(status.failure().isEmpty(), "Tick-driven checkpoint read failed: " + status.failure());
		require(status.peakBytes() <= CheckpointReadService.QUEUED_BYTE_LIMIT && status.peakBatches() <= CheckpointReadService.QUEUED_BATCH_LIMIT, "Read buffering exceeded budget");
		if (progress.state() != com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointDecoder.State.COMPLETE) return false;
		require(expected.equals(status.sha256()) && expectedCheckpoint.equals(decoder.checkpoint()), "Restoration lost checkpoint fields");
		report.addProperty("tickDrivenFullDomainDecode", true); report.addProperty("decodeMaxStepNanos", progress.maxStepNanos());
		report.addProperty("tickDrivenCheckpointRead", true); report.addProperty("readProbeTicks", ticks);
		report.addProperty("readProbeBytes", status.decompressedBytes()); close(); return true;
	}
	static void close() {
		if (decoder != null) decoder.close(); decoder = null; expectedCheckpoint = null;
		if (reader != null) reader.close(); reader = null; session = null; consumed = null; expected = null;
	}
}
