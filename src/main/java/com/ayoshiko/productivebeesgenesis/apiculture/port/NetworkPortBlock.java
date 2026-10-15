package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.BlockHitResult;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkContent;

public final class NetworkPortBlock extends BaseEntityBlock {
    private final PortState.Kind kind;
    public NetworkPortBlock(PortState.Kind kind) {
        super(Properties.of().mapColor(MapColor.GOLD).strength(4).sound(SoundType.METAL).pushReaction(PushReaction.BLOCK));
        this.kind = kind;
    }
    public PortState.Kind kind() { return kind; }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return MapCodec.unit(this); }
    @Override protected RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new NetworkPortBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, NetworkContent.PORT_TILE.get(), (world, pos, block, tile) -> tile.serverTick());
    }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player && level.getBlockEntity(pos) instanceof NetworkPortBlockEntity port)
            port.initializeOwner(player.getUUID());
    }
    @Override protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos from, boolean moving) {
        if (level.getBlockEntity(pos) instanceof NetworkPortBlockEntity port) port.connectionChanged();
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof NetworkPortBlockEntity port && port.open(server))
            return InteractionResult.CONSUME;
        return InteractionResult.FAIL;
    }
    @Override public void appendHoverText(ItemStack stack, Item.TooltipContext context, java.util.List<net.minecraft.network.chat.Component> tooltip, TooltipFlag flag) {
        tooltip.add(NetworkPortMenu.text("hint." + kind.name().toLowerCase(java.util.Locale.ROOT)));
    }
}
