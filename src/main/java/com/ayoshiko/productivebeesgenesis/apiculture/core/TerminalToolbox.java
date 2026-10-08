package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.ayoshiko.productivebeesgenesis.apiculture.bridge.MeBridgeIntegration;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** 原网络工具的九格组件投影；不额外保存工具包资产或自动切换到另一件工具。 */
public final class TerminalToolbox {
    public static final int START = 51, END = 60;
    private final TerminalCraftingMenu.Host host;
    private final DataSlot source = DataSlot.standalone();
    private final SimpleContainer contents = new SimpleContainer(9) {
        @Override public void setItem(int index, ItemStack stack) {
            if (stack.getCount() > Math.min(64, stack.getMaxStackSize())) throw new IllegalArgumentException("Oversized toolbox stack");
            super.setItem(index, stack);
        }
    };
    private ItemStack tool;
    private ItemContainerContents expected;
    private int sourceIndex = -1;
    private boolean synchronizing, closed;

    public TerminalToolbox(TerminalCraftingMenu.Host host) {
        this.host = host;
        if (host.craftingPlayer() instanceof ServerPlayer player) {
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var candidate = player.getInventory().getItem(i);
                if (!MeBridgeIntegration.toolboxItem(candidate)) continue;
                var stored = candidate.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                if (candidate.getCount() != 1 || stored.getSlots() > 9 || stored.stream().anyMatch(stack -> stack.getCount() > Math.min(64, stack.getMaxStackSize()))) {
                    com.mojang.logging.LogUtils.getLogger().warn("Network toolbox at player {} slot {} has unsupported contents; left unchanged", player.getUUID(), i);
                    break;
                }
                tool = candidate; expected = stored; sourceIndex = i; source.set(i + 1);
                for (int slot = 0; slot < stored.getSlots(); slot++) contents.setItem(slot, stored.getStackInSlot(slot));
                break;
            }
        }
        contents.addListener(ignored -> publish());
    }
    public DataSlot data() { return source; }
    public boolean present() { return !closed && source.get() > 0; }
    public boolean locks(ItemStack stack) {
        if (!present() || stack.isEmpty()) return false;
        return host.craftingPlayer() instanceof ServerPlayer ? stack == tool : stack == host.craftingPlayer().getInventory().getItem(source.get() - 1);
    }
    private boolean current() {
        return tool != null && tool.getCount() == 1 && host.craftingPlayer().getInventory().getItem(sourceIndex) == tool
                && tool.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY) == expected;
    }
    /** tick 与一次原生点击开始时检查；失效只撤销投影，不修改原物。 */
    public void refresh() {
        if (closed || !(host.craftingPlayer() instanceof ServerPlayer) || tool == null || current()) return;
        close();
    }
    public void publish() {
        if (synchronizing || closed || !(host.craftingPlayer() instanceof ServerPlayer) || tool == null) return;
        var next = ItemContainerContents.fromItems(contents.getItems());
        if (next.equals(expected)) return;
        if (!current()) throw new IllegalStateException("Network toolbox changed during an inventory edit");
        tool.set(DataComponents.CONTAINER, next); expected = next;
        host.craftingPlayer().getInventory().setChanged();
    }
    public Slot slot(int toolboxIndex, int x, int y) {
        return new Slot(contents, toolboxIndex, x, y) {
            @Override public boolean isActive() { return present(); }
            @Override public boolean mayPlace(ItemStack stack) {
                return present() && (!(host.craftingPlayer() instanceof ServerPlayer) || current()) && !host.lockedNativeStack(stack)
                        && MeBridgeIntegration.toolboxUpgrade(stack);
            }
            @Override public boolean mayPickup(Player player) {
                return player == host.craftingPlayer() && present() && (!(player instanceof ServerPlayer) || current());
            }
            @Override public int getMaxStackSize() { return 64; }
        };
    }
    public void layout(int left, int top) {
        if (host.craftingPlayer() instanceof ServerPlayer) return;
        var slots = host.craftingMenu().slots;
        for (int i = 0; i < 9; i++) {
            var slot = slot(i, left + i % 3 * 18, top + i / 3 * 18); slot.index = START + i; slots.set(slot.index, slot);
        }
    }
    public void close() {
        if (closed) return;
        closed = true; tool = null; expected = null; sourceIndex = -1; source.set(0);
        synchronizing = true;
        try { contents.clearContent(); } finally { synchronizing = false; }
    }
}
