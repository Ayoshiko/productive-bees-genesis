/**
 * 配置包 — 模组配置定义与聚合
 * <br/>
 * 负责：
 * <ol>
 *   <li>客户端/通用/服务端配置定义（{@link com.ayoshiko.productivebeesgenesis.config.ModConfig}）</li>
 *   <li>配置段抽取与聚合（{@link com.ayoshiko.productivebeesgenesis.config.ConfigSectionRegistry}、
 *       {@link com.ayoshiko.productivebeesgenesis.config.ServerConfig}）</li>
 *   <li>独立配置段（蜜蜂属性、离心机、蜂箱）</li>
 *   <li>配置校验逻辑（validator 与跨字段联合校验）</li>
 * </ol>
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.config;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
