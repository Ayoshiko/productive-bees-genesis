/**
 * 通用槽位组件包
 * <br/>
 * 存放跨模块复用的自定义 InventorySlot 实现，如分等级堆叠倍率输出槽。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP：每个槽位类仅负责单一职责（如堆叠上限计算）</li>
 *   <li>OCP：通过 IntSupplier 等函数式接口扩展行为，不修改既有代码</li>
 *   <li>DIP：倍率来源、监听器等依赖通过构造参数注入</li>
 * </ul>
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
@org.jetbrains.annotations.ApiStatus.Internal
package com.ayoshiko.productivebeesgenesis.inventory;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
