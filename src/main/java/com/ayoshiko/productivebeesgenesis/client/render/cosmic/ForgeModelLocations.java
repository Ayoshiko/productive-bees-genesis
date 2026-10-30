package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;

final class ForgeModelLocations {
    private ForgeModelLocations() {}
    static ModelResourceLocation inventory(ResourceLocation id) { return new ModelResourceLocation(id, "inventory"); }
    static ResourceLocation standalone(ResourceLocation id) { return id; }
}
