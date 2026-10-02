package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.feeding.FeedingItem;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.StrictNbt;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineCapacity;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedMachineWork;
import com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedWorkCodec;
import com.mojang.logging.LogUtils;
import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;

/** 单机有限资产独立于区块保存；控制器只持引用，拆机和卸载不删除文件。 */
final class MachineAssets extends SavedData {
	enum Mode { STANDALONE, RECOVERY }
	private final Thread thread = Thread.currentThread();
	private final UUID owner;
	private final ResourceLocation dimension;
	private final BlockPos position;
	private final CompoundTag quarantined;
	private CombinedMachineWork work;
	private Mode mode;
	private String failure = "", saveFailure = "";
	private boolean persisted;
	private Path boundPath;

	MachineAssets(UUID owner, ResourceLocation dimension, BlockPos position, CombinedMachineWork work) {
		this.owner = Objects.requireNonNull(owner); this.dimension = Objects.requireNonNull(dimension);
		this.position = position.immutable(); this.work = Objects.requireNonNull(work); mode = Mode.STANDALONE; quarantined = null;
		CombinedMachineCapacity.validate(work); setDirty();
	}
	private MachineAssets(CompoundTag raw, RuntimeException failure) {
		owner = null; dimension = null; position = null; work = null; mode = Mode.RECOVERY;
		quarantined = raw.copy(); this.failure = failure.toString();
	}
	static MachineAssets load(CompoundTag tag, HolderLookup.Provider registries) {
		return decode(tag, key -> ProductKeyCodec.validatePersisted(key, registries),
				key -> ProductKeyCodec.item(key, 1, registries).getMaxStackSize(), item -> StaticFeedingAdapter.validate(item, registries));
	}
	static MachineAssets decode(CompoundTag tag, Consumer<ProductKey> validate, ToIntFunction<ProductKey> limits, Consumer<FeedingItem> validateFeeding) {
		try {
			if (!tag.getAllKeys().equals(Set.of("schema", "owner", "dimension", "position", "mode", "failure", "work"))
					|| StrictNbt.integer(tag, "schema") != 1) throw new IllegalArgumentException("Unsupported machine asset envelope");
			var assets = new MachineAssets(StrictNbt.uuid(tag, "owner"), ResourceLocation.parse(StrictNbt.string(tag, "dimension")),
					BlockPos.of(StrictNbt.number(tag, "position")), CombinedWorkCodec.decode(StrictNbt.compound(tag, "work"), validate, limits, validateFeeding));
			assets.mode = StrictNbt.choice(tag, "mode", Mode.class); assets.failure = StrictNbt.string(tag, "failure");
			if ((assets.mode == Mode.STANDALONE) != assets.failure.isEmpty()) throw new IllegalArgumentException("Inconsistent machine recovery state");
			assets.persisted = true; assets.setDirty(false); return assets;
		} catch (RuntimeException failure) { return new MachineAssets(tag, failure); }
	}
	boolean matches(UUID machine, long generation, UUID owner, ResourceLocation dimension, BlockPos position) {
		checkThread();
		return work != null && work.machine().equals(machine) && work.generation() == generation
				&& this.owner.equals(owner) && this.dimension.equals(dimension) && this.position.equals(position);
	}
	boolean available() { checkThread(); return persisted && mode == Mode.STANDALONE && saveFailure.isEmpty(); }
	String failure() { checkThread(); return saveFailure.isEmpty() ? failure : saveFailure; }
	CombinedMachineWork work() { checkThread(); if (!available()) throw new IllegalStateException("Machine assets unavailable: " + failure()); return work; }
	void commit(CombinedMachineWork before, CombinedMachineWork after) {
		checkThread();
		if (!available() || before != work) throw new IllegalArgumentException("Stale or unavailable machine assets");
		if (before == after) return;
		CombinedMachineCapacity.validate(after);
		if (!before.machine().equals(after.machine()) || before.generation() != after.generation() || after.revision() <= before.revision())
			throw new IllegalArgumentException("Foreign combined work candidate");
		work = after; setDirty();
	}
	void quarantine(RuntimeException cause) {
		checkThread();
		if (quarantined != null || mode == Mode.RECOVERY) return;
		mode = Mode.RECOVERY; failure = cause.toString(); setDirty();
	}
	@Override public CompoundTag save(CompoundTag ignored, HolderLookup.Provider registries) {
		checkThread();
		if (quarantined != null) return quarantined.copy();
		var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putUUID("owner", owner);
		tag.putString("dimension", dimension.toString()); tag.putLong("position", position.asLong());
		tag.putString("mode", mode.name()); tag.putString("failure", failure); tag.put("work", CombinedWorkCodec.encode(work)); return tag;
	}
	/** 原生 save 会在失败后清 dirty；这里仅在强制写出并原子替换成功后确认，失败保持资产并暂停工作。 */
	@Override public void save(File file, HolderLookup.Provider registries) {
		checkThread();
		if (quarantined != null || !isDirty()) return;
		Path target = file.toPath().toAbsolutePath().normalize();
		if (boundPath != null && !boundPath.equals(target)) throw new IllegalArgumentException("Machine asset file changed");
		boundPath = target;
		try {
			var root = new CompoundTag(); root.putInt("DataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
			root.put("data", save(new CompoundTag(), registries));
			write(target, root);
			persisted = true; saveFailure = ""; setDirty(false);
		} catch (IOException | RuntimeException error) {
			if (saveFailure.isEmpty()) LogUtils.getLogger().error("Cannot save machine assets {}; retained and paused", target, error);
			saveFailure = error.toString(); setDirty();
		}
	}
	private static void write(Path target, CompoundTag root) throws IOException {
		Files.createDirectories(target.getParent());
		Path temporary = target.resolveSibling(target.getFileName() + ".pbg-pending");
		NbtIo.writeCompressed(root, temporary);
		try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
	private void checkThread() {
		if (Thread.currentThread() != thread) throw new IllegalStateException("Machine assets belong to the server thread");
	}
}
