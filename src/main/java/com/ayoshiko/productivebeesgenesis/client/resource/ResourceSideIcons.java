package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** 配置分组使用可辨认的容器或物流物品，图标不携带资源余额。 */
final class ResourceSideIcons {
    private ResourceSideIcons() {}

    static ItemStack icon(ResourceNativeType type) {
        if (!type.available()) return ItemStack.EMPTY;
        String id = switch (type) {
            case CHEMICAL -> null;
            case ENERGY -> "mekanism:basic_energy_cube";
            case SOURCE -> "ars_nouveau:source_jar";
            case SOUL -> "industrialforegoingsouls:soul_surge";
            case AIR -> "pneumaticcraft:pressure_gauge";
            case EXPERIENCE -> "minecraft:experience_bottle";
            case LIGHTNING -> "ae2lt:lightning_collapse_matrix";
        };
        if (id == null) return ItemStack.EMPTY;
        var stack = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(id)).map(ItemStack::new).orElse(ItemStack.EMPTY);
        return type == ResourceNativeType.SOURCE && !stack.isEmpty() ? fullSourceJar(stack) : stack;
    }

    private static ItemStack fullSourceJar(ItemStack stack) { stack.getOrCreateTagElement("BlockEntityTag").putInt("source",10_000);return stack; }
}
