package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;
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

public final class MeBridgeBlock extends BaseEntityBlock {
	public MeBridgeBlock() { super(Properties.of().mapColor(MapColor.GOLD).strength(4).sound(SoundType.METAL)); }
	@Override public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context,
			java.util.List<net.minecraft.network.chat.Component> tooltip, net.minecraft.world.item.TooltipFlag flag) {
		tooltip.add(net.minecraft.network.chat.Component.translatable("block.productivebeesgenesis.bee_network_me_bridge.hint"));
	}
	@Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
	@Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
	@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new MeBridgeBlockEntity(pos, state); }
	@Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
		return level.isClientSide ? null : createTickerHelper(type, NetworkContent.ME_BRIDGE_TILE.get(), (world, pos, block, tile) -> tile.serverTick());
	}
	@Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof MeBridgeBlockEntity bridge) bridge.initializeOwner(player.getUUID());
	}
	@Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof MeBridgeBlockEntity bridge)
			server.displayClientMessage(server.getUUID().equals(bridge.owner()) ? bridge.status().message() : MeBridgeStatus.OWNER_ONLY.message(), true);
		return InteractionResult.sidedSuccess(level.isClientSide);
	}
}
