/**
	 * 能力接口包
	 * <br/>
	 * 定义自定义 Capability 接口和包装器：
	 * <ol>
	 *   <li>{@code IInventoryDirtyDebouncer} — 物品栏脏标记去抖接口</li>
	 *   <li>{@code RateLimitedItemHandler} — 按 tick 限流的物品处理器包装器</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.capability;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
