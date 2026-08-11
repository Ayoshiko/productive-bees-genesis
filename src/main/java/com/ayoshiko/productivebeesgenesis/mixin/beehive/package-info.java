/**
	 * 蜂箱 Mixin 包
	 * <br/>
	 * 优化 Productive Bees 蜂箱性能的 Mixin：
	 * <ol>
	 *   <li>{@code BeeDataHasNectarCacheMixin} — 缓存 hasNectar() 结果</li>
	 *   <li>{@code AdvancedBeehiveBlockEntityAbstractSimCacheMixin} — 缓存 isSim() 结果</li>
	 *   <li>{@code AdvancedBeehiveBlockEntityAbstractSimulateThrottleMixin} — 蜂箱模拟节流</li>
	 *   <li>{@code AdvancedBeehiveInventoryDebounceMixin} — 物品栏变更去抖</li>
	 *   <li>{@code BlockEntityItemStackHandlerDebounceMixin} — 物品处理器去抖</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
