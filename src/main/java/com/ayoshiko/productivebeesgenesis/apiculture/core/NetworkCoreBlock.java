package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public final class NetworkCoreBlock extends BaseEntityBlock {
	public NetworkCoreBlock() { super(Properties.of().mapColor(MapColor.GOLD).strength(4).sound(SoundType.METAL)); }
	@Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
	@Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
	@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NetworkCoreBlockEntity(pos, state); }
	@Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, NetworkContent.CORE_TILE.get(), (world, pos, block, tile) -> tile.serverTick());
	}
	@Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof NetworkCoreBlockEntity core) core.initializeOwner(player.getUUID());
	}
	@Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof NetworkCoreBlockEntity core && core.allowed(player)) {
			if (player.isShiftKeyDown()) core.toggleFace(hit.getDirection()); else core.openTerminal(serverPlayer);
			return InteractionResult.CONSUME;
		}
		return InteractionResult.FAIL;
	}
}
