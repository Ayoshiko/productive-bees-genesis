package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceNativeType;
import net.minecraft.core.component.DataComponentType;
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
        var stack = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(id)).map(ItemStack::new).orElse(ItemStack.EMPTY);
        return type == ResourceNativeType.SOURCE && !stack.isEmpty() ? fullSourceJar(stack) : stack;
    }

    private static ItemStack fullSourceJar(ItemStack stack) {
        var component = BuiltInRegistries.DATA_COMPONENT_TYPE
                .getOptional(ResourceLocation.parse("ars_nouveau:block_fill_contents"))
                .orElse(null);
        if (component == null) {
            return stack;
        }
        try {
            var contentsType = Class.forName("com.hollingsworth.arsnouveau.common.items.data.BlockFillContents");
            var contents = contentsType.getConstructor(int.class).newInstance(10_000);
            setSourceContents(stack, component, contents);
        } catch (ReflectiveOperationException exception) {
            ProductiveBeesGenesis.LOGGER.warn("无法创建满魔源 Source Jar 页签图标", exception);
        }
        return stack;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setSourceContents(ItemStack stack, DataComponentType<?> component, Object contents) {
        stack.set((DataComponentType) component, contents);
    }
}
