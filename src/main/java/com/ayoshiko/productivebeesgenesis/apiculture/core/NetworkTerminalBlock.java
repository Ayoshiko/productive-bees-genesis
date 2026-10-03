package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/** 无库存和 ticker 的专用入口；不参与生产成员或拓扑传播。 */
public final class NetworkTerminalBlock extends BaseEntityBlock {
	private final TerminalScope scope;
	public NetworkTerminalBlock(TerminalScope scope) {
		super(Properties.of().mapColor(MapColor.GOLD).strength(4).sound(SoundType.METAL));
		if (scope == TerminalScope.ALL) throw new IllegalArgumentException("Terminal requires a machine scope");
		this.scope = scope;
	}
	public TerminalScope scope() { return scope; }
	@Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
	@Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
	@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NetworkTerminalBlockEntity(pos, state); }
	@Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block source, BlockPos from, boolean moving) {
		if (!level.isClientSide && level.getBlockEntity(pos) instanceof NetworkTerminalBlockEntity terminal) terminal.connection();
	}
	@Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (level.isClientSide) return InteractionResult.SUCCESS;
		return player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof NetworkTerminalBlockEntity terminal
				&& NetworkTerminalAccess.open(server, terminal) ? InteractionResult.CONSUME : InteractionResult.FAIL;
	}
}
