package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/** 只将万象的蛋、蜜脾和蜜脾块关联到万象页面。 */
public final class MyriadGuideLink {
    private MyriadGuideLink() {}

    public static boolean matches(ItemStack stack) {
        var item = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (!item.getNamespace().equals("productivebees")) return false;
        return switch (item.getPath()) {
            case "configurable_honeycomb", "configurable_comb" ->
                    PBConstants.MYRIADCREATIONS_TYPE.equals(stack.get(ModDataComponents.BEE_TYPE.get()));
            case "spawn_egg_configurable_bee" -> {
                var data = stack.get(DataComponents.ENTITY_DATA);
                yield data != null && PBConstants.MYRIADCREATIONS_TYPE_STRING.equals(data.copyTag().getString("type"));
            }
            default -> false;
        };
    }
}
