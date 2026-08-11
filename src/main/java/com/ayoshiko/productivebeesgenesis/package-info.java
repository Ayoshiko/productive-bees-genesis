/**
	 * Productive Bees Genesis 附属模组根包
	 * <br/>
	 * 负责：
	 * <ol>
	 *   <li>模组入口与 {@code @Mod} 注解类 {@link ProductiveBeesGenesis}</li>
	 *   <li>客户端入口 {@link ProductiveBeesGenesisClient}</li>
	 *   <li>万象创世（Myriad Creations）子系统事件处理</li>
	 *   <li>梳块检查缓存、随机蜂蜜梳选择器等公共工具</li>
	 *   <li>跨模块事件处理（如升级收集、创造模式标签事件）</li>
	 * </ol>
	 * <br/>
	 * 子包职责：
	 * <ul>
	 *   <li>{@code apiary/} - MEK 蜂箱方块实体、GUI、槽位管理、序列化</li>
	 *   <li>{@code capability/} - 自定义 Capability 包装（如 RateLimitedItemHandler）</li>
	 *   <li>{@code client/} - 客户端渲染、JEI、Jade、Screen</li>
	 *   <li>{@code command/} - 命令注册（当前为空，性能监控命令已移除）</li>
	 *   <li>{@code compat/} - 第三方模组兼容（KubeJS、ME、EME）</li>
	 *   <li>{@code config/} - 客户端/服务端/通用配置</li>
	 *   <li>{@code datagen/} - 数据生成（语言、配方、战利品表）</li>
	 *   <li>{@code init/} - 注册器（方块、物品、方块实体、菜单等）</li>
	 *   <li>{@code inventory/} - 自定义槽位与库存管理</li>
	 *   <li>{@code item/} - 自定义物品（如 InfinityCreationComb）</li>
	 *   <li>{@code mek/} - Mekanism 离心机与 AE2 集成</li>
	 *   <li>{@code mixin/} - Mixin 注入类</li>
	 *   <li>{@code network/} - 网络包</li>
	 * </ul>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
