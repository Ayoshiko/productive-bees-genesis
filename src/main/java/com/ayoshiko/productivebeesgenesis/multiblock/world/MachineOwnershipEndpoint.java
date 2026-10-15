package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.apiary.StaticFeedingAdapter;
import com.ayoshiko.productivebeesgenesis.multiblock.production.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** 一体机交接保存真实的机器文件；控制器与外壳不持第二份资产。 */
public final class MachineOwnershipEndpoint implements OwnershipEndpoint {
	private final MachineControllerEntity core;
	public MachineOwnershipEndpoint(MachineControllerEntity core) { this.core = core; }
	private ServerLevel level() {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || core.isRemoved() || !core.readyIdentity())
			throw new IllegalStateException("Machine controller unavailable");
		var pos = core.getBlockPos(); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (chunk == null || chunk.getBlockEntity(pos) != core || core.assets == null
				|| !core.assets.matches(core.machineId(), core.generation(), core.ownerId(), level.dimension().location(), pos))
			throw new IllegalStateException("Machine controller identity changed");
		return level;
	}
	public boolean bound() { level(); return core.assets.binding() != null; }
	@Override public void validate(NetworkIdentity network, MemberClaim claim) {
		var level = level(); var pos = core.getBlockPos(); var origin = claim.origin();
		if (!claim.network().equals(network.networkId()) || !claim.member().equals(core.machineId()) || !network.ownerId().equals(core.ownerId())
				|| !claim.machine().equals(CombinedAssetProjection.MACHINE) || !origin.dimension().equals(level.dimension().location().toString())
				|| origin.x() != pos.getX() || origin.y() != pos.getY() || origin.z() != pos.getZ())
			throw new IllegalArgumentException("Foreign machine claim");
	}
	@Override public void freeze(NetworkIdentity network, MemberClaim claim) {
		validate(network, claim);
		if (!core.formed()) throw new IllegalStateException("Machine must be formed before joining");
		validateReturn(capture());
		core.assets.freeze(new MemberBinding.Reference(network, claim.member(), claim.transfer(), MemberBinding.Mode.JOINING)); flush();
	}
	@Override public boolean matches(NetworkIdentity network, MemberClaim claim, MemberBinding.Mode mode) {
		level(); return new MemberBinding.Reference(network, claim.member(), claim.transfer(), mode).equals(core.assets.binding());
	}
	@Override public boolean persistsModes() { return true; }
	@Override public boolean prepare() { level(); return core.assets.readable(); }
	@Override public AssetImage capture() { level(); return core.assets.capture(); }
	@Override public void seal(AssetImage expected) { level(); core.assets.seal(expected); }
	@Override public boolean empty() { level(); return core.assets.empty(); }
	@Override public void mode(MemberBinding.Mode mode) { level(); core.assets.mode(mode); flush(); }
	@Override public boolean readyToReturn(OwnedMachineRecord record) { level(); return record.combined() == null || record.combined().drained(); }
	public CombinedMachineWork decode(AssetImage image) {
		var level = level(); var tag = image.copy();
		if (!tag.getAllKeys().equals(java.util.Set.of("combinedWork"))) throw new IllegalArgumentException("Invalid machine asset image");
		var work = CombinedWorkCodec.decode(tag.getCompound("combinedWork"), key -> ProductKeyCodec.validatePersisted(key, level.registryAccess()),
				key -> ProductKeyCodec.item(key, 1, level.registryAccess()).getMaxStackSize(), item -> StaticFeedingAdapter.validate(item, level.registryAccess()));
		if (!work.machine().equals(core.machineId()) || work.generation() != core.generation()) throw new IllegalArgumentException("Foreign machine work");
		return work;
	}
	@Override public void validateReturn(AssetImage image) { decode(image); }
	@Override public void restore(AssetImage image) { core.assets.restore(decode(image), image); }
	@Override public CompletionStage<Void> saveReturn() {
		try { flush(); return CompletableFuture.completedFuture(null); }
		catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
	}
	@Override public void release() { level(); core.assets.release(); flush(); }
	@Override public void quarantine(String reason) { level(); core.assets.quarantine(new IllegalStateException(reason)); flush(); }
	private void flush() {
		var level = level();
		var file = level.getServer().getWorldPath(LevelResource.ROOT).resolve("data").resolve(MachineWorkService.dataName(core.machineId()) + ".dat");
		core.assets.save(file.toFile(), level.registryAccess());
		if (core.assets.isDirty() || !core.assets.failure().isEmpty()) throw new IllegalStateException("Machine ownership save failed: " + core.assets.failure());
		level.invalidateCapabilities(core.getBlockPos());
	}
}
