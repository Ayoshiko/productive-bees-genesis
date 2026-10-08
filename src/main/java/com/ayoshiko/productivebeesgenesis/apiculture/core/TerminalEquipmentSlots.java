package com.ayoshiko.productivebeesgenesis.apiculture.core;

import com.mojang.datafixers.util.Pair;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/** 固定追加的真实装备槽；不改变已有背包、材料和结果槽的序号。 */
public final class TerminalEquipmentSlots {
    public static final int START = 46, OFFHAND = 50, END = 51;
    private static final EquipmentSlot[] TYPES = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND};
    private static final ResourceLocation[] ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS, InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD};
    private final TerminalCraftingMenu.Host host;
    private boolean visible;
    public TerminalEquipmentSlots(TerminalCraftingMenu.Host host) { this.host = host; }

    public Slot slot(int equipmentIndex, int x, int y) {
        var player = host.craftingPlayer(); var type = TYPES[equipmentIndex];
        return new Slot(player.getInventory(), equipmentIndex == 4 ? 40 : 39 - equipmentIndex, x, y) {
            @Override public boolean isActive() { return player instanceof ServerPlayer || visible; }
            @Override public int getMaxStackSize() { return equipmentIndex == 4 ? super.getMaxStackSize() : 1; }
            @Override public boolean mayPlace(ItemStack stack) {
                return !host.lockedNativeStack(getItem()) && !host.lockedNativeStack(stack) && (equipmentIndex == 4 || stack.canEquip(type, player));
            }
            @Override public boolean mayPickup(net.minecraft.world.entity.player.Player viewer) {
                return viewer == player && !host.lockedNativeStack(getItem())
                        && (equipmentIndex == 4 || getItem().isEmpty() || viewer.isCreative() || !EnchantmentHelper.has(getItem(), EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE));
            }
            @Override public void setByPlayer(ItemStack next, ItemStack previous) {
                player.onEquipItem(type, previous, next); super.setByPlayer(next, previous);
            }
            @Override public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() { return Pair.of(InventoryMenu.BLOCK_ATLAS, ICONS[equipmentIndex]); }
        };
    }
    /** 客户端只替换坐标，容器索引、限制与服务器实物不变。 */
    public void layout(int armorX, int armorY, int offhandX, int offhandY) {
        if (host.craftingPlayer() instanceof ServerPlayer) return;
        visible = true; var slots = host.craftingMenu().slots;
        for (int i = 0; i < 5; i++) {
            var slot = slot(i, i == 4 ? offhandX : armorX, i == 4 ? offhandY : armorY + i * 18);
            slot.index = START + i; slots.set(slot.index, slot);
        }
    }
    public static int preferredSlot(net.minecraft.world.entity.player.Player player, ItemStack stack) {
        return switch (player.getEquipmentSlotForItem(stack)) {
            case HEAD -> START; case CHEST -> START + 1; case LEGS -> START + 2; case FEET -> START + 3; case OFFHAND -> OFFHAND;
            default -> -1;
        };
    }
}
