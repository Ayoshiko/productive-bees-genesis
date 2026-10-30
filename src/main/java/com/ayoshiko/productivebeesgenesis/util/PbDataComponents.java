package com.ayoshiko.productivebeesgenesis.util;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import javax.annotation.Nullable;
/** Productive Bees 12 native comb variant storage. */
public final class PbDataComponents {
    private PbDataComponents() {}
    @Nullable
    public static ResourceLocation getBeeType(ItemStack stack) {
        if (stack.isEmpty() || !stack.hasTag()) return null;
        var entityTag = stack.getTagElement("EntityTag");
        return entityTag == null ? null : ResourceLocation.tryParse(entityTag.getString("type"));
    }
    public static void setBeeType(ItemStack stack, ResourceLocation beeType) {
        stack.getOrCreateTagElement("EntityTag").putString("type", beeType.toString());
    }
}
