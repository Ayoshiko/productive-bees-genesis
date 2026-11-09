package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import mekanism.api.security.IBlockSecurityUtils;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** 拆卸只在完整物品已能交入背包后提交，预览不清空机器资产。 */
public final class MachineDismantle {

	// 掉落事件可以重入；范围只保留当前服务器调用栈上的具体机器实例。
	private static final ThreadLocal<Set<BlockEntity>> ACTIVE = new ThreadLocal<>();

	private MachineDismantle() {
	}

	public static boolean isPreviewing(BlockEntity tile) {
		Set<BlockEntity> active = ACTIVE.get();
		return active != null && active.contains(tile);
	}

	public static boolean tryDismantle(BlockState state, Level level, BlockPos pos,
			TileEntityMekanism tile, Player player, ItemStack tool) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)
				|| !canDismantle(state, serverLevel, pos, tile, serverPlayer, tool)) {
			return false;
		}
		Set<BlockEntity> active = ACTIVE.get();
		if (active == null) {
			active = Collections.newSetFromMap(new IdentityHashMap<>());
			ACTIVE.set(active);
		}
		if (!active.add(tile)) {
			return false;
		}
		try {
			// 沿上游掉落事件取得最终列表，但两个本地 getDrops 在此调用域只序列化。
			List<ItemStack> drops = WorldUtils.getDrops(state, serverLevel, pos, tile, serverPlayer, tool);
			if (!hasSingleMachine(drops, state)
					|| !canDismantle(state, serverLevel, pos, tile, serverPlayer, tool)) {
				return false;
			}
			PlayerInventoryTransfer.Plan transfer = PlayerInventoryTransfer.prepare(serverPlayer, drops);
			if (transfer == null) {
				serverPlayer.displayClientMessage(
						Component.translatable("message.productivebeesgenesis.inventory_full"), true);
				return false;
			}
			if (!transfer.commit()) {
				return false;
			}
			return removeTransferredMachine(state, serverLevel, pos, tile, serverPlayer, transfer);
		} finally {
			active.remove(tile);
			if (active.isEmpty()) {
				ACTIVE.remove();
			}
		}
	}

	private static boolean canDismantle(BlockState state, ServerLevel level, BlockPos pos,
			TileEntityMekanism tile, ServerPlayer player, ItemStack tool) {
		if (player.level() != level || player.isSpectator() || !player.isAlive() || !player.mayBuild()
				|| !player.canInteractWithBlock(pos, 0)
				|| !level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
				|| !level.mayInteract(player, pos) || tile.isRemoved() || tile.getLevel() != level
				|| !tile.getBlockPos().equals(pos) || level.getBlockEntity(pos) != tile
				|| !level.getBlockState(pos).equals(state) || tile.getRadiationScale() > 0
				|| tool.isEmpty() || !WrenchCapabilityHelper.canUseAsWrench(tool)) {
			return false;
		}
		if (!(state.getBlock() instanceof MekApiaryBlock<?, ?>)
				&& !(state.getBlock() instanceof MekCentrifugeBlock<?, ?>)) {
			return false;
		}
		return IBlockSecurityUtils.INSTANCE.canAccessOrDisplayError(player, level, pos, state, tile);
	}

	private static boolean hasSingleMachine(List<ItemStack> drops, BlockState state) {
		int machineCount = 0;
		for (ItemStack drop : drops) {
			if (drop.is(state.getBlock().asItem())) {
				if (drop.getCount() != 1 || machineCount != 0) {
					return false;
				}
				machineCount = 1;
			}
		}
		return machineCount == 1;
	}

	private static boolean removeTransferredMachine(BlockState state, ServerLevel level, BlockPos pos,
			TileEntityMekanism tile, ServerPlayer player, PlayerInventoryTransfer.Plan transfer) {
		boolean removed = false;
		RuntimeException failure = null;
		try {
			removed = level.removeBlock(pos, false);
		} catch (RuntimeException exception) {
			failure = exception;
		}
		boolean originalPresent = level.getBlockEntity(pos) == tile
				&& level.getBlockState(pos).equals(state) && !tile.isRemoved();
		if (originalPresent) {
			if (!transfer.rollback()) {
				throw new IllegalStateException("Machine dismantle could not restore the changed inventory at "
						+ pos + " for " + player.getUUID(), failure);
			}
			if (failure != null) {
				ProductiveBeesGenesis.LOGGER.error("拆卸机器失败，已撤回背包交付，位置 {}，玩家 {}",
						pos, player.getUUID(), failure);
			}
			player.displayClientMessage(
					Component.translatable("message.productivebeesgenesis.dismantle_failed"), true);
			return false;
		}
		// 只有源实例已不在世界中才释放其槽位；异常后的已交付物品不再次回填。
		if (level.getBlockEntity(pos) != tile) {
			clearTransferredMachine(tile);
		}
		if (!removed || failure != null || level.getBlockEntity(pos) == tile) {
			ProductiveBeesGenesis.LOGGER.error("拆卸移除结果异常，保留已交付物品，位置 {}，玩家 {}",
					pos, player.getUUID(), failure);
			player.displayClientMessage(
					Component.translatable("message.productivebeesgenesis.dismantle_failed"), true);
			return false;
		}
		level.playSound(null, player.getX(), player.getY(), player.getZ(),
				SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F, 1.0F);
		return true;
	}

	private static void clearTransferredMachine(TileEntityMekanism tile) {
		if (tile instanceof TileEntityMekApiary apiary) {
			apiary.markDropsSerialized();
			apiary.saveAllItemsForDrop();
		} else if (tile instanceof TileEntityMekCentrifuge centrifuge) {
			centrifuge.markDropsSerialized();
			centrifuge.saveAllItemsForDrop();
		}
	}
}
