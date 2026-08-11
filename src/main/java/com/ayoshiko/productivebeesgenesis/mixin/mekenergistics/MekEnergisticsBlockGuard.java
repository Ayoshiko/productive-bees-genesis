package com.ayoshiko.productivebeesgenesis.mixin.mekenergistics;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
	 * Shared guard predicate used by the Mek-Energistics installer mixins.
	 * <p>
	 * Any block registered by ProductiveBeesGenesis whose path belongs to the
	 * centrifuge or apiary machine family (base machine, vanilla tiers, ME/EME
	 * compat tiers) must never be converted into an ME machine by the
	 * mekenergistics factory installer.
	 */
public final class MekEnergisticsBlockGuard {

	private MekEnergisticsBlockGuard() {
	}

	/**
	 * @return true when the block is one of our centrifuge/apiary machines that
	 *         must never be handed to the ME factory installer.
	 */
	public static boolean isProtectedMachine(Block block) {
		if (block == null) return false;
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		if (id == null || !ProductiveBeesGenesis.MOD_ID.equals(id.getNamespace())) {
			return false;
		}
		String path = id.getPath();
		return path.contains("mek_centrifuge") || path.contains("mek_apiary");
	}
}
