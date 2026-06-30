/**
 * Mixin 访问器包
 * <br/>
 * 通过 @Accessor 和 @Invoker Mixin 暴目标类的包私有字段和方法，
 * 供外部包安全访问。所有访问器方法使用 {@code productivebeesgenesis$} 前缀。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
