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
	enum Mode { STANDALONE, JOINING, MANAGED, LEAVING, RETURNED, RECOVERY }
	private final Thread thread = Thread.currentThread();
	private final UUID owner;
	private final ResourceLocation dimension;
	private final BlockPos position;
	private final CompoundTag quarantined;
	private CombinedMachineWork work;
	/** 交接尚未确认时保留原编码用于指纹；锁定期间不会推进工作，落盘仍写同一份 work。 */
	private com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage captureImage;
	private UUID machine;
	private long generation;
	private com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference binding;
	private Mode mode;
	private String failure = "", saveFailure = "";
	private boolean persisted;
	private Path boundPath;

	MachineAssets(UUID owner, ResourceLocation dimension, BlockPos position, CombinedMachineWork work) {
		this.owner = Objects.requireNonNull(owner); this.dimension = Objects.requireNonNull(dimension);
		this.position = position.immutable(); this.work = Objects.requireNonNull(work); mode = Mode.STANDALONE; quarantined = null;
		machine = work.machine(); generation = work.generation(); CombinedMachineCapacity.validate(work); setDirty();
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
			int schema = StrictNbt.integer(tag, "schema");
			var fields = new java.util.HashSet<>(Set.of("schema", "owner", "dimension", "position", "mode", "failure", "work"));
			if (schema == 2) fields.addAll(Set.of("machine", "generation", "binding"));
			if ((schema != 1 && schema != 2) || !tag.getAllKeys().equals(fields)) throw new IllegalArgumentException("Unsupported machine asset envelope");
			var rawWork = StrictNbt.compound(tag, "work");
			var work = rawWork.isEmpty() && schema == 2 ? null : CombinedWorkCodec.decode(rawWork, validate, limits, validateFeeding);
			UUID id = schema == 1 ? work.machine() : StrictNbt.uuid(tag, "machine");
			long generation = schema == 1 ? work.generation() : StrictNbt.number(tag, "generation");
			var assets = new MachineAssets(StrictNbt.uuid(tag, "owner"), ResourceLocation.parse(StrictNbt.string(tag, "dimension")),
					BlockPos.of(StrictNbt.number(tag, "position")), work == null ? CombinedMachineCapacity.empty(id, generation) : work);
			assets.work = work;
			if (!id.equals(assets.machine) || generation != assets.generation) throw new IllegalArgumentException("Machine envelope identity differs from work");
			assets.mode = StrictNbt.choice(tag, "mode", Mode.class); assets.failure = StrictNbt.string(tag, "failure");
			if (schema == 2 && !StrictNbt.compound(tag, "binding").isEmpty())
				assets.binding = com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.decode(StrictNbt.compound(tag, "binding"));
			if ((assets.mode == Mode.RECOVERY) == assets.failure.isEmpty()
					|| assets.mode != Mode.RECOVERY && ((assets.mode == Mode.STANDALONE) != (assets.binding == null)
					|| (assets.mode == Mode.MANAGED || assets.mode == Mode.LEAVING) != (work == null))
					|| assets.binding != null && (!assets.binding.member().equals(id) || !assets.binding.network().ownerId().equals(assets.owner)
					|| !assets.binding.mode().name().equals(assets.mode.name()))) throw new IllegalArgumentException("Inconsistent machine ownership state");
			if (work != null && (assets.mode == Mode.JOINING || assets.mode == Mode.RETURNED)) {
				var image = new CompoundTag(); image.put("combinedWork", rawWork.copy());
				assets.captureImage = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage(image);
			}
			assets.persisted = true; assets.setDirty(false); return assets;
		} catch (RuntimeException failure) { return new MachineAssets(tag, failure); }
	}
	boolean matches(UUID machine, long generation, UUID owner, ResourceLocation dimension, BlockPos position) {
		checkThread();
		return this.machine != null && this.machine.equals(machine) && this.generation == generation
				&& this.owner.equals(owner) && this.dimension.equals(dimension) && this.position.equals(position);
	}
	boolean readable() { checkThread(); return persisted && quarantined == null && mode != Mode.RECOVERY && saveFailure.isEmpty(); }
	com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference binding() { checkThread(); return binding; }
	CombinedMachineWork captured() { checkThread(); if (!readable() || work == null) throw new IllegalStateException("No machine source assets"); return work; }
	com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage capture() {
		var current = captured();
		return captureImage == null ? com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(current) : captureImage;
	}
	boolean empty() { checkThread(); return readable() && work == null; }
	void freeze(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference value) {
		checkThread();
		if (!available() || binding != null || !machine.equals(value.member()) || !owner.equals(value.network().ownerId())
				|| value.mode() != com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.JOINING)
			throw new IllegalStateException("Machine already bound or identity differs");
		captureImage = com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(work);
		binding = value; mode = Mode.JOINING; setDirty();
	}
	void seal(com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage expected) {
		if (mode != Mode.JOINING || !capture().equals(expected))
			throw new IllegalStateException("Machine changed before sealing");
		work = null; captureImage = null; setDirty();
	}
	void mode(com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode next) {
		checkThread();
		if (!readable() || binding == null) throw new IllegalStateException("Missing machine binding");
		boolean allowed = mode == Mode.JOINING && next == com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.MANAGED && work == null
				|| mode == Mode.MANAGED && next == com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.LEAVING && work == null
				|| mode == Mode.LEAVING && next == com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.RETURNED && work != null;
		if (!allowed) throw new IllegalStateException("Invalid machine ownership transition");
		binding = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference(binding.network(), binding.member(), binding.transfer(), next);
		mode = Mode.valueOf(next.name()); setDirty();
	}
	void restore(CombinedMachineWork restored) {
		restore(restored, com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.capture(restored));
	}
	void restore(CombinedMachineWork restored, com.ayoshiko.productivebeesgenesis.apiculture.ownership.AssetImage image) {
		checkThread(); CombinedMachineCapacity.validate(restored);
		if (!empty() || mode != Mode.LEAVING || !machine.equals(restored.machine()) || generation != restored.generation())
			throw new IllegalStateException("Invalid machine return target");
		if (!com.ayoshiko.productivebeesgenesis.multiblock.production.CombinedAssetProjection.matches(restored, image)) throw new IllegalArgumentException("Return image differs from work");
		work = restored; captureImage = image; setDirty();
	}
	void release() {
		checkThread();
		if (!readable() || isDirty() || mode != Mode.RETURNED || work == null) throw new IllegalStateException("Machine return not persisted");
		binding = null; captureImage = null; mode = Mode.STANDALONE; setDirty();
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
		mode = Mode.RECOVERY; failure = cause.toString();
		if (binding != null) binding = new com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Reference(binding.network(), binding.member(), binding.transfer(), com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.Mode.RECOVERY);
		setDirty();
	}
	@Override public CompoundTag save(CompoundTag ignored, HolderLookup.Provider registries) {
		checkThread();
		if (quarantined != null) return quarantined.copy();
		var tag = new CompoundTag(); tag.putInt("schema", 2); tag.putUUID("owner", owner);
		tag.putUUID("machine", machine); tag.putLong("generation", generation);
		tag.put("binding", binding == null ? new CompoundTag() : com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding.encode(binding));
		tag.putString("dimension", dimension.toString()); tag.putLong("position", position.asLong());
		tag.putString("mode", mode.name()); tag.putString("failure", failure);
		tag.put("work", work == null ? new CompoundTag() : captureImage == null ? CombinedWorkCodec.encode(work) : captureImage.copy().getCompound("combinedWork")); return tag;
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
