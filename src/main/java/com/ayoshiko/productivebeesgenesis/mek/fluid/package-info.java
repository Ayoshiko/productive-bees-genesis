/**
 * 多方流体侧向配置模块
 * <br/>
 * 提供机械蜂箱/离心机的多流体槽位管理与侧向配置（输入/输出方向控制）。
 * 包含流体槽位持有者（{@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankHolder}）、
 * NBT 编解码器（{@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidTankNbtCodec}）
 * 和侧向配置路由处理器（{@link com.ayoshiko.productivebeesgenesis.mek.fluid.MultiFluidSideConfigHandler}）。
 * <p>
 * 设计原则：槽位路由、序列化、侧面配置三职责分离（SRP），各司其职。
 */
@ParametersAreNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mek.fluid;

import javax.annotation.ParametersAreNonnullByDefault;
