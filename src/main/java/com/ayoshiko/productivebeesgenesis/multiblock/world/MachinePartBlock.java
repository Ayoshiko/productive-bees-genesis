package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.StructureRole;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

public final class MachinePartBlock extends BaseEntityBlock implements MachineContent.RoleBlock {
	public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
	public static final BooleanProperty FORMED = BooleanProperty.create("formed");
	private final StructureRole role;
	MachinePartBlock(StructureRole role) {
		super(Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(4).sound(SoundType.METAL)); this.role = role;
		registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FORMED, false));
	}
	@Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
	@Override public StructureRole role() { return role; }
	@Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
	@Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, FORMED); }
	@Override public BlockState getStateForPlacement(BlockPlaceContext context) { return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite()); }
	@Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
	@Override protected BlockState mirror(BlockState state, Mirror mirror) { return rotate(state, mirror.getRotation(state.getValue(FACING))); }
	@Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return role == StructureRole.CONTROLLER ? new MachineControllerEntity(pos, state) : new MachinePartEntity(pos, state); }
	@Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof MachineControllerEntity core) core.initializeOwner(player.getUUID());
	}
	@Override protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
		super.onPlace(state, level, pos, previous, moved);
		if (state.getBlock() != previous.getBlock() || state.getValue(FACING) != previous.getValue(FACING)) MachineWorldService.changed(level, pos);
	}
	@Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
		if (state.getBlock() != next.getBlock() || state.getValue(FACING) != next.getValue(FACING)) MachineWorldService.changed(level, pos);
		super.onRemove(state, level, pos, next, moved);
	}
	@Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (role != StructureRole.CONTROLLER) return InteractionResult.PASS;
		if (level.isClientSide) return InteractionResult.SUCCESS;
		if (!(level.getBlockEntity(pos) instanceof MachineControllerEntity core) || !core.allowed(player)) return InteractionResult.FAIL;
		if (player.isShiftKeyDown()) MachineWorldService.request(core);
		player.displayClientMessage(Component.translatable("message.productivebeesgenesis.machine." + core.status().name().toLowerCase(java.util.Locale.ROOT)), true);
		return InteractionResult.CONSUME;
	}
}
