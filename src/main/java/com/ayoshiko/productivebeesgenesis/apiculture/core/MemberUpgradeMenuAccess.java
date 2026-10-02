package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import java.util.UUID;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 菜单生命周期内的单成员入口；固定源 BE／交接与权威域，不另建库存或全局引用。 */
public final class MemberUpgradeMenuAccess {
	private final NetworkCoreBlockEntity core;
	private final TileEntityMekanism source;
	private final NetworkSavedData authority;
	private final MemberClaim claim;
	private final Object bindingVersion;
	private MemberUpgradeMenuAccess(NetworkCoreBlockEntity core, TileEntityMekanism source, NetworkSavedData authority, MemberClaim claim) {
		this.core = core; this.source = source; this.authority = authority; this.claim = claim;
		bindingVersion = source.getPersistentData().get(MemberBinding.TAG);
	}
	public static boolean open(ServerPlayer player, BlockEntity source) {
		if (!(source instanceof TileEntityMekanism machine) || !(source.getLevel() instanceof ServerLevel level)
				|| !level.getServer().isSameThread() || source.isRemoved()
				|| source.getClass() != TileEntityMekApiary.class && source.getClass() != TileEntityMekCentrifuge.class) return false;
		MemberBinding.Reference binding;
		try { binding = MemberBinding.read(source); } catch (IllegalArgumentException invalid) { return false; }
		var origin = binding.network().origin();
		if (binding.mode() != MemberBinding.Mode.MANAGED || !origin.dimension().equals(level.dimension().location().toString())
				|| !level.hasChunk(origin.x() >> 4, origin.z() >> 4)) return false;
		if (!(level.getBlockEntity(new BlockPos(origin.x(), origin.y(), origin.z())) instanceof NetworkCoreBlockEntity core)
				|| !binding.network().equals(core.network())) return false;
		var authority = core.ownership().readyAuthority(); if (authority == null) return false;
		var record = authority.checkpoint().ownedMachines().get(binding.member()); if (record == null) return false;
		var access = new MemberUpgradeMenuAccess(core, machine, authority, record.claim());
		if (!access.ready(player)) return false;
		var session = UUID.randomUUID();
		return player.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> access.valid(viewer)
				? new NetworkCoreMenu(id, inventory, core, session, access) : null,
				Component.translatable("screen.productivebeesgenesis.network.member_upgrades")),
				buffer -> { buffer.writeBlockPos(core.getBlockPos()); buffer.writeUUID(session); buffer.writeBoolean(true); }).isPresent();
	}
	UUID member() { return claim.member(); }
	boolean ready(Player player) { return valid(player) && core.topology() != null; }
	boolean valid(Player player) {
		if (!(core.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || player.level() != level
				|| core.isRemoved() || !core.validNetworkReference()
				|| !level.hasChunk(core.getBlockPos().getX() >> 4, core.getBlockPos().getZ() >> 4)
				|| level.getBlockEntity(core.getBlockPos()) != core
				|| !player.isAlive() || player.isSpectator() || source.isRemoved() || source.getLevel() != level
				|| source.getPersistentData().get(MemberBinding.TAG) != bindingVersion
				|| player.distanceToSqr(source.getBlockPos().getCenter()) > 64 || !core.permits(player)
				|| core.ownership().readyAuthority() != authority) return false;
		var record = authority.checkpoint().ownedMachines().get(claim.member());
		if (record == null || record.phase() != OwnedMachineRecord.Phase.OWNED || !claim.equals(record.claim())
				|| !authority.identity().equals(core.network())) return false;
		var directory = NetworkPersistence.directory(level.getServer());
		// 有界审计暂时撤下拓扑时只保留菜单；所有查询和资产动作仍须经过 ready。
		if (core.topology() == null) return claim.equals(directory.claimAt(claim.origin()))
				&& level.hasChunk(source.getBlockPos().getX() >> 4, source.getBlockPos().getZ() >> 4)
				&& level.getBlockEntity(source.getBlockPos()) == source && new MachineAssetStore(source).empty();
		return source instanceof TileEntityMekApiary
				? ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekApiary.class) == source
				: ManagedProductionAccess.member(level, authority, directory, record, TileEntityMekCentrifuge.class) == source;
	}
}
