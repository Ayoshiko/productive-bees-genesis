package com.ayoshiko.productivebeesgenesis.mixin.jdte;

import com.ayoshiko.productivebeesgenesis.mek.IJdteCentrifugeFactory;
import com.jdte.common.blockentities.CoalescedAcceleratedMachine;
import org.spongepowered.asm.mixin.Mixin;

/** JDTE coalesced acceleration bridge for the optional Evolved Mekanism Extras factory. */
@Mixin(targets = "com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory",
		remap = false)
public abstract class JdteEMExtraCentrifugeFactoryCoalescedMixin implements CoalescedAcceleratedMachine {

	@Override
	public void accumulateAcceleratedTicks(int ticks) {
		((IJdteCentrifugeFactory) (Object) this).productivebeesgenesis$accumulateAcceleratedTicks(ticks);
	}

	@Override
	public void flushAcceleratedTicks() {
		((IJdteCentrifugeFactory) (Object) this).productivebeesgenesis$flushAcceleratedTicks();
	}
}
