/**
	 * KubeJS 兼容层
	 * <br/>
	 * 提供 KubeJS 脚本集成，允许整合包作者通过脚本动态添加 ProductiveBees 蜜蜂配方。
	 * <p>
	 * 核心类：
	 * <ul>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.kubejs.ProductiveBeesGenesisKubeJSPlugin} — KubeJS 插件主类</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.kubejs.MyriadBeeEvents} — 事件组定义</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.kubejs.MyriadBeeRegisterEventJS} — 蜜蜂配方注册事件</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.kubejs.MyriadBeeJsonSerializer} — 配方 JSON 序列化工具</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本包下的类仅在 KubeJS 已安装时被加载（通过 kubejs.plugins.txt），
	 * 未安装 KubeJS 时不会触发类加载，模组正常运行。
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.compat.kubejs;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
