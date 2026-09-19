package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.*;
import com.ayoshiko.productivebeesgenesis.apiculture.policy.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckpointStreamingTest {
	@TempDir Path folder;
	@BeforeAll static void initializeVersion() { SharedConstants.tryDetectVersion(); }
	private static final class ManualExecutor implements Executor {
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		@Override public void execute(Runnable action) { tasks.add(action); }
		void complete() { tasks.remove().run(); }
	}
	private static CompoundTag stream(NetworkCheckpoint checkpoint) throws IOException {
		var bytes = new ByteArrayOutputStream();
		NetworkCheckpointStream.write(checkpoint, new CheckpointDataOutput(new DataOutputStream(bytes)));
		try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return NbtIo.read(input); }
	}
	@Test void standardNbtRoundTripCoversEveryDomainAndEmptyCollections() throws Exception {
		for (var checkpoint : List.of(rich(identity(), 1), NetworkCheckpoint.empty(identity()))) {
			var root = stream(checkpoint);
			assertEquals(SharedConstants.getCurrentVersion().getDataVersion().getVersion(), root.getInt("DataVersion"));
			assertEquals(NetworkCheckpointCodec.encode(checkpoint), root.getCompound("data"));
			assertEquals(checkpoint, CODEC.decode(root.getCompound("data")));
		}
	}
	@Test void wideTransactionsReserveLayersAndCapabilitiesAreStreamedAndRemainExact() throws Exception {
		var balances = new ConcurrentHashMap<ProductKey, ProductAmount>();
		var reserveEntries = new ConcurrentHashMap<ProductMatcher, ReserveLimit>();
		var effects = new ConcurrentHashMap<String, Integer>(); var alternatives = new ArrayList<WorkCapacity>();
		for (int i = 0; i < 4096; i++) {
			var components = new CompoundTag(); components.putInt("variant", i); components.putString("name", "蜜蜂\u0000\ud83d\udc1d");
			var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:raw"), components);
			balances.put(key, ProductAmount.of(BigInteger.ONE.shiftLeft(512).add(BigInteger.valueOf(i))));
			reserveEntries.put(new ProductMatcher(ProductMatcher.Mode.EXACT, key), ReserveLimit.ALL); effects.put("effect-" + i, i);
		}
		var work = new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:recipe", 3, "default"), 1, 30, 1, 1, 1, 0, effects);
		alternatives.add(work);
		for (int i = 0; i < 1024; i++) alternatives.add(new WorkCapacity(new WorkKey(WorkKey.Kind.CENTRIFUGE_RECIPE, "test:r" + i, 3, "default"), 1, 30, 1, 1, 1, 0, Map.of()));
		var member = new MemberCapabilitySnapshot(UUID.randomUUID(), 1, "test:machine", new MemberCapabilitySnapshot.Origin("minecraft:overworld", 2, 64, 1),
				MemberCapabilitySnapshot.Availability.OFFLINE, 0, 1, alternatives);
		var reserves = new ReservePolicy(ReservePolicy.Scope.LOCAL_PROCESSING, new ReservePolicy.Layer(ReserveLimit.NONE, reserveEntries), ReservePolicy.Layer.NONE);
		var rule = new ProcessingRule("match", 1, true, 0, 1, 1, new ProcessingRule.Match(new ProductMatcher(ProductMatcher.Mode.BASE_ITEM, RAW)), reserves);
		var pending = new LedgerCheckpoint.Pending(UUID.randomUUID(), 3, LedgerTransaction.State.PAID, balances, balances);
		var checkpoint = new NetworkCheckpoint(identity(), 1, 3, new LedgerCheckpoint(1, balances, List.of(pending)), List.of(), Set.of(), List.of(member),
				List.of(new VirtualLaneState(member.memberId(), 1, 0, work, 17)), new SchedulerCheckpoint(List.of(rule), ProcessingRuleScheduler.Mode.FAIR, Map.of(), "", 0));
		var decoded = CODEC.decode(stream(checkpoint).getCompound("data")); assertEquals(checkpoint, decoded);
		var policy = new ProductPolicyRegistry(new ProductPolicySnapshot(3, List.of(), List.of(new DynamicProductRule("test:all", RAW.kind(), RAW.id(), false, key -> true))));
		var ledger = ProductLedger.restore(policy, 2, decoded.ledger()); var job = ledger.pending(pending.id());
		assertTrue(ledger.commit(job)); assertTrue(ledger.commit(job)); assertEquals(balances, ledger.checkpoint().balances());
	}
	@Test void largeSingleComponentAndBigAmountKeepTheirExactBytes() throws Exception {
		var bytes = new byte[1024 * 1024]; new Random(71).nextBytes(bytes);
		var components = new CompoundTag(); components.putByteArray("bytes", bytes);
		components.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
		components.putLongArray("longs", new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
		var key = new ProductKey(ProductKey.Kind.FLUID, ResourceLocation.parse("test:fluid"), components);
		var value = ProductAmount.of(BigInteger.ONE.shiftLeft(1_000_000));
		var checkpoint = new NetworkCheckpoint(identity(), 1, 0, new LedgerCheckpoint(1, Map.of(key, value), List.of()), List.of(), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		assertEquals(checkpoint, CODEC.decode(stream(checkpoint).getCompound("data")));
		var file = folder.resolve("large.dat"); CheckpointFiles.write(file, new CheckpointPayload.Network(checkpoint, 3955));
		assertEquals(checkpoint, CODEC.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data")));
	}
	@Test void directoryFreezesBeforeBackgroundIteration() throws Exception {
		var executor = new ManualExecutor(); var queue = new CheckpointSaveQueue(executor, CheckpointFiles::write);
		var directory = NetworkDirectoryData.create(queue); directory.add(identity()); var frozen = directory.checkpoint();
		var file = folder.resolve("index.dat"); directory.save(file.toFile(), null);
		var id = identity(); directory.add(new NetworkIdentity(id.networkId(), id.controllerId(), id.ownerId(), 2,
				new MemberCapabilitySnapshot.Origin("minecraft:overworld", 3, 64, 1)));
		executor.complete(); queue.poll(); assertTrue(directory.isDirty()); assertEquals(1, directory.persistedRevision());
		var decoded = NetworkDirectoryData.load(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"), queue);
		assertEquals(frozen, decoded.checkpoint()); directory.save(file.toFile(), null); executor.complete(); queue.poll();
		assertFalse(directory.isDirty());
	}
	@Test void waitingRequestsCoalesceWithoutRetainingAnotherSnapshotOrBlockingFairness() throws Exception {
		var executor = new ManualExecutor(); var queue = new CheckpointSaveQueue(executor, CheckpointFiles::write);
		var first = NetworkSavedData.create(rich(identity(), 1), queue);
		var second = NetworkSavedData.create(rich(identity(), 1), queue);
		var firstFile = folder.resolve("first.dat").toFile(); var secondFile = folder.resolve("second.dat").toFile();
		first.save(firstFile, null); second.save(secondFile, null);
		for (int i = 2; i <= 100; i++) {
			first.publish(rich(first.identity(), i)); first.save(firstFile, null);
			second.publish(rich(second.identity(), i)); second.save(secondFile, null);
		}
		assertEquals(1, executor.tasks.size()); assertEquals(1, queue.status().activeSnapshots());
		assertEquals(1, queue.status().waitingDomains()); assertEquals(32 * 1024, queue.status().reservedBufferBytes());
		executor.complete(); queue.tick(); assertEquals(1, first.persistedRevision()); assertTrue(first.isDirty());
		executor.complete(); queue.tick(); assertEquals(100, second.persistedRevision());
		executor.complete(); queue.tick(); assertEquals(100, first.persistedRevision());
		assertEquals(0, queue.status().activeSnapshots()); assertEquals(0, queue.status().waitingDomains());
		assertEquals(first.checkpoint(), CODEC.decode(NbtIo.readCompressed(firstFile.toPath(), NbtAccounter.unlimitedHeap()).getCompound("data")));
	}
	@Test void rejectionAndPartialEncodingFailureReleaseTheSlotAndPreserveTheOldFile() throws Exception {
		var file = folder.resolve("network.dat"); var checkpoint = rich(identity(), 1);
		var good = NetworkSavedData.create(checkpoint, Runnable::run, CheckpointFiles::write); good.flush(file.toFile(), null);
		byte[] previous = Files.readAllBytes(file); var reject = new AtomicBoolean(true);
		var queue = new CheckpointSaveQueue(action -> { if (reject.get()) throw new RejectedExecutionException("injected"); action.run(); }, CheckpointFiles::write);
		var data = NetworkSavedData.create(rich(checkpoint.identity(), 2), queue);
		data.save(file.toFile(), null); assertTrue(data.isDirty()); assertEquals(0, queue.status().reservedBufferBytes());
		assertArrayEquals(previous, Files.readAllBytes(file)); reject.set(false); data.flush(file.toFile(), null); assertFalse(data.isDirty());
		previous = Files.readAllBytes(file);
		var badTransfer = new TransferStaging.View(UUID.randomUUID(), "x".repeat(65536), PRODUCT, TransferStaging.Direction.EXPORT, 1,
				ProductAmount.of(1), TransferStaging.Phase.UNKNOWN, "");
		var bad = new NetworkCheckpoint(checkpoint.identity(), 3, 3, checkpoint.ledger(), List.of(badTransfer), Set.of(), List.of(), List.of(), SchedulerCheckpoint.EMPTY);
		data.publish(bad); assertThrows(IOException.class, () -> data.flush(file.toFile(), null));
		assertTrue(data.isDirty()); assertEquals(2, data.persistedRevision()); assertArrayEquals(previous, Files.readAllBytes(file));
		assertEquals(0, queue.status().reservedBufferBytes());
		data.publish(rich(checkpoint.identity(), 4)); data.flush(file.toFile(), null); assertEquals(4, data.persistedRevision());
	}
	@Test void actualWorkerCanEncodeWhileOwnerAdvancesAndShutdownFlushesTheLatestVersion() throws Exception {
		Thread owner = Thread.currentThread(); var encodedOffThread = new AtomicBoolean();
		try (var executor = Executors.newSingleThreadExecutor()) {
			var queue = new CheckpointSaveQueue(executor, (file, payload) -> {
				assertNotSame(owner, Thread.currentThread()); encodedOffThread.set(true); CheckpointFiles.write(file, payload);
			});
			var data = NetworkSavedData.create(rich(identity(), 1), queue); var file = folder.resolve("worker.dat");
			data.save(file.toFile(), null); data.publish(rich(data.identity(), 2)); data.flush(file.toFile(), null);
			assertTrue(encodedOffThread.get()); assertEquals(2, data.persistedRevision()); assertEquals(0, queue.status().activeSnapshots());
			assertEquals(data.checkpoint(), CODEC.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data")));
		}
	}
	@Test void failedDomainBacksOffWhileAnotherDomainSavesThenRetriesWithoutANewRequest() throws Exception {
		var executor = new ManualExecutor(); var failOnce = new AtomicBoolean(true);
		var queue = new CheckpointSaveQueue(executor, (path, payload) -> {
			if (path.getFileName().toString().equals("retry.dat") && failOnce.getAndSet(false)) throw new IOException("temporary disk failure");
			CheckpointFiles.write(path, payload);
		});
		var first = NetworkSavedData.create(rich(identity(), 1), queue); var second = NetworkSavedData.create(rich(identity(), 1), queue);
		var file = folder.resolve("retry.dat"); first.save(file.toFile(), null); second.save(folder.resolve("other.dat").toFile(), null);
		executor.complete(); queue.tick(); assertTrue(first.isDirty()); assertFalse(first.lastFailure().isEmpty());
		executor.complete(); queue.poll(); assertFalse(second.isDirty());
		Thread.sleep(120); queue.tick(); executor.complete(); queue.poll();
		assertFalse(first.isDirty()); assertTrue(first.lastFailure().isEmpty());
		assertEquals(first.checkpoint(), CODEC.decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data")));
	}
	@Test void modifiedUtfMatchesJavaIncludingNullSurrogatesAndLimit() throws Exception {
		for (var text : List.of("", "ascii", "蜜蜂\u0000\ud83d\udc1d\ud800", "x".repeat(65535))) {
			var expected = new ByteArrayOutputStream(); new DataOutputStream(expected).writeUTF(text);
			var actual = new ByteArrayOutputStream(); new CheckpointDataOutput(new DataOutputStream(actual)).writeUTF(text);
			assertArrayEquals(expected.toByteArray(), actual.toByteArray());
		}
		assertThrows(UTFDataFormatException.class, () -> new CheckpointDataOutput(new DataOutputStream(OutputStream.nullOutputStream())).writeUTF("蜜".repeat(22000)));
	}
}
