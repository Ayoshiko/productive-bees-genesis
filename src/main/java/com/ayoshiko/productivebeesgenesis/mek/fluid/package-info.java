/**
	 * 多方流体侧向配置模块
	 * <br/>
	 * 提供机械蜂箱/离心机的多流体槽位管理。
	 * 包含流体槽位持有者（{@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder}）
	 * 与 NBT 编解码器（{@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankNbtCodec}）。
	 * <p>
	 * 设计原则：槽位路由与序列化职责分离（SRP）；对外的弹出/轮转视图由 {@code logistics/} 包负责。
	 */
@ParametersAreNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mek.fluid;

import javax.annotation.ParametersAreNonnullByDefault;
