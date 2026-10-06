package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessTerminalItem.MachineReference;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 只查六个相邻位置，未知区块阻止连接；同一宿主多桥时全部拒绝。 */
public record MeBridgeTarget(BlockEntity host, Object generation, MeBridgeStatus status) {
	public static UUID owner(BlockEntity host) {
		return host instanceof NetworkCoreBlockEntity core ? core.owner() : host instanceof MachineControllerEntity core ? core.ownerId() : null;
	}
	public static boolean live(BlockEntity tile) {
		if (tile == null || tile.isRemoved() || !(tile.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return false;
		return loaded(level, tile.getBlockPos()) && at(level, tile.getBlockPos()) == tile;
	}
	private static boolean loaded(ServerLevel level, BlockPos pos) {
		return level.isOutsideBuildHeight(pos) || level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
	}
	private static BlockEntity at(ServerLevel level, BlockPos pos) {
		if (level.isOutsideBuildHeight(pos)) return null;
		var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockEntity(pos);
	}
	private static Object generation(BlockEntity host) {
		if (host instanceof NetworkCoreBlockEntity core) return core.validNetworkReference() && core.ownership().readyAuthority() != null ? core.network() : null;
		if (host instanceof MachineControllerEntity core) return core.readyIdentity() && core.formed() ? new MachineReference(core.machineId(), core.ownerId(), core.generation()) : null;
		return null;
	}
	public static MeBridgeTarget inspect(MeBridgeBlockEntity bridge) {
		if (!ModConfig.SERVER.beeNetwork.enabled.get()) return denied(MeBridgeStatus.DISABLED);
		if (!live(bridge) || bridge.owner() == null) return denied(MeBridgeStatus.HOST_UNAVAILABLE);
		var level = (ServerLevel) bridge.getLevel(); BlockEntity host = null;
		for (var side : Direction.values()) {
			var pos = bridge.getBlockPos().relative(side); if (!loaded(level, pos)) return denied(MeBridgeStatus.HOST_UNAVAILABLE);
			var candidate = at(level, pos);
			if (!bridge.owner().equals(owner(candidate))) continue;
			if (host != null) return denied(MeBridgeStatus.CONFLICT); host = candidate;
		}
		if (host == null || !live(host)) return denied(MeBridgeStatus.HOST_UNAVAILABLE);
		var generation = generation(host); if (generation == null) return denied(MeBridgeStatus.HOST_UNAVAILABLE);
		int bridges = 0;
		for (var side : Direction.values()) {
			var pos = host.getBlockPos().relative(side); if (!loaded(level, pos)) return denied(MeBridgeStatus.HOST_UNAVAILABLE);
			if (at(level, pos) instanceof MeBridgeBlockEntity candidate && bridge.owner().equals(candidate.owner())) bridges++;
		}
		return bridges == 1 ? new MeBridgeTarget(host, generation, MeBridgeStatus.ONLINE) : denied(MeBridgeStatus.CONFLICT);
	}
	public static MeBridgeStatus status(BlockEntity host, ServerPlayer player) {
		if (!MeBridgeIntegration.installed()) return MeBridgeStatus.ABSENT;
		if (!ModConfig.SERVER.beeNetwork.enabled.get()) return MeBridgeStatus.DISABLED;
		if (!live(host) || !player.isAlive() || player.isSpectator() || player.isRemoved() || player.level() != host.getLevel()) return MeBridgeStatus.HOST_UNAVAILABLE;
		if (!player.getUUID().equals(owner(host)) || !host.getLevel().mayInteract(player, host.getBlockPos())) return MeBridgeStatus.OWNER_ONLY;
		var level = (ServerLevel) host.getLevel(); MeBridgeBlockEntity bridge = null;
		for (var side : Direction.values()) {
			var pos = host.getBlockPos().relative(side); if (!loaded(level, pos)) return MeBridgeStatus.HOST_UNAVAILABLE;
			if (at(level, pos) instanceof MeBridgeBlockEntity candidate && player.getUUID().equals(candidate.owner())) {
				if (bridge != null) return MeBridgeStatus.CONFLICT; bridge = candidate;
			}
		}
		if (bridge == null) return MeBridgeStatus.NO_BRIDGE;
		if (!level.mayInteract(player, bridge.getBlockPos())) return MeBridgeStatus.OWNER_ONLY;
		var target = inspect(bridge); return target.status != MeBridgeStatus.ONLINE ? target.status : target.host != host ? MeBridgeStatus.CONFLICT : bridge.status();
	}
	private static MeBridgeTarget denied(MeBridgeStatus status) { return new MeBridgeTarget(null, null, status); }
}
