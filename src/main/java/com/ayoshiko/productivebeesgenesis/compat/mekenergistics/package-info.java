/**
 * Mek-Energistics 兼容包（普通代码，非 Mixin 包）
 * <br/>
 * 提供工厂安装器守卫等运行时工具类。注意：守卫类必须放在本包而非
 * {@code mixin.*} 包，否则被 Mixin 注入到第三方类后会触发
 * {@code IllegalClassLoadError}（issue #6）。
 */
package com.ayoshiko.productivebeesgenesis.compat.mekenergistics;
