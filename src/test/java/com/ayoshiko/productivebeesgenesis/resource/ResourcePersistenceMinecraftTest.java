package com.ayoshiko.productivebeesgenesis.resource;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.neoforged.neoforge.common.IOUtilities;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class ResourcePersistenceMinecraftTest {
	@TempDir Path directory;

	private static HolderLookup.Provider provider() {
		return RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
	}

	@Test void cleanCloseDoesNotCreateMarkerAndAllowsNextSession() throws IOException {
		var path = directory.resolve("accounts.dat");
		var marker = directory.resolve("accounts.dat.session");
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var account = account(data.accounts());
		produce(account, 100);
		var reference = account.reference();
		data.finishAndClose(provider(), true);
		assertFalse(Files.exists(marker));
		var next = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		assertTrue(next.accounts().bound(reference, BINDING).canExtract(reference));
		next.finishAndClose(provider(), true);
		assertFalse(Files.exists(marker));
	}

	@Test void legacyMarkerDoesNotSuspendHealthyAccountsOrChangeMarker() throws IOException {
		var path = directory.resolve("accounts.dat");
		var marker = directory.resolve("accounts.dat.session");
		byte[] legacy = "legacy-session".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		Files.write(marker, legacy);
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var account = account(data.accounts());
		produce(account, 100);
		var reference = account.reference();
		data.finishAndClose(provider(), true);
		var next = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var restored = next.accounts().bound(reference, BINDING);
		assertTrue(restored.canExtract(reference));
		produce(restored, 50);
		assertEquals(150L, restored.available().get(HYDROGEN));
		next.finishAndClose(provider(), true);
		assertArrayEquals(legacy, Files.readAllBytes(marker));
	}

	@Test void failedFinalSaveDoesNotInventSessionMarker() {
		var path = directory.resolve("accounts.dat");
		var data = ResourceAccountsSavedData.open(path, (snapshot, target) -> {
			throw new IOException("disk full");
		}, Runnable::run);
		produce(account(data.accounts()), 100);
		data.finishAndClose(provider(), true);
		assertFalse(Files.exists(path));
		assertFalse(Files.exists(directory.resolve("accounts.dat.session")));
	}

	@Test void failedWorldShutdownKeepsLastSnapshotWithoutBlockingHealthyReload() throws IOException {
		var path = directory.resolve("accounts.dat");
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var account = account(data.accounts());
		produce(account, 100);
		var reference = account.reference();
		data.save(path.toFile(), provider());
		data.poll();
		byte[] before = Files.readAllBytes(path);
		produce(account, 50);
		data.finishAndClose(provider(), false);
		assertArrayEquals(before, Files.readAllBytes(path));
		var next = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var restored = next.accounts().bound(reference, BINDING);
		assertEquals(100L, restored.available().get(HYDROGEN));
		assertTrue(restored.canExtract(reference));
		assertThrows(IllegalStateException.class, next::requestSaveRetry);
		next.finishAndClose(provider(), true);
		assertArrayEquals(before, Files.readAllBytes(path));
	}

	@Test void worldSaveFailureSurvivesAnAlreadyQueuedSuccessfulAccountWrite() {
		var path = directory.resolve("accounts.dat");
		var queued = new ArrayList<Runnable>();
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, queued::add);
		var account = account(data.accounts());
		produce(account, 100);
		data.save(path.toFile(), provider());
		data.isolateWorldSaveFailure();
		queued.removeFirst().run();
		data.poll();
		assertTrue(data.sessionIsolated());
		assertFalse(account.canExtract(account.reference()));
		assertThrows(IllegalStateException.class, data::requestSaveRetry);
		data.finishAndClose(provider(), true);
		assertFalse(Files.exists(directory.resolve("accounts.dat.session")));
	}

	@Test void normalSaveReloadAndClosePreserveAccountAndRevokeOldReferences() {
		var path = directory.resolve("accounts.dat");
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var account = account(data.accounts());
		produce(account, 1000);
		var reference = account.reference();
		data.save(path.toFile(), provider());
		data.poll();
		assertFalse(data.isDirty());
		var restored = ResourceAccountsSavedData.open(path).accounts().bound(reference, BINDING);
		assertEquals(1000L, restored.available().get(HYDROGEN));
		data.finishAndClose(provider(), true);
		assertThrows(IllegalStateException.class, () -> account.beginTransfer(reference, HYDROGEN, 1));
	}

	@Test void completingAnOldSnapshotDoesNotClearNewerChanges() {
		var path = directory.resolve("accounts.dat");
		var queued = new ArrayList<Runnable>();
		var snapshots = new ArrayList<CompoundTag>();
		var data = ResourceAccountsSavedData.open(path, (snapshot, target) -> snapshots.add(snapshot.copy()), queued::add);
		var account = account(data.accounts());
		produce(account, 100);
		data.save(path.toFile(), provider());
		produce(account, 50);
		data.save(path.toFile(), provider());
		assertEquals(1, queued.size());
		queued.removeFirst().run();
		data.poll();
		assertTrue(data.isDirty());
		var old = ResourceAccounts.load(snapshots.getFirst().getCompound("data"), () -> {})
				.find(account.reference().sourceId());
		assertEquals(100L, old.available().get(HYDROGEN));
		data.save(path.toFile(), provider());
		queued.removeFirst().run();
		data.poll();
		assertFalse(data.isDirty());
		var latest = ResourceAccounts.load(snapshots.getLast().getCompound("data"), () -> {})
				.find(account.reference().sourceId());
		assertEquals(150L, latest.available().get(HYDROGEN));
	}

	@Test void failedWritePreservesOldFileAndRequiresExplicitSuccessfulRetry() throws IOException {
		var path = directory.resolve("accounts.dat");
		var fail = new AtomicBoolean();
		var writes = new AtomicInteger();
		var data = ResourceAccountsSavedData.open(path, (snapshot, target) -> {
			writes.incrementAndGet();
			if (fail.get()) throw new IOException("injected disk failure");
			IOUtilities.writeNbtCompressed(snapshot, target);
		}, Runnable::run);
		var account = account(data.accounts());
		produce(account, 100);
		data.save(path.toFile(), provider());
		data.poll();
		byte[] before = Files.readAllBytes(path);
		produce(account, 50);
		fail.set(true);
		data.save(path.toFile(), provider());
		data.poll();
		assertTrue(data.isDirty());
		assertFalse(data.accounts().persistenceFailure().isEmpty());
		assertArrayEquals(before, Files.readAllBytes(path));
		assertEquals(150L, account.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> account.beginTransfer(account.reference(), HYDROGEN, 1));
		data.save(path.toFile(), provider());
		assertEquals(2, writes.get());
		fail.set(false);
		data.requestSaveRetry();
		assertThrows(IllegalStateException.class, () -> produce(account, 1));
		data.save(path.toFile(), provider());
		data.poll();
		assertFalse(data.isDirty());
		assertTrue(data.accounts().persistenceFailure().isEmpty());
		var restored = ResourceAccountsSavedData.open(path).accounts().find(account.reference().sourceId());
		assertEquals(150L, restored.available().get(HYDROGEN));
	}

	@Test void corruptExistingFileCannotBeReplacedWithEmptyAccounts() throws IOException {
		var path = directory.resolve("corrupt.dat");
		byte[] corrupt = new byte[]{1, 2, 3, 4, 5};
		Files.write(path, corrupt);
		var data = ResourceAccountsSavedData.open(path);
		assertFalse(data.accounts().persistenceFailure().isEmpty());
		assertThrows(IllegalStateException.class, () -> account(data.accounts()));
		assertThrows(IllegalStateException.class, data::requestSaveRetry);
		data.setDirty();
		data.save(path.toFile(), provider());
		assertArrayEquals(corrupt, Files.readAllBytes(path));
	}

	@Test void unknownSchemaIsPreservedAndNeverOverwritesFile() throws IOException {
		var path = directory.resolve("future.dat");
		var raw = new CompoundTag();
		raw.putInt("version", 19);
		raw.putString("unparsed_asset", "keep exactly");
		var file = new CompoundTag();
		file.put("data", raw.copy());
		NbtIo.writeCompressed(file, path);
		byte[] before = Files.readAllBytes(path);
		var data = ResourceAccountsSavedData.open(path);
		assertTrue(data.accounts().hasInvalidData());
		assertEquals(raw, data.save(new CompoundTag(), provider()));
		data.setDirty();
		data.save(path.toFile(), provider());
		assertArrayEquals(before, Files.readAllBytes(path));
	}

	@Test void unfinishedDiskTransferRequiresExplicitOutcomeInsteadOfAutomaticRefund() {
		var path = directory.resolve("pending.dat");
		var data = ResourceAccountsSavedData.open(path, IOUtilities::writeNbtCompressed, Runnable::run);
		var account = account(data.accounts());
		produce(account, 1000);
		var ticket = account.beginTransfer(account.reference(), HYDROGEN, 750);
		data.save(path.toFile(), provider());
		data.poll();
		var restored = ResourceAccountsSavedData.open(path).accounts().find(ticket.source().sourceId());
		assertEquals(250L, restored.available().get(HYDROGEN));
		assertThrows(IllegalStateException.class, () -> restored.completeTransfer(ticket, 0));
		restored.resolveTransfer(OWNER, ticket, 500);
		assertEquals(500L, restored.available().get(HYDROGEN));
	}

	@Test void shutdownWaitsForRealFrameworkIoWorkerAndSavesLastRevision() throws IOException {
		var path = directory.resolve("shutdown.dat");
		var data = ResourceAccountsSavedData.open(path);
		var account = account(data.accounts());
		produce(account, 50);
		data.save(path.toFile(), provider());
		produce(account, 25);
		var reference = account.reference();
		data.finishAndClose(provider(), true);
		assertTrue(Files.isRegularFile(path));
		var restored = ResourceAccountsSavedData.open(path).accounts().find(reference.sourceId());
		assertEquals(75L, restored.available().get(HYDROGEN));
	}
}
