package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.mek.IJdteCentrifugeFactory;
import com.jdte.common.blockentities.CoalescedAcceleratedMachine;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Adds JDTE's coalesced acceleration contract to the optional Mekanism Extras factory.
 * The target is a string so this mixin class remains loadable without Mekanism Extras;
 * {@code MixinConfigPlugin} requires both JDTE and Mekanism Extras before applying it.
 */
@Mixin(targets = "com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory",
		remap = false)
public abstract class JdteExtraCentrifugeFactoryCoalescedMixin implements CoalescedAcceleratedMachine {

	@Override
	public void accumulateAcceleratedTicks(int ticks) {
		((IJdteCentrifugeFactory) (Object) this).productivebeesgenesis$accumulateAcceleratedTicks(ticks);
	}

	@Override
	public void flushAcceleratedTicks() {
		((IJdteCentrifugeFactory) (Object) this).productivebeesgenesis$flushAcceleratedTicks();
	}
}
