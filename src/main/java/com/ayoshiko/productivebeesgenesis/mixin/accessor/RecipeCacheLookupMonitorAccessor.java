package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.api.recipes.cache.CachedRecipe;
import mekanism.common.recipe.lookup.monitor.RecipeCacheLookupMonitor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Accessor used when a per-machine recipe mode changes without an inventory mutation. */
@Mixin(value = RecipeCacheLookupMonitor.class, remap = false)
public interface RecipeCacheLookupMonitorAccessor {

	@Accessor("cachedRecipe")
	void productivebeesgenesis$setCachedRecipe(CachedRecipe<?> recipe);
}
