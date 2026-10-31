package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import net.minecraft.server.packs.CompositePackResources;
import net.minecraft.server.packs.PackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** 来源判定沿复合资源包的真实优先顺序，只读子包列表。 */
@Mixin(CompositePackResources.class)
public interface CompositePackResourcesAccessor {
	@Accessor("packResourcesStack")
	List<PackResources> productivebeesgenesis$packResourcesStack();
}
