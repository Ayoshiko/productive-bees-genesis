package com.ayoshiko.productivebeesgenesis.apiculture.ownership;

import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkCheckpointCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkIdentity;
import com.ayoshiko.productivebeesgenesis.mek.PbRecipeContext;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;

/** BE 只保存所有权引用；存在任何绑定（包括损坏记录）时均禁止独立执行。 */
public final class MemberBinding {
	public enum Mode { JOINING, MANAGED, LEAVING, RETURNED, ORPHANED, RECOVERY }
	public record Reference(NetworkIdentity network, UUID member, UUID transfer, Mode mode) { }
	public static final String TAG = "pbgNetworkBinding";
	private MemberBinding() { }
	public static boolean isolated(Object candidate) {
		return candidate instanceof PbRecipeContext && candidate instanceof BlockEntity tile && (tile.isRemoved() || tile.getPersistentData().contains(TAG)
				|| tile instanceof com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary hive && !hive.pendingCyclesReadable()
				|| com.ayoshiko.productivebeesgenesis.apiculture.persistence.NetworkPersistence.holdsMember(tile));
	}
	public static Reference read(BlockEntity tile) {
		var tag = tile.getPersistentData().getCompound(TAG);
		if (!tag.hasUUID("member") || !tag.hasUUID("transfer") || !tag.contains("mode", 8)) throw new IllegalArgumentException("Malformed member binding");
		return new Reference(NetworkCheckpointCodec.readIdentity(tag.getCompound("network")), tag.getUUID("member"), tag.getUUID("transfer"), Mode.valueOf(tag.getString("mode")));
	}
	public static void begin(BlockEntity tile, NetworkIdentity network, UUID member, UUID transfer) {
		check(tile); if (!(tile instanceof PbRecipeContext) || isolated(tile)) throw new IllegalStateException("Member is already bound or unsupported");
		write(tile, new Reference(network, member, transfer, Mode.JOINING)); suspend(tile);
	}
	public static void phase(BlockEntity tile, Reference expected, Mode mode) {
		check(tile); if (!read(tile).equals(expected)) throw new IllegalStateException("Member binding changed");
		write(tile, new Reference(expected.network(), expected.member(), expected.transfer(), mode));
	}
	public static void release(BlockEntity tile, Reference expected) {
		check(tile); if (!read(tile).equals(expected)) throw new IllegalStateException("Member binding changed");
		tile.getPersistentData().remove(TAG); tile.setChanged(); tile.getLevel().invalidateCapabilities(tile.getBlockPos());
		if (tile instanceof IAe2OutputHostBase host) host.productivebeesgenesis$getAe2LifecycleHandler().prepareForLoad(host);
	}
	private static void write(BlockEntity tile, Reference value) {
		var tag = new CompoundTag(); tag.put("network", NetworkCheckpointCodec.identity(value.network()));
		tag.putUUID("member", value.member()); tag.putUUID("transfer", value.transfer()); tag.putString("mode", value.mode().name());
		tile.getPersistentData().put(TAG, tag); tile.setChanged(); tile.getLevel().invalidateCapabilities(tile.getBlockPos());
	}
	public static void suspend(BlockEntity tile) {
		if (tile instanceof IAe2OutputHostBase host) {
			host.productivebeesgenesis$getAe2LifecycleHandler().destroyForChunkUnload(host);
			var tracker = host.productivebeesgenesis$getAe2StateHolder().getTickAccelTracker(); if (tracker != null) tracker.reset();
		}
		if (tile instanceof mekanism.common.tile.base.TileEntityMekanism machine) { machine.setActive(false); machine.sendUpdatePacket(); }
	}
	private static void check(BlockEntity tile) {
		if (!(tile.getLevel() instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread() || tile.isRemoved()) throw new IllegalStateException("Ownership changes require a live server member");
	}
}
