/**
	 * 兼容性集成模块父包
	 * <br/>
	 * 作为第三方模组兼容性集成的命名空间父级，本身不直接存放类文件。
	 * 当前包含以下子包：
	 * <ul>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.emextras} — Extra_MODS Extras 兼容</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.kubejs} — KubeJS 脚本事件集成</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras} — Mekanism Extras 兼容</li>
	 * </ul>
	 * 各子包通过运行时 {@code ModList.isLoaded(...)} 检查实现条件加载，
	 * 避免硬依赖导致未安装可选前置时触发 {@code NoClassDefFoundError}。
	 */
@ParametersAreNonnullByDefault
package com.ayoshiko.productivebeesgenesis.compat;

import javax.annotation.ParametersAreNonnullByDefault;
