package com.ayoshiko.productivebeesgenesis.resource;

/** PB 离心资源接入的加载后能力门，普通与电力 tick 均须实际完成注入。 */
public interface PbResourceCentrifugeBridge {
	double productivebeesgenesis$resourceTimeModifier();
	default double productivebeesgenesis$resourceEnergyModifier() { return 1; }
	default boolean productivebeesgenesis$resourcePowerReady() { return false; }
}
