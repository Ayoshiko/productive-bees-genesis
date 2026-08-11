package com.ayoshiko.productivebeesgenesis.mixin.mekenergistics;

import com.ayoshiko.productivebeesgenesis.compat.mekenergistics.MekEnergisticsBlockGuard;

import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * Prevents Mek-Energistics from resolving our centrifuge/apiary machines into
	 * ME machines through the Mekanism machine provider.
	 * <p>
	 * Background: the centrifuge reuses the Mekanism factory pipeline and is
	 * registered with {@code FactoryType.SMELTING}, so the block carries Mekanism's
	 * {@code AttributeFactoryType}. Mek-Energistics'
	 * {@code MekanismMachineProvider.resolveOriginalMachine} reads that attribute
	 * and would map the basic machine to {@code me_energized_smelter} and the
	 * factory tiers to the corresponding ME smelting factories.
	 * <p>
	 * This mixin returns null at the provider entry point for every block of our
	 * machine family. The resolver-level guard
	 * ({@link MekEnergisticsTargetResolverGuardMixin}) additionally blocks all
	 * other providers (ME/EME compat) at the single {@code resolve} choke point.
	 *
	 * @since 2.0.7
	 */
@Mixin(targets = "com.beipuo.mekenergistics.compat.provider.MekanismMachineProvider", remap = false)
public abstract class MekEnergisticsInstallerGuardMixin {

	private MekEnergisticsInstallerGuardMixin() {
	}

	/**
	 * Intercepts {@code resolveOriginalMachine(BlockState)}: our machines are
	 * never convertible, so return null to stop the installer.
	 */
	@Inject(method = "resolveOriginalMachine", at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$blockCentrifugeConversion(BlockState state,
			CallbackInfoReturnable<Object> cir) {
		if (state != null && MekEnergisticsBlockGuard.isProtectedMachine(state.getBlock())) {
			cir.setReturnValue(null);
		}
	}
}
