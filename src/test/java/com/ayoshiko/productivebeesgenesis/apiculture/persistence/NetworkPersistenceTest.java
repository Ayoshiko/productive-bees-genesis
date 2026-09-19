package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.ayoshiko.productivebeesgenesis.apiculture.persistence.CheckpointTestData.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkPersistenceTest {
	@TempDir Path folder;
	@BeforeAll static void initializeVersion() { SharedConstants.tryDetectVersion(); }
	private static final class ManualExecutor implements Executor {
		private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
		@Override public void execute(Runnable action) { queue.add(action); }
		void complete() { queue.remove().run(); }
	}
	private NetworkDirectory directory() { return new NetworkDirectory(folder, null, Runnable::run, null, CODEC, CheckpointFiles::write); }
	private static NetworkOpenHandle await(NetworkDirectory directory, NetworkOpenHandle handle) {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (handle.pending()) { assertTrue(System.nanoTime() < deadline); directory.tick(); Thread.yield(); }
		return handle;
	}
	private static NetworkSavedData create(NetworkDirectory directory, NetworkIdentity identity) { return await(directory, directory.create(identity)).ready(); }
	@Test void oldSaveReceiptCannotClearNewerRevisionAndOnlyOneWriteIsInFlight() throws Exception {
		var executor = new ManualExecutor(); var identity = identity(); var original = rich(identity, 1);
		var data = NetworkSavedData.create(original, executor, CheckpointFiles::write); var file = folder.resolve("network.dat").toFile();
		data.save(file, null); assertEquals(-1, data.persistedRevision()); assertTrue(data.isDirty());
		var next = rich(identity, 2); data.publish(next); data.save(file, null); assertEquals(1, executor.queue.size());
		executor.complete(); data.poll(); assertEquals(1, data.persistedRevision()); assertTrue(data.isDirty());
		assertEquals(original, CODEC.decode(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()).getCompound("data")));
		data.save(file, null); executor.complete(); data.poll(); assertFalse(data.isDirty()); assertEquals(2, data.persistedRevision());
		assertEquals(next, CODEC.decode(NbtIo.readCompressed(file.toPath(), NbtAccounter.unlimitedHeap()).getCompound("data")));
	}
	@Test void writeFailureRetainsDirtyAndRetryDoesNotLoseThePriorFile() throws Exception {
		var file = folder.resolve("network.dat"); var identity = identity();
		var data = NetworkSavedData.create(rich(identity, 1), Runnable::run, CheckpointFiles::write); data.flush(file.toFile(), null);
		byte[] previous = Files.readAllBytes(file); var attempts = new AtomicInteger();
		var failing = NetworkSavedData.create(rich(identity, 2), Runnable::run, (path, bytes) -> {
			if (attempts.incrementAndGet() == 1) throw new IOException("Injected unavailable disk"); CheckpointFiles.write(path, bytes);
		});
		assertThrows(IOException.class, () -> failing.flush(file.toFile(), null)); assertTrue(failing.isDirty());
		assertArrayEquals(previous, Files.readAllBytes(file)); assertFalse(failing.lastFailure().isEmpty());
		failing.flush(file.toFile(), null); assertFalse(failing.isDirty()); assertEquals(2, attempts.get());
	}
	@Test void explicitCreateAndFreshDirectoryLoadPreserveIdentityAndData() throws Exception {
		var directory = directory(); var identity = identity(); var domain = create(directory, identity);
		var checkpoint = rich(identity, 1); domain.publish(checkpoint); directory.flush();
		var fresh = directory(); var reloaded = await(fresh, fresh.loadExisting(identity)).ready(); assertEquals(NetworkSavedData.Status.READY, reloaded.status());
		assertEquals(checkpoint, reloaded.checkpoint()); assertFalse(reloaded.isDirty());
		assertThrows(IllegalArgumentException.class, () -> directory.create(identity));
	}
	@Test void corruptBoundDomainIsNotRecreatedOrOverwrittenAndRequiresExplicitReload() throws Exception {
		var directory = directory(); var identity = identity(); create(directory, identity);
		Path file = directory.domainFile(identity.networkId()); byte[] valid = Files.readAllBytes(file);
		byte[] truncated = {31, -117, 8, 0}; Files.write(file, truncated);
		var fresh = directory(); var broken = await(fresh, fresh.loadExisting(identity)); assertEquals(NetworkOpenHandle.State.RECOVERY, broken.state());
		assertThrows(IllegalStateException.class, broken::ready); fresh.tick(); fresh.flush(); assertArrayEquals(truncated, Files.readAllBytes(file));
		Files.write(file, valid); assertSame(broken, fresh.loadExisting(identity));
		assertEquals(NetworkOpenHandle.State.READY, await(fresh, fresh.reloadRecovered(identity)).state());
	}
	@Test void missingDomainOrDirectoryCannotTurnABoundIdentityIntoAnEmptyNetwork() throws Exception {
		var directory = directory(); var identity = identity(); create(directory, identity);
		Path file = directory.domainFile(identity.networkId()); Files.delete(file);
		var fresh = directory(); assertEquals(NetworkOpenHandle.State.RECOVERY, await(fresh, fresh.loadExisting(identity)).state()); fresh.tick(); assertFalse(Files.exists(file));
		var unknown = identity(); assertEquals(NetworkOpenHandle.State.RECOVERY, await(fresh, fresh.loadExisting(unknown)).state());
		assertFalse(Files.exists(fresh.domainFile(unknown.networkId())));
	}
	@Test void unreadableDirectoryRefusesCreationAndRetainsOriginalBytes() throws Exception {
		Path file = folder.resolve("productivebeesgenesis_network_directory.dat"); byte[] broken = {0, 1, 2}; Files.write(file, broken);
		var directory = directory(); await(directory, directory.loadExisting(identity()));
		assertFalse(directory.failure().isEmpty()); assertThrows(IllegalStateException.class, () -> directory.create(identity()));
		directory.tick(); directory.flush(); assertArrayEquals(broken, Files.readAllBytes(file));
	}
	@Test void ordinaryTicksDoNotWriteEveryMutationAndNativeDirtyFlagCannotForgeAReceipt() throws Exception {
		var directory = directory(); var identity = identity(); var data = create(directory, identity);
		Path file = directory.domainFile(identity.networkId()); byte[] previous = Files.readAllBytes(file);
		data.publish(rich(identity, 1)); data.setDirty(false); directory.tick();
		assertTrue(data.isDirty()); assertEquals(0, data.persistedRevision()); assertArrayEquals(previous, Files.readAllBytes(file));
		directory.flush(); assertFalse(data.isDirty());
	}
	@Test void failedInitialDomainWriteDoesNotPublishDirectoryIdentity() throws Exception {
		var directory = new NetworkDirectory(folder, null, Runnable::run, null, CODEC, (path, bytes) -> { throw new IOException("Injected creation failure"); });
		var identity = identity(); var handle = directory.create(identity); directory.tick(); directory.tick();
		assertEquals(NetworkOpenHandle.State.CREATING, handle.state()); assertThrows(IllegalStateException.class, handle::ready);
		assertThrows(IOException.class, directory::flush);
		assertFalse(Files.exists(directory.domainFile(identity.networkId())));
		handle.cancel(); directory.close();
	}
	@Test void copiedControllerOrPositionCannotCreateASecondAuthority() throws Exception {
		var directory = directory(); var original = identity(); create(directory, original);
		var copied = new NetworkIdentity(java.util.UUID.randomUUID(), original.controllerId(), original.ownerId(), 2,
				new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 9, 64, 1));
		assertThrows(IllegalArgumentException.class, () -> directory.create(copied)); assertFalse(Files.exists(directory.domainFile(copied.networkId())));
		var occupied = identity(); assertThrows(IllegalArgumentException.class, () -> directory.create(occupied));
		assertFalse(Files.exists(directory.domainFile(occupied.networkId())));
	}
	@Test void slowCreationRequiresBothReceiptsAndDoesNotWaitInsideTick() {
		var executor = new ManualExecutor();
		try (var directory = new NetworkDirectory(folder, null, executor, null, CODEC, CheckpointFiles::write)) {
			var handle = directory.create(identity()); directory.tick();
			for (int i = 0; i < 100; i++) directory.tick();
			assertEquals(NetworkOpenHandle.State.CREATING, handle.state()); assertThrows(IllegalStateException.class, handle::ready);
			executor.complete(); directory.tick(); assertEquals(1, executor.queue.size());
			assertThrows(IllegalStateException.class, handle::ready); executor.complete(); directory.tick();
			assertEquals(NetworkOpenHandle.State.READY, handle.state()); assertFalse(handle.ready().isDirty());
		}
	}
	@Test void reloadInvalidationAndCancellationCannotPublishOldCandidates() throws Exception {
		var identity = identity();
		try (var initial = directory()) { var domain = create(initial, identity); domain.publish(rich(identity, 1)); initial.flush(); }
		try (var fresh = directory()) {
			var handle = fresh.loadExisting(identity);
			while (handle.state() == NetworkOpenHandle.State.QUEUED) fresh.tick(1, 1_000_000);
			fresh.invalidateLoads(); fresh.tick(); assertEquals(NetworkOpenHandle.State.CANCELLED, handle.state());
			assertThrows(IllegalStateException.class, handle::ready);
			var current = await(fresh, fresh.reloadRecovered(identity)); assertEquals(NetworkOpenHandle.State.READY, current.state());
		}
	}
}
