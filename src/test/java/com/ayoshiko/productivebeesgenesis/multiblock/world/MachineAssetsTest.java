package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineCapacity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MachineAssetsTest {
	@TempDir Path folder;
	private static final ResourceLocation DIMENSION = ResourceLocation.parse("minecraft:overworld");
	private static final ProductKey ITEM = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("test:item"), new CompoundTag());
	@BeforeAll static void version() { SharedConstants.tryDetectVersion(); }
	private MachineAssets assets() {
		return new MachineAssets(UUID.randomUUID(), DIMENSION, new BlockPos(1, 64, 2), CombinedMachineCapacity.empty(UUID.randomUUID(), 1));
	}
	private static MachineAssets decode(CompoundTag tag) { return MachineAssets.decode(tag, key -> {}, key -> 64, item -> {}); }

	@Test void firstCheckpointMustReachDiskBeforeUseAndReloadKeepsTheExactWorkAndAnchor() throws Exception {
		var assets = assets(); assertFalse(assets.available()); assertTrue(assets.isDirty());
		var file = folder.resolve("machine.dat"); assets.save(file.toFile(), null);
		assertTrue(assets.available()); assertFalse(assets.isDirty());
		var before = assets.work(); var after = before.insert(ITEM, 64, 64).apply(before);
		assets.commit(before, after); assertTrue(assets.isDirty()); assets.save(file.toFile(), null);
		var encoded = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data");
		var loaded = decode(encoded);
		assertTrue(loaded.available()); assertEquals(64, loaded.work().buffer().count(ITEM)); assertEquals(after.revision(), loaded.work().revision());
		assertEquals(encoded, loaded.save(new CompoundTag(), null));
		assertTrue(loaded.matches(after.machine(), 1, encoded.getUUID("owner"), DIMENSION, new BlockPos(1, 64, 2)));
		assertFalse(loaded.matches(after.machine(), 1, UUID.randomUUID(), DIMENSION, new BlockPos(1, 64, 2)));
		assertFalse(loaded.matches(after.machine(), 2, encoded.getUUID("owner"), DIMENSION, new BlockPos(1, 64, 2)));
		assertFalse(loaded.matches(after.machine(), 1, encoded.getUUID("owner"), DIMENSION, new BlockPos(2, 64, 2)));
		assertFalse(loaded.matches(after.machine(), 1, encoded.getUUID("owner"), ResourceLocation.parse("minecraft:the_nether"), new BlockPos(1, 64, 2)));
	}

	@Test void failedSaveKeepsPreviousFileAndDirtyAssetsUntilSuccessfulRetry() throws Exception {
		var assets = assets(); var file = folder.resolve("machine.dat"); assets.save(file.toFile(), null);
		var original = Files.readAllBytes(file); var before = assets.work();
		assets.commit(before, before.receiveEnergy(123).apply(before));
		var temporary = folder.resolve("machine.dat.pbg-pending"); Files.createDirectory(temporary);
		assets.save(file.toFile(), null);
		assertFalse(assets.available()); assertTrue(assets.isDirty()); assertFalse(assets.failure().isEmpty());
		assertArrayEquals(original, Files.readAllBytes(file));
		assertEquals(123, assets.save(new CompoundTag(), null).getCompound("work").getLong("energy"));
		Files.delete(temporary); assets.save(file.toFile(), null);
		assertTrue(assets.available()); assertFalse(assets.isDirty()); assertEquals(123, assets.work().energy());
		assertEquals(123, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data").getCompound("work").getLong("energy"));
	}

	@Test void invalidEnvelopePreservesRawDataAndCannotOverwriteTheOriginalFile() throws Exception {
		var assets = assets(); var file = folder.resolve("machine.dat"); assets.save(file.toFile(), null);
		var original = Files.readAllBytes(file); var valid = assets.save(new CompoundTag(), null);
		for (var field : valid.getAllKeys()) {
			var broken = valid.copy(); broken.remove(field); var loaded = decode(broken);
			assertFalse(loaded.available()); assertEquals(broken, loaded.save(new CompoundTag(), null));
			loaded.setDirty(); loaded.save(file.toFile(), null); assertArrayEquals(original, Files.readAllBytes(file));
		}
		var future = valid.copy(); future.putString("mode", "MANAGED"); assertFalse(decode(future).available());
		assertThrows(IllegalStateException.class, () -> decode(future).work());
	}

	@Test void oldCandidatesAndPersistedRecoveryCannotResumeWork() throws Exception {
		var assets = assets(); var file = folder.resolve("machine.dat"); assets.save(file.toFile(), null);
		var before = assets.work(); var after = before.receiveEnergy(20).apply(before); assets.commit(before, after);
		assertThrows(IllegalArgumentException.class, () -> assets.commit(before, after));
		assets.quarantine(new IllegalStateException("unknown callback result")); assets.save(file.toFile(), null);
		var loaded = decode(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getCompound("data"));
		assertFalse(loaded.available()); assertEquals(20, loaded.save(new CompoundTag(), null).getCompound("work").getLong("energy"));
		assertThrows(IllegalArgumentException.class, () -> loaded.commit(after, after));
	}
	@Test void everyDurableModeKeepsOnlyTheCurrentOwnerAndReturnMustBeSaved() throws Exception {
		var assets = assets(); var path = folder.resolve("ownership.dat"); assets.save(path.toFile(), null);
		var work = assets.work(); assets.commit(work, work.receiveEnergy(42).apply(work)); work = assets.work();
		var tag = assets.save(new CompoundTag(), null);
		var network = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(),
				tag.getUUID("owner"), 0, new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 0, 64, 0));
		var reference = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference(network, work.machine(), UUID.randomUUID(),
				com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.JOINING);
		assets.freeze(reference); assets.save(path.toFile(), null); assets = decode(assets.save(new CompoundTag(), null));
		assertFalse(assets.available()); assertEquals(work, assets.captured());
		assets.seal(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(work));
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.MANAGED);
		assets.save(path.toFile(), null); assets = decode(assets.save(new CompoundTag(), null));
		assertTrue(assets.empty()); assertFalse(assets.available()); assertTrue(assets.save(new CompoundTag(), null).getCompound("work").isEmpty());
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.LEAVING); assets.save(path.toFile(), null);
		assets = decode(assets.save(new CompoundTag(), null)); assets.restore(work);
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.RETURNED);
		var unsaved = assets; assertThrows(IllegalStateException.class, unsaved::release);
		assets.save(path.toFile(), null); assets = decode(assets.save(new CompoundTag(), null));
		assertFalse(assets.available()); assertEquals(work, assets.captured()); assets.release(); assets.save(path.toFile(), null);
		assertTrue(assets.available()); assertNull(assets.binding()); assertEquals(42, assets.work().energy());
	}
	@Test void legacyTransferFingerprintSurvivesRepeatedRestartUntilReturnIsReleased() {
		var assets = assets(); var file = folder.resolve("legacy-transfer.dat"); assets.save(file.toFile(), null);
		var work = assets.work(); assets.commit(work, work.receiveEnergy(42).apply(work)); work = assets.work();
		var tag = assets.save(new CompoundTag(), null);
		var network = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(), tag.getUUID("owner"), 0,
				new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 0, 64, 0));
		assets.freeze(new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference(network, work.machine(), UUID.randomUUID(), com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.JOINING));
		tag = assets.save(new CompoundTag(), null); tag.getCompound("work").putInt("schema", 5);
		var rawImage = new CompoundTag(); rawImage.put("combinedWork", tag.getCompound("work").copy());
		var image = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage(rawImage);
		for (int i = 0; i < 3; i++) { assets = decode(tag); assertEquals(image, assets.capture()); tag = assets.save(new CompoundTag(), null); assertEquals(5, tag.getCompound("work").getInt("schema")); }
		assertTrue(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.matches(work, image));
		assets.seal(image); assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.MANAGED);
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.LEAVING); assets.restore(work, image);
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.RETURNED);
		assets.save(file.toFile(), null); assets = decode(assets.save(new CompoundTag(), null)); assertEquals(image, assets.capture());
		assets.release(); assertEquals(6, assets.save(new CompoundTag(), null).getCompound("work").getInt("schema")); assertEquals(42, assets.work().energy());
	}
	@Test void schemaOneMigratesWithoutChangingWorkOrInventingABinding() {
		var assets = assets(); assets.save(folder.resolve("old.dat").toFile(), null);
		var old = assets.save(new CompoundTag(), null); old.putInt("schema", 1); old.remove("machine"); old.remove("generation"); old.remove("binding");
		var migrated = decode(old); assertTrue(migrated.available()); assertNull(migrated.binding());
		assertEquals(old.getCompound("work"), migrated.save(new CompoundTag(), null).getCompound("work"));
	}
	@Test void failedReturnReceiptKeepsFiniteAssetsLockedAndNeverReleasesTheOldDiskOwner() throws Exception {
		var assets = assets(); var path = folder.resolve("return.dat"); assets.save(path.toFile(), null);
		var work = assets.work(); assets.commit(work, work.insert(ITEM, 64, 64).apply(work)); work = assets.work();
		var tag = assets.save(new CompoundTag(), null);
		var network = new com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity(UUID.randomUUID(), UUID.randomUUID(),
				tag.getUUID("owner"), 0, new com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin("minecraft:overworld", 0, 64, 0));
		assets.freeze(new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference(network, work.machine(), UUID.randomUUID(),
				com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.JOINING));
		assets.seal(com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(work));
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.MANAGED);
		assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.LEAVING); assets.save(path.toFile(), null);
		var oldFile = Files.readAllBytes(path);
		assets.restore(work); assets.mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.RETURNED);
		var pending = folder.resolve("return.dat.pbg-pending"); Files.createDirectory(pending); assets.save(path.toFile(), null);
		assertThrows(IllegalStateException.class, assets::release); assertFalse(assets.available()); assertTrue(assets.isDirty());
		assertArrayEquals(oldFile, Files.readAllBytes(path));
		var restored = decode(NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getCompound("data"));
		assertTrue(restored.empty()); assertFalse(restored.available());
		Files.delete(pending); assets.save(path.toFile(), null);
		assertEquals(work, assets.captured()); assertFalse(assets.available());
		assets.release(); assertEquals(64, assets.work().buffer().count(ITEM));
		var returnedWork = work; assertThrows(IllegalStateException.class, () -> assets.restore(returnedWork));
	}
}
