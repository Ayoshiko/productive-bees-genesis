package com.ayoshiko.productivebeesgenesis.apiculture.persistence;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnershipTransferService.Step.*;

class OwnershipTransferTest {
	@TempDir Path folder;
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private NetworkDirectory directory() { return new NetworkDirectory(folder, null, Runnable::run, null, CheckpointTestData.CODEC, CheckpointFiles::write); }
	private static NetworkSavedData ready(NetworkDirectory directory, NetworkOpenHandle handle) {
		long end = System.nanoTime() + 10_000_000_000L;
		while (handle.pending()) { assertTrue(System.nanoTime() < end); directory.tick(); Thread.yield(); }
		return handle.ready();
	}
	private static MemberClaim claim(NetworkIdentity identity) { return new MemberClaim(identity.networkId(), UUID.randomUUID(), UUID.randomUUID(), new Origin("minecraft:overworld", 2, 64, 1), "productivebeesgenesis:mek_apiary"); }
	private static AssetImage image(int count) { var tag = new CompoundTag(); tag.putInt("items", count); tag.putLong("energy", 123456); return new AssetImage(tag); }
	private static final class Endpoint implements OwnershipEndpoint {
		AssetImage assets = image(37);
		MemberBinding.Reference binding;
		CompletableFuture<Void> write = new CompletableFuture<>();
		int clears, restores, releases;
		boolean prepared = true, full, failSeal;
		@Override public void validate(NetworkIdentity network, MemberClaim claim) { }
		@Override public void freeze(NetworkIdentity network, MemberClaim claim) {
			if (binding != null) throw new IllegalStateException("Already frozen"); binding = new MemberBinding.Reference(network, claim.member(), claim.transfer(), MemberBinding.Mode.JOINING);
		}
		@Override public boolean matches(NetworkIdentity network, MemberClaim claim, MemberBinding.Mode mode) { return new MemberBinding.Reference(network, claim.member(), claim.transfer(), mode).equals(binding); }
		@Override public boolean prepare() { return prepared; }
		@Override public AssetImage capture() { return assets; }
		@Override public void seal(AssetImage expected) { assertEquals(expected, assets); if (failSeal) throw new IllegalStateException("Injected seal failure"); assets = AssetImage.EMPTY; clears++; }
		@Override public boolean empty() { return assets.isEmpty(); }
		@Override public void mode(MemberBinding.Mode mode) { binding = new MemberBinding.Reference(binding.network(), binding.member(), binding.transfer(), mode); }
		@Override public void validateReturn(AssetImage assets) { if (full) throw new IllegalStateException("Insufficient capacity"); }
		@Override public void restore(AssetImage assets) { assertTrue(empty()); this.assets = assets; restores++; }
		@Override public CompletableFuture<Void> saveReturn() { return write; }
		@Override public void release() { binding = null; releases++; }
		@Override public void quarantine(String reason) { if (binding != null) mode(MemberBinding.Mode.RECOVERY); }
	}
	private static void reach(NetworkDirectory directory, OwnershipTransferService service, Endpoint endpoint, OwnershipTransferService.Step target) {
		for (int i = 0; i < 50 && service.step() != target; i++) { directory.tick(); service.advance(endpoint); }
		assertEquals(target, service.step(), service.failure());
	}
	private static final class DelayedWrites implements java.util.concurrent.Executor {
		boolean delayed;
		final java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
		@Override public void execute(Runnable task) { if (delayed) tasks.addLast(task); else task.run(); }
		void complete() { delayed = false; while (!tasks.isEmpty()) tasks.removeFirst().run(); }
	}
	@Test void liveCoreRebuildCannotSkipAnOutstandingClaimReceipt() {
		var writes = new DelayedWrites();
		try (var directory = new NetworkDirectory(folder, null, writes, null, CheckpointTestData.CODEC, CheckpointFiles::write)) {
			try {
				var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint();
				writes.delayed = true; OwnershipTransferService.begin(directory, data, claim, endpoint);
				var resumed = OwnershipTransferService.resume(directory, data, claim, endpoint);
				for (int i = 0; i < 5; i++) { directory.tick(); resumed.advance(endpoint); }
				assertEquals(CLAIM, resumed.step()); assertTrue(data.checkpoint().ownedMachines().values().isEmpty());
				assertEquals(0, endpoint.clears); assertTrue(directory.directoryRevision() > directory.persistedDirectoryRevision());
				writes.complete(); reach(directory, resumed, endpoint, OWNED);
			} finally { writes.complete(); }
		}
	}
	@Test void liveCoreRebuildCannotUnlockAnUnacknowledgedClaimRelease() {
		var writes = new DelayedWrites();
		try (var directory = new NetworkDirectory(folder, null, writes, null, CheckpointTestData.CODEC, CheckpointFiles::write)) {
			try {
				var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint();
				var service = OwnershipTransferService.begin(directory, data, claim, endpoint); reach(directory, service, endpoint, OWNED);
				service.requestReturn(endpoint); endpoint.write.complete(null); reach(directory, service, endpoint, RELEASE_CLAIM);
				writes.delayed = true; service.advance(endpoint); assertNull(directory.claimAt(claim.origin()));
				var resumed = OwnershipTransferService.resume(directory, data, claim, endpoint);
				for (int i = 0; i < 5; i++) { directory.tick(); resumed.advance(endpoint); }
				assertEquals(RELEASE_BINDING, resumed.step()); assertEquals(0, endpoint.releases); assertNotNull(endpoint.binding);
				writes.complete(); reach(directory, resumed, endpoint, RETURNED); assertEquals(1, endpoint.restores);
				assertEquals(0, data.checkpoint().ownedMachines().activeCount());
			} finally { writes.complete(); }
		}
	}
	@Test void liveCoreRebuildRequestsAndWaitsForPreviouslyUnrequestedDirtyWork() {
		var writes = new DelayedWrites();
		try (var directory = new NetworkDirectory(folder, null, writes, null, CheckpointTestData.CODEC, CheckpointFiles::write)) {
			try {
				var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint();
				var service = OwnershipTransferService.begin(directory, data, claim, endpoint); reach(directory, service, endpoint, OWNED);
				long submitted = directory.saveStatus().submitted();
				data.publish(data.checkpoint().configureEnergy(100).receiveEnergy(17));
				assertTrue(data.isDirty()); assertEquals(submitted, directory.saveStatus().submitted());
				writes.delayed = true;
				var resumed = OwnershipTransferService.resume(directory, data, claim, endpoint);
				assertEquals(submitted + 1, directory.saveStatus().submitted());
				for (int i = 0; i < 5; i++) { directory.tick(); resumed.advance(endpoint); }
				assertEquals(OWNED_RECEIPT, resumed.step()); assertEquals(1, endpoint.clears); assertEquals(0, endpoint.restores);
				writes.complete(); reach(directory, resumed, endpoint, OWNED);
				assertEquals(17, data.checkpoint().energy().stored()); assertEquals(data.checkpoint().revision(), data.persistedRevision());
			} finally { writes.complete(); }
		}
	}
	@Test void bothDirectionsWaitForReceiptsAndNeverIssueAssetsTwice() throws Exception {
		try (var directory = directory()) {
			var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint(); var original = endpoint.assets;
			var service = OwnershipTransferService.begin(directory, data, claim, endpoint);
			assertEquals(CLAIM, service.step()); assertEquals(original, endpoint.assets);
			assertThrows(IllegalArgumentException.class, () -> OwnershipTransferService.begin(directory, data, claim, new Endpoint()));
			assertThrows(IllegalStateException.class, () -> directory.releaseMember(data, claim));
			reach(directory, service, endpoint, OWNED); assertEquals(1, endpoint.clears); assertTrue(endpoint.empty());
			service.requestReturn(endpoint); reach(directory, service, endpoint, RETURN_WRITE);
			for (int i = 0; i < 10; i++) { directory.tick(); service.advance(endpoint); }
			assertEquals(RETURN_WRITE, service.step()); assertEquals(1, endpoint.restores); assertEquals(claim, directory.claimAt(claim.origin()));
			assertEquals(OwnedMachineRecord.Phase.RETURNING, data.checkpoint().ownedMachines().get(claim.member()).phase());
			endpoint.write.complete(null); reach(directory, service, endpoint, RETURNED);
			assertNull(directory.claimAt(claim.origin())); assertNull(endpoint.binding); assertEquals(original, endpoint.assets);
			assertFalse(data.checkpoint().ownedMachines().activeValues().iterator().hasNext());
			assertTrue(data.checkpoint().ownedMachines().get(claim.member()).assets().isEmpty());
			service.advance(endpoint); assertEquals(1, endpoint.restores); assertEquals(1, endpoint.releases);
			assertThrows(IllegalStateException.class, () -> service.requestReturn(endpoint));
			assertThrows(IllegalArgumentException.class, () -> OwnershipTransferService.begin(directory, data, claim, endpoint)); directory.flush();
		}
	}
	@Test void claimIsDurableBeforeDomainSealingAndOldUnboundSourceIsNotReimported() throws Exception {
		var identity = CheckpointTestData.identity(); var claim = claim(identity); var endpoint = new Endpoint();
		try (var directory = directory()) {
			var data = ready(directory, directory.create(identity)); var service = OwnershipTransferService.begin(directory, data, claim, endpoint);
			reach(directory, service, endpoint, PREPARE); directory.flush(); assertTrue(data.checkpoint().ownedMachines().values().isEmpty());
		}
		endpoint.binding = null;
		try (var directory = directory()) {
			var data = ready(directory, directory.loadExisting(identity)); assertEquals(claim, directory.claimAt(claim.origin()));
			var service = OwnershipTransferService.resume(directory, data, claim, endpoint);
			assertEquals(RECOVERY, service.step()); assertEquals(image(37), endpoint.assets); assertEquals(0, endpoint.clears);
			assertThrows(IllegalArgumentException.class, () -> OwnershipTransferService.begin(directory, data, claim(identity), endpoint));
		}
	}
	@Test void eachDurableStageCanResumeWithoutRepeatingThePhysicalReturn() throws Exception {
		for (var stage : new OwnershipTransferService.Step[] {PREPARE, SEAL, OWNED, RETURN_INTENT, RETURN_WRITE, RETURN_RECEIPT, RELEASE_CLAIM, RELEASE_BINDING}) {
			var identity = new NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, new Origin("minecraft:overworld", stage.ordinal(), 70, 3));
			var claim = claim(identity); var endpoint = new Endpoint();
			try (var directory = directory()) {
				var data = ready(directory, directory.create(identity)); var service = OwnershipTransferService.begin(directory, data, claim, endpoint);
				if (stage == SEAL || stage == PREPARE) reach(directory, service, endpoint, stage);
				else {
					reach(directory, service, endpoint, OWNED);
					if (stage != OWNED) { service.requestReturn(endpoint); if (stage.ordinal() >= RETURN_RECEIPT.ordinal()) endpoint.write.complete(null); reach(directory, service, endpoint, stage); }
				}
				directory.flush();
			}
			try (var directory = directory()) {
				var data = ready(directory, directory.loadExisting(identity)); var service = OwnershipTransferService.resume(directory, data, claim, endpoint);
				assertNotEquals(RECOVERY, service.step(), service.failure());
				if (stage == PREPARE || stage == SEAL || stage == OWNED) { reach(directory, service, endpoint, OWNED); service.requestReturn(endpoint); }
				endpoint.write.complete(null); reach(directory, service, endpoint, RETURNED);
				assertEquals(1, endpoint.clears); assertEquals(1, endpoint.restores); assertEquals(image(37), endpoint.assets); directory.flush();
			}
		}
	}
	@Test void changedSourceRetainsAuthorityInRecovery() {
		try (var directory = directory()) {
			var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint();
			var service = OwnershipTransferService.begin(directory, data, claim, endpoint); reach(directory, service, endpoint, SEAL);
			endpoint.assets = image(38); directory.tick(); service.advance(endpoint); assertEquals(RECOVERY, service.step());
			assertEquals(0, endpoint.clears); assertEquals(image(37), data.checkpoint().ownedMachines().get(claim.member()).assets());
			assertEquals(claim, directory.claimAt(claim.origin()));
		}
	}
	@Test void failedChunkWriteCannotDiscardTheNetworkCopyOrUnlockTheTarget() {
		try (var directory = directory()) {
			var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint();
			var service = OwnershipTransferService.begin(directory, data, claim, endpoint); reach(directory, service, endpoint, OWNED);
			service.requestReturn(endpoint); reach(directory, service, endpoint, RETURN_WRITE); endpoint.write.completeExceptionally(new java.io.IOException("Injected chunk IO failure")); service.advance(endpoint);
			assertEquals(RECOVERY, service.step()); assertEquals(0, endpoint.releases); assertEquals(claim, directory.claimAt(claim.origin()));
			assertEquals(image(37), data.checkpoint().ownedMachines().get(claim.member()).assets());
		}
	}
	@Test void pendingPreparationAndInsufficientCapacityDoNotLoseAssets() {
		try (var directory = directory()) {
			var identity = CheckpointTestData.identity(); var data = ready(directory, directory.create(identity)); var claim = claim(identity); var endpoint = new Endpoint(); endpoint.prepared = false;
			var service = OwnershipTransferService.begin(directory, data, claim, endpoint); reach(directory, service, endpoint, PREPARE);
			for (int i = 0; i < 10; i++) service.advance(endpoint);
			assertEquals(PREPARE, service.step()); assertTrue(data.checkpoint().ownedMachines().values().isEmpty());
			endpoint.prepared = true; reach(directory, service, endpoint, OWNED); endpoint.full = true; service.requestReturn(endpoint);
			assertEquals(RECOVERY, service.step()); assertEquals(0, endpoint.restores); assertEquals(0, endpoint.releases);
		}
	}
	@Test void immutableHistoryCannotBeRewrittenAndCaptureMustPreserveIt() {
		var identity = CheckpointTestData.identity(); var claim = claim(identity); var image = image(1);
		var sealed = new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.SEALED, image, image.fingerprint(), "");
		var first = OwnedMachines.EMPTY.put(sealed); var second = first.put(sealed.phase(OwnedMachineRecord.Phase.OWNED));
		assertEquals(OwnedMachineRecord.Phase.SEALED, first.get(claim.member()).phase());
		assertThrows(IllegalArgumentException.class, () -> second.put(sealed));
		assertThrows(IllegalStateException.class, () -> sealed.phase(OwnedMachineRecord.Phase.RETURNED));
		assertThrows(IllegalArgumentException.class, () -> second.put(new OwnedMachineRecord(claim, OwnedMachineRecord.Phase.RETURNING, image(2), image(2).fingerprint(), "")));
		var checkpoint = NetworkCheckpoint.empty(identity).withOwnership(sealed);
		assertEquals(checkpoint, CheckpointTestData.CODEC.decode(NetworkCheckpointCodec.encode(checkpoint)));
		var domain = NetworkSavedData.loaded(checkpoint, new CheckpointSaveQueue(Runnable::run, CheckpointFiles::write));
		assertThrows(IllegalArgumentException.class, () -> domain.publish(CheckpointTestData.rich(identity, 99)));
	}
}
