package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalScope;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/** 无生产 ticker 的专用入口；合成资产留在独立账户，不随方块掉落或移动。 */
public final class NetworkTerminalBlock extends BaseEntityBlock {
	public static final DirectionProperty FACING = BlockStateProperties.FACING;
	private static final VoxelShape[] SHAPES = {
			Block.box(2, 13, 2, 14, 16, 14), Block.box(2, 0, 2, 14, 3, 14),
			Block.box(2, 2, 13, 14, 14, 16), Block.box(2, 2, 0, 14, 14, 3),
			Block.box(13, 2, 2, 16, 14, 14), Block.box(0, 2, 2, 3, 14, 14)
	};
	private final TerminalScope scope;
	private final boolean combined;
	public NetworkTerminalBlock(TerminalScope scope) {
		this(scope, false);
	}
	public NetworkTerminalBlock(TerminalScope scope, boolean combined) {
		super(Properties.of().mapColor(MapColor.GOLD).strength(4).sound(SoundType.METAL).noOcclusion().pushReaction(net.minecraft.world.level.material.PushReaction.BLOCK));
		if (scope == TerminalScope.ALL) throw new IllegalArgumentException("Terminal requires a machine scope");
		this.scope = scope; this.combined = combined;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
	}
	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }
	@Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getClickedFace()); }
	@Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) { return SHAPES[state.getValue(FACING).ordinal()]; }
	@Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
	@Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
	public TerminalScope scope() { return scope; }
	public boolean combined() { return combined; }
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
