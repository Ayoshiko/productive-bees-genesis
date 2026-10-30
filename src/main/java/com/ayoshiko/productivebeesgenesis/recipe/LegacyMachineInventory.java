package com.ayoshiko.productivebeesgenesis.recipe;

import java.util.List;
import mekanism.api.DataHandlerUtils;
import mekanism.api.NBTConstants;
import mekanism.api.inventory.IInventorySlot;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EntityBlock;

/** Uses each target tile's real slot layout without attaching it to a level. */
final class LegacyMachineInventory {
    private LegacyMachineInventory() {}
    static List<IInventorySlot> read(ItemStack item) {
        if (!(item.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof EntityBlock block)) return null;
        if (!(block.newBlockEntity(BlockPos.ZERO, blockItem.getBlock().defaultBlockState()) instanceof TileEntityMekanism tile)) return null;
        List<IInventorySlot> slots = tile.getInventorySlots(null);
        CompoundTag data = item.getTagElement("BlockEntityTag");
        if (data != null) DataHandlerUtils.readContainers(slots, data.getList(NBTConstants.ITEMS, Tag.TAG_COMPOUND));
        return slots;
    }
    static List<IInventorySlot> emptyTarget(ItemStack item) {
        ItemStack clean = item.copy();
        clean.removeTagKey("BlockEntityTag");
        return read(clean);
    }
    static void write(ItemStack item, List<IInventorySlot> slots) {
        item.getOrCreateTagElement("BlockEntityTag").put(NBTConstants.ITEMS, DataHandlerUtils.writeContainers(slots));
    }
    static boolean transferCentrifuge(List<ItemStack> inputs, ItemStack result) {
        List<IInventorySlot> target = emptyTarget(result);
        if (target == null) return false;
        for (ItemStack input : inputs) {
            List<IInventorySlot> source = read(input);
            if (source == null) return false;
            for (IInventorySlot sourceSlot : source) {
                ItemStack remaining = sourceSlot.getStack().copy();
                for (IInventorySlot targetSlot : target) {
                    if (remaining.isEmpty()) break;
                    // Keep energy, inputs and outputs in their respective slot roles.
                    if (role(sourceSlot) == role(targetSlot)) remaining = ApiaryCraftingInventoryTransfer.mergeIntoSlot(targetSlot, remaining);
                }
                if (!remaining.isEmpty()) return false;
            }
        }
        write(result, target);
        return true;
    }
    private static int role(IInventorySlot slot) {
        if (slot instanceof mekanism.common.inventory.slot.EnergyInventorySlot) return 0;
        if (slot instanceof mekanism.common.inventory.slot.OutputInventorySlot) return 1;
        return 2;
    }
}
