package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import java.util.concurrent.CompletionStage;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;

/** 只在服务器线程操作当前 BE；返回写入完成回执，等待由状态机轮询。 */
public final class BlockEntityOwnershipEndpoint implements OwnershipEndpoint {
	private final TileEntityMekanism tile;
	private final MachineAssetStore assets;
	public BlockEntityOwnershipEndpoint(TileEntityMekanism tile) { this.tile = tile; assets = new MachineAssetStore(tile); }
	private ServerLevel level() {
		if (!(tile.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || tile.isRemoved()
				|| !level.hasChunk(tile.getBlockPos().getX() >> 4, tile.getBlockPos().getZ() >> 4) || level.getBlockEntity(tile.getBlockPos()) != tile) throw new IllegalStateException("Member unloaded or replaced");
		return level;
	}
	@Override public void validate(NetworkIdentity network, MemberClaim claim) {
		var level = level(); var pos = tile.getBlockPos(); var origin = claim.origin();
		if (!network.networkId().equals(claim.network()) || !network.ownerId().equals(tile.getOwnerUUID())
				|| !origin.dimension().equals(level.dimension().location().toString()) || origin.x() != pos.getX() || origin.y() != pos.getY() || origin.z() != pos.getZ()
				|| !claim.machine().equals(BuiltInRegistries.BLOCK.getKey(tile.getBlockState().getBlock()).toString())) throw new IllegalArgumentException("Member identity, position, or owner changed");
	}
	@Override public void freeze(NetworkIdentity network, MemberClaim claim) {
		var level = level();
		// 已知外部欠账必须在独立机路径结清；冻结后不能再启动外部输出重试。
		if (!assets.prepared()) throw new IllegalStateException("Settle external output before joining");
		assets.validate(assets.capture(level.registryAccess()), level);
		MemberBinding.begin(tile, network, claim.member(), claim.transfer());
	}
	@Override public boolean matches(NetworkIdentity network, MemberClaim claim, MemberBinding.Mode mode) {
		level(); if (!tile.getPersistentData().contains(MemberBinding.TAG)) return false;
		return MemberBinding.read(tile).equals(new MemberBinding.Reference(network, claim.member(), claim.transfer(), mode));
	}
	@Override public boolean prepare() { level(); return assets.prepared(); }
	@Override public AssetImage capture() { return assets.capture(level().registryAccess()); }
	@Override public void seal(AssetImage expected) {
		var level = level(); if (!expected.equals(capture())) throw new IllegalStateException("Source changed before sealing");
		assets.validate(expected, level); assets.clear(level.registryAccess());
	}
	@Override public boolean empty() { level(); return assets.empty(); }
	@Override public void mode(MemberBinding.Mode mode) { level(); MemberBinding.phase(tile, MemberBinding.read(tile), mode); }
	@Override public void validateReturn(AssetImage image) { assets.validate(image, level()); }
	@Override public void restore(AssetImage image) { assets.restore(image, level()); }
	@Override public CompletionStage<Void> saveReturn() {
		var level = level(); var chunk = level.getChunkAt(tile.getBlockPos()); tile.setChanged();
		// 与原生保存同用 ChunkMap 的写队列，后续正常保存不会被旧的另一路写入覆盖。
		var data = ChunkSerializer.write(level, chunk);
		net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.level.ChunkDataEvent.Save(chunk, level, data));
		return level.getChunkSource().chunkMap.write(chunk.getPos(), data);
	}
	@Override public void release() { level(); MemberBinding.release(tile, MemberBinding.read(tile)); }
	@Override public void quarantine(String reason) {
		level();
		if (tile.getPersistentData().contains(MemberBinding.TAG)) {
			try { mode(MemberBinding.Mode.RECOVERY); } catch (IllegalArgumentException ignored) { /* 原始损坏引用仍保留并隔离。 */ }
		}
		MemberBinding.suspend(tile);
	}
}
