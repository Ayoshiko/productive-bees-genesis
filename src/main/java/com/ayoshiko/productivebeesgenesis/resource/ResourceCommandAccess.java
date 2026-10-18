package com.ayoshiko.productivebeesgenesis.resource;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** 命令与后续交互共用的身份边界；从已认证来源取得玩家，不接受客户端自报 owner。 */
final class ResourceCommandAccess {
	private ResourceCommandAccess() {}

	static ResourceAccount account(CommandSourceStack source, UUID id) throws CommandSyntaxException {
		var player = source.getPlayerOrException();
		var account = ResourceRuntime.accounts(source.getServer()).accounts().find(id);
		if (account == null || (!account.owner().equals(player.getUUID()) && !source.hasPermission(2))) {
			throw new IllegalArgumentException("account_access");
		}
		return account;
	}

	static BlockEntity hive(ServerPlayer player, BlockPos pos) {
		var level = player.serverLevel();
		if (player.isSpectator() || !player.mayBuild() || !player.canInteractWithBlock(pos, 0)
				|| !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4) || !level.mayInteract(player, pos)) {
			throw new IllegalArgumentException("host_access");
		}
		var hive = level.getBlockEntity(pos);
		if (!ResourceHiveHost.supports(hive)) {
			throw new IllegalArgumentException("not_hive");
		}
		if (!ResourceHiveHost.canAccess(player, hive)) throw new IllegalArgumentException("host_access");
		return hive;
	}

	static BlockEntity boundHive(CommandSourceStack source, ResourceAccount account)
			throws CommandSyntaxException {
		var player = source.getPlayerOrException();
		var binding = account.binding();
		if (binding == null || !player.level().dimension().location().equals(binding.dimension())) {
			throw new IllegalArgumentException("host_access");
		}
		var hive = hive(player, BlockPos.of(binding.packedPosition()));
		var host = ResourceHiveHost.existing(hive);
		if (host == null || host.account(hive) != account) throw new IllegalArgumentException("host_access");
		return hive;
	}

	static ResourceAccount.Reference reference(ResourceAccount account, long generation) {
		var expected = new ResourceAccount.Reference(account.reference().sourceId(), generation);
		if (!expected.equals(account.reference())) throw new IllegalArgumentException("stale");
		return expected;
	}
}
