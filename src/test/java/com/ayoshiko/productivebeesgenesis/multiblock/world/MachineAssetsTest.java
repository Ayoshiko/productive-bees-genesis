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
	private static MachineAssets decode(CompoundTag tag) { return MachineAssets.decode(tag, key -> {}, key -> 64); }

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
}
