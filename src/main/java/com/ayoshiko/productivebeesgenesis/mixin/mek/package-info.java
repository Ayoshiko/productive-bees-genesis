/**
 * Mekanism Mixin 包
 * <br/>
 * 注入 Mekanism 和 PB 离心机的 Mixin：
 * <ol>
 *   <li>离心机 Mixin — 拦截 canOperate/canProcessRecipe/completeRecipeProcessing</li>
 *   <li>工厂 Mixin — ME/EME 工厂升级链扩展</li>
 *   <li>弹出器 Mixin — 输出阻塞冷却与限流</li>
 * </ol>
 * 受 {@code MixinConfigPlugin} 控制，ME/EME 相关 Mixin 仅在对应模组加载时应用。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mixin.mek;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
