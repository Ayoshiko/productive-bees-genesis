package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.StorageCells;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiary.ContainerFlower;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@Tag("minecraft")
class Ae2ContainerFlowerMinecraftTest {
    private static final RegistryAccess REGISTRIES = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    @Test void jeiDisplayCellContainsTheActualKeyAndMinimumOnAnIndependentStack() {
        var key = AEItemKey.of(Items.DIAMOND);
        var resource = ResourceKey.aeKey(key.getType().getId(), key.getId(), key.toTag(REGISTRIES));
        var json = ResourceProductionRecipe.Output.CODEC.encodeStart(JsonOps.INSTANCE,
                new ResourceProductionRecipe.Output(resource, 32, 1)).getOrThrow().getAsJsonObject();
        json.remove("chance");
        var flower = ContainerFlower.parse(json.toString());
        var display = Ae2ContainerFlowerDisplay.cell(flower, REGISTRIES);
        var inventory = StorageCells.getCellInventory(display, null);
        assertNotNull(inventory);
        assertEquals(32, inventory.extract(key, 64, Actionable.SIMULATE, IActionSource.empty()));
        assertTrue(flower.matches(display, REGISTRIES));
        var other = Ae2ContainerFlowerDisplay.cell(flower, REGISTRIES);
        inventory.extract(key, 32, Actionable.MODULATE, IActionSource.empty());
        inventory.persist();
        assertTrue(flower.matches(other, REGISTRIES));
    }
    @Test void filledCellUsesExactKeyAndSimulationWhileWrappedTemplatesNeverQualify() {
        var template = new ItemStack(Items.DIAMOND); template.set(DataComponents.CUSTOM_NAME, Component.literal("Precise flower"));
        var key = AEItemKey.of(template);
        var resource = ResourceKey.aeKey(key.getType().getId(), key.getId(), key.toTag(REGISTRIES));
        var json = ResourceProductionRecipe.Output.CODEC.encodeStart(JsonOps.INSTANCE,
                new ResourceProductionRecipe.Output(resource, 32, 1)).getOrThrow().getAsJsonObject();
        json.remove("chance"); var flower = ContainerFlower.parse(json.toString());
        var stack = AEItems.ITEM_CELL_1K.stack();
        var cell = StorageCells.getCellInventory(stack, null); assertNotNull(cell);
        assertFalse(flower.matches(stack, REGISTRIES));
        assertEquals(64, cell.insert(AEItemKey.of(Items.DIAMOND), 64, Actionable.MODULATE, IActionSource.empty())); cell.persist();
        assertFalse(flower.matches(stack, REGISTRIES), "Same item without matching name is not the resource");
        assertEquals(31, cell.insert(key, 31, Actionable.MODULATE, IActionSource.empty())); cell.persist(); assertFalse(flower.matches(stack, REGISTRIES));
        cell.insert(key, 1, Actionable.MODULATE, IActionSource.empty()); cell.persist();
        var before = stack.copy();
        assertTrue(flower.matches(stack, REGISTRIES)); assertTrue(ItemStack.matches(before, stack));
        assertEquals(32, cell.extract(key, 64, Actionable.SIMULATE, IActionSource.empty()));
        assertFalse(flower.matches(GenericStack.wrapInItemStack(new GenericStack(key, Long.MAX_VALUE)), REGISTRIES));
        assertFalse(flower.matches(new ItemStack(Items.DIAMOND, 64), REGISTRIES));
    }
}
