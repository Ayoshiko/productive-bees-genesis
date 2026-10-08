package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.stacks.AEKey;
import com.ayoshiko.productivebeesgenesis.mek.ae2.AppliedFluxIntegrationLoader;

/** Applied Flux 的 FE 键延迟加载；GTEU 与 AE2 网络运行能量不属于本交接。 */
final class AeMeEnergy {
	static AEKey key() { return AppliedFluxIntegrationLoader.isAppliedFluxLoaded() ? Loaded.key() : null; }
	static boolean isFe(AEKey key) { return AppliedFluxIntegrationLoader.isAppliedFluxLoaded() && Loaded.isFe(key); }
	private static final class Loaded {
		static AEKey key() { return com.glodblock.github.appflux.common.me.key.FluxKey.of(com.glodblock.github.appflux.common.me.key.type.EnergyType.FE); }
		static boolean isFe(AEKey key) {
			return key instanceof com.glodblock.github.appflux.common.me.key.FluxKey flux
					&& flux.getEnergyType() == com.glodblock.github.appflux.common.me.key.type.EnergyType.FE;
		}
	}
	private AeMeEnergy() { }
}
