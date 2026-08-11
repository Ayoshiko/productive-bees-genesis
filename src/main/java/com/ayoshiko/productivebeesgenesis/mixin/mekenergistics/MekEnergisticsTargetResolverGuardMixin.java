package com.ayoshiko.productivebeesgenesis.mixin.mekenergistics;

import com.ayoshiko.productivebeesgenesis.compat.mekenergistics.MekEnergisticsBlockGuard;

import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * Resolver-level guard for the ME factory installer.
	 * <p>
	 * Mek-Energistics 3.x funnels every installer conversion through
	 * {@code MeInstallerTargetResolver.resolve(BlockState)} (both
	 * {@code MeTierInstallerItem.tryInstall} and the upgrade handler). The
	 * resolver iterates every loaded machine provider ({@code Mekanism},
	 * {@code mekanism_extras} ME, {@code emextras} EME, mekmm), and several of
	 * them match by factory attributes, so guarding only the Mekanism provider
	 * is not enough.
	 * <p>
	 * This mixin returns null at the single choke point for every block of our
	 * machine family, which makes the conversion impossible regardless of which
	 * provider would have matched.
	 *
	 * @since 2.0.9
	 */
@Mixin(targets = "com.beipuo.mekenergistics.item.MeInstallerTargetResolver", remap = false)
public abstract class MekEnergisticsTargetResolverGuardMixin {

	private MekEnergisticsTargetResolverGuardMixin() {
	}

	/**
	 * Intercepts the static {@code resolve(BlockState)} entry: our machines are
	 * never convertible, so return null to stop every provider path.
	 */
	@Inject(method = "resolve", at = @At("HEAD"), cancellable = true, remap = false)
	private static void productivebeesgenesis$blockInstallerConversion(BlockState state,
			CallbackInfoReturnable<Object> cir) {
		if (state != null && MekEnergisticsBlockGuard.isProtectedMachine(state.getBlock())) {
			cir.setReturnValue(null);
		}
	}
}
