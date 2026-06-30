/**
 * 配方序列化 Mixin 包
 * <br/>
 * 为 PB 的配方序列化器注入 fallback 逻辑，
 * 防止 {@code BeeIngredientFactory} 未就绪时 NPE。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mixin.recipe;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
