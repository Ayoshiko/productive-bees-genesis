package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.read.CheckpointReadService;
import com.ayoshiko.productivebeesgenesis.apiculture.port.PortState;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class PortCheckpointTest {
	@TempDir Path folder;
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private static NetworkCheckpoint fixture() {
		var root = rich(identity(), 4);
		for (var kind : PortState.Kind.values()) {
			var empty = PortState.empty(root.identity(), new Origin("minecraft:overworld", 1, 64, 2 + kind.ordinal()), kind);
			var next = kind == PortState.Kind.SUPPLY
					? empty.stack(0, new PortState.Stack(RAW, 1)).stack(8, new PortState.Stack(PRODUCT, 17))
					: empty.filter(0, PRODUCT).filter(9, new ProductKey(ProductKey.Kind.FLUID, ResourceLocation.parse("test:honey"), new CompoundTag()));
			root = root.withPort(null, empty);
			// 每次发布只接受一个连续修订，整份配置不能跳过中间版本。
			if (kind == PortState.Kind.SUPPLY) {
				var first = empty.stack(0, new PortState.Stack(RAW, 1));
				root = root.withPort(empty, first).withPort(first, next);
			} else {
				var first = empty.filter(0, PRODUCT);
				root = root.withPort(empty, first).withPort(first, next);
			}
		}
		return root;
	}
	private void verify(CompoundTag data, NetworkCheckpoint expected, NetworkCheckpointCodec codec) throws Exception {
		var original = data.copy();
		if (expected == null) assertThrows(IllegalArgumentException.class, () -> codec.decode(data));
		else assertEquals(expected, codec.decode(data));
		var wrapper = new CompoundTag();
		wrapper.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
		wrapper.put("data", data);
		var file = folder.resolve("ports.dat"); NbtIo.writeCompressed(wrapper, file);
		var bytes = Files.readAllBytes(file);
		try (var reader = new CheckpointReadService(); var decoder = codec.decoder(reader.tryOpen(file).orElseThrow())) {
			long deadline = System.nanoTime() + 10_000_000_000L;
			while (decoder.progress().state() == CheckpointDecoder.State.READING || decoder.progress().state() == CheckpointDecoder.State.VALIDATING) {
				assertTrue(System.nanoTime() < deadline);
				decoder.step(1, 1_000_000); Thread.yield();
			}
			if (expected == null) {
				assertEquals(CheckpointDecoder.State.FAILED, decoder.progress().state());
				assertThrows(IllegalStateException.class, decoder::checkpoint);
			} else {
				assertEquals(CheckpointDecoder.State.COMPLETE, decoder.progress().state(), decoder.progress().failure());
				assertEquals(expected, decoder.checkpoint());
			}
		}
		assertEquals(original, data); assertArrayEquals(bytes, Files.readAllBytes(file));
	}
	@Test void finiteSuppliesFiltersAndDisabledStateSurviveBothReadersAndStreamWriter() throws Exception {
		var root = fixture();
		verify(NetworkCheckpointCodec.encode(root), root, CODEC);
		var file = folder.resolve("stream.dat");
		CheckpointFiles.write(file, new CheckpointPayload.Network(root, SharedConstants.getCurrentVersion().getDataVersion().getVersion()));
		verify(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), root, CODEC);
	}
	private static CompoundTag supply(CompoundTag root) {
		for (var raw : root.getList("ports", Tag.TAG_COMPOUND)) {
			var data = ((CompoundTag) raw).getCompound("data");
			if (data.getString("kind").equals("SUPPLY")) return data;
		}
		throw new AssertionError("Missing supply fixture");
	}
	@Test void malformedOrForeignPortRecordsRemainQuarantinedWithoutChangingSource() throws Exception {
		List<Consumer<CompoundTag>> corruptions = List.of(
				root -> root.getList("ports", Tag.TAG_COMPOUND).add(root.getList("ports", Tag.TAG_COMPOUND).get(0).copy()),
				root -> supply(root).putUUID("id", UUID.randomUUID()),
				root -> supply(root).getCompound("origin").putString("dimension", "test:elsewhere"),
				root -> supply(root).getList("supplies", Tag.TAG_COMPOUND).remove(8),
				root -> supply(root).getList("supplies", Tag.TAG_COMPOUND).getCompound(0).putInt("count", 65),
				root -> supply(root).getList("supplies", Tag.TAG_COMPOUND).getCompound(0).putString("extra", "unexpected"),
				root -> supply(root).putLong("configuration", Long.MAX_VALUE),
				root -> supply(root).putString("kind", "INPUT"),
				root -> supply(root).getCompound("target").putString("unexpected", "unknown"));
		for (var corruption : corruptions) {
			var data = NetworkCheckpointCodec.encode(fixture()); corruption.accept(data); verify(data, null, CODEC);
		}
	}
	@Test void componentStackLimitIsEnforcedByBothReaders() throws Exception {
		var codec = new NetworkCheckpointCodec(key -> { }, item -> { }, key -> key.equals(RAW) ? 1 : 64);
		var root = fixture(); verify(NetworkCheckpointCodec.encode(root), root, codec);
		var invalid = NetworkCheckpointCodec.encode(root);
		supply(invalid).getList("supplies", Tag.TAG_COMPOUND).getCompound(0).putInt("count", 2);
		verify(invalid, null, codec);
	}
	@Test void staleUpdatesCannotOverwriteFiniteAssetsAndOldRootsStayImmutable() {
		var root = rich(identity(), 4);
		var empty = PortState.empty(root.identity(), new Origin("minecraft:overworld", 1, 64, 2), PortState.Kind.SUPPLY);
		var anchored = root.withPort(null, empty); var full = empty.stack(0, new PortState.Stack(RAW, 64));
		var stocked = anchored.withPort(empty, full);
		assertTrue(root.ports().isEmpty()); assertTrue(anchored.ports().get(empty.id()).supplies().get(0).empty());
		assertThrows(IllegalArgumentException.class, () -> stocked.withPort(empty, empty.enabled(true)));
		assertThrows(IllegalArgumentException.class, () -> root.withPort(null, full));
		assertEquals(root.ledger(), stocked.ledger()); assertEquals(root.energy(), stocked.energy());
		var disabled = full.enabled(false); assertSame(full, disabled);
		var enabled = full.enabled(true);
		assertEquals(1, enabled.configuration()); assertEquals(0, full.configuration());
		assertEquals(full.supplies(), enabled.supplies());
	}
}
