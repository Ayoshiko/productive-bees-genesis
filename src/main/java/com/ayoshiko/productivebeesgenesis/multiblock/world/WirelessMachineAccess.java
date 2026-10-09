package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.apiculture.core.WirelessDeviceSession;
import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 无线访问直接复用独立机服务；内部核心仅解析同一已形成控制器。 */
public final class WirelessMachineAccess {
	public static MachineControllerEntity resolve(ServerPlayer player, BlockEntity target) {
		if (!player.server.isSameThread() || target == null || target.isRemoved() || target.getLevel() != player.level()
				|| !player.isAlive() || player.isSpectator() || player.distanceToSqr(target.getBlockPos().getCenter()) > 64) return null;
		var level = player.serverLevel(); var at = target.getBlockPos();
		var originChunk = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
		if (originChunk == null || originChunk.getBlockEntity(at) != target) return null;
		MachineControllerEntity core;
		if (target instanceof MachineControllerEntity controller) core = controller;
		else if (target instanceof MachinePartEntity part && part.getBlockState().is(MachineContent.block(StructureRole.CORE)) && part.bound()) {
			var binding = part.binding(); var pos = binding.handle().controller();
			var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk == null || !(chunk.getBlockEntity(pos) instanceof MachineControllerEntity controller) || controller.handle != binding.handle()) return null;
			core = controller;
		} else return null;
		return player.getUUID().equals(core.ownerId()) && core.readyIdentity() && MachineWorkService.access(core).isPresent() ? core : null;
	}
	public static boolean valid(MachineControllerEntity core, WirelessDeviceSession device, Player player) {
		if (!device.valid(player)) return false;
		var ref = device.binding().machine(); var pos = device.binding().position();
		var level = (ServerLevel) player.level(); var chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		if (ref == null || core.getLevel() != level || core.isRemoved() || !core.readyIdentity() || chunk == null || chunk.getBlockEntity(pos) != core
				|| !ref.machine().equals(core.machineId()) || !ref.owner().equals(core.ownerId()) || !ref.owner().equals(player.getUUID())
				|| ref.generation() != core.generation() || MachineWorkService.access(core).isEmpty()) { device.revoke(); return false; }
		return true;
	}
	public static boolean open(ServerPlayer player, WirelessDeviceSession device) {
		return open(player, device, net.minecraft.world.item.ItemStack.EMPTY);
	}
	public static boolean open(ServerPlayer player, WirelessDeviceSession device, net.minecraft.world.item.ItemStack pickTarget) {
		if (!device.valid(player) || device.binding().machine() == null) return false;
		var pos = device.binding().position(); var chunk = player.serverLevel().getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk != null && chunk.getBlockEntity(pos) instanceof MachineControllerEntity core && valid(core, device, player)
				&& MachineMenu.openWireless(core, player, device, pickTarget);
	}
	private WirelessMachineAccess() { }
}
