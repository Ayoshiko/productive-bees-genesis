package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.inventories.InternalInventory;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.parts.AEBasePart;
import java.util.Locale;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 会话内供应器身份；读写都只访问仍加载、仍有权限的原节点及原库存。 */
record AePatternProviderTarget(IGridNode node, PatternProviderLogicHost host, BlockEntity block,
        InternalInventory inventory, int size, String label, String location, String search, ItemStack icon) {
    static AePatternProviderTarget capture(ServerPlayer player, IGrid grid, IGridNode node) {
        if (!(node.getOwner() instanceof PatternProviderLogicHost host)) return null;
        var block = host.getBlockEntity();
        if (!accessible(player, grid, node, host, block)) return null;
        var inventory = host.getTerminalPatternInventory();
        if (inventory == null || inventory.size() <= 0) return null;
        var key = host.getTerminalIcon();
        var icon = key == null ? ItemStack.EMPTY : key.toStack(1);
        String name = icon.isEmpty() ? block.getBlockState().getBlock().getName().getString() : icon.getHoverName().getString();
        // AE2 的分组名称可能探测邻居能力；边界未加载时只用供应器自身名称。
        boolean neighborsLoaded = true;
        for (var side : Direction.values()) if (!block.getLevel().hasChunkAt(block.getBlockPos().relative(side))) neighborsLoaded = false;
        String group = neighborsLoaded ? host.getTerminalGroup().name().getString() : "";
        String location = block.getLevel().dimension().location() + " " + block.getBlockPos().toShortString()
                + (host instanceof AEBasePart part ? " " + part.getSide().getName() : "");
        String label = clip(location, 88) + " · " + clip(group.isBlank() ? name : group, 36);
        var target = new AePatternProviderTarget(node, host, block, inventory, inventory.size(), clip(label, 128), location,
                (clip(name, 128) + " " + clip(group, 128) + " " + location).toLowerCase(Locale.ROOT), icon);
        return target.valid(player, grid) ? target : null;
    }
    boolean valid(ServerPlayer player, IGrid grid) {
        return accessible(player, grid, node, host, block) && host.getTerminalPatternInventory() == inventory && inventory.size() == size;
    }
    private static boolean accessible(ServerPlayer player, IGrid grid, IGridNode node, PatternProviderLogicHost host, BlockEntity block) {
        if (block == null || block.isRemoved() || block.getLevel() != player.serverLevel()
                || !player.serverLevel().hasChunkAt(block.getBlockPos()) || player.serverLevel().getBlockEntity(block.getBlockPos()) != block
                || player.isSpectator() || !player.level().mayInteract(player, block.getBlockPos())
                || node.getOwner() != host || node.getLevel() != player.serverLevel() || node.getGrid() != grid || !node.isActive()
                || !(host instanceof IActionHost actionable) || actionable.getActionableNode() != node || host.getBlockEntity() != block) return false;
        if (host instanceof AEBasePart part) {
            var partHost = part.getHost();
            if (partHost == null || partHost.getBlockEntity() != block || partHost.getPart(part.getSide()) != part) return false;
        } else if (host != block) return false;
        return host.getGrid() == grid && host.isVisibleInTerminal();
    }
    static String clip(String text, int length) {
        return text.length() <= length ? text : text.substring(0, Character.isHighSurrogate(text.charAt(length - 1)) ? length - 1 : length);
    }
}
