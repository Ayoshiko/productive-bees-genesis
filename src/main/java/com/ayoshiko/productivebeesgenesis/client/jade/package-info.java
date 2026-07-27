/**
 * Jade 集成模块
 * <br/>
 * 提供机械蜂箱与离心机方块实体的 Jade tooltip 显示组件，包括 AE2 网络状态、
 * 蜂箱运行状态（蜜蜂数量/状态/进度）等信息。
 * <br/>
 * 仅客户端加载，通过 {@link snownee.jade.api.WailaPlugin} 注解自动注册；
 * ME/EME 工厂版注册委托给隔离类（{@link com.ayoshiko.productivebeesgenesis.client.jade.JadePluginMEDelegate}
 * /{@link com.ayoshiko.productivebeesgenesis.client.jade.JadePluginEMEDelegate}），
 * 利用 JVM 延迟类加载避免未安装可选依赖时触发 {@code NoClassDefFoundError}。
 */
@ParametersAreNonnullByDefault
package com.ayoshiko.productivebeesgenesis.client.jade;

import javax.annotation.ParametersAreNonnullByDefault;
