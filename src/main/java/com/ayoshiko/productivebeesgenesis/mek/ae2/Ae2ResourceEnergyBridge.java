package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEKey;
import com.glodblock.github.appflux.common.me.key.FluxKey;
import com.glodblock.github.appflux.common.me.key.type.EnergyType;

/** 只由 Applied Flux 存在守卫后的入口加载；不允许把 GTEU 转成 FE。 */
final class Ae2ResourceEnergyBridge {
	private Ae2ResourceEnergyBridge() {}

	static AEKey fe() { return FluxKey.of(EnergyType.FE); }
}
