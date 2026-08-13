/**
	 * Mekanism Extras (ME) 兼容注册隔离包
	 * <br/>
	 * 将 ME（MekanismExtras）相关的方块/物品/方块实体注册逻辑从主注册类
	 * ({@link com.ayoshiko.productivebeesgenesis.init.ModBlocks}/{@link com.ayoshiko.productivebeesgenesis.init.ModItems}/
	 * {@link com.ayoshiko.productivebeesgenesis.init.ModBlockEntities})中隔离出来，
	 * 使主注册类不再直接 import ME 的类（ExtraFactoryTier、ExtraMachine 等）。
	 * <p>
	 * 核心类：
	 * <ul>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MECompatLoader} — 统一入口，供主类反射调用</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEBlockRegistration} — ME 工厂方块注册</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEItemRegistration} — ME 工厂 BlockItem 注册</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEBlockEntityRegistration} —
	 * ME 工厂 BlockEntityType 注册</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本包下的类直接 import ME 的类，但仅在 MekanismExtras 已加载时
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isMekanismExtrasLoaded()}
	 * 检查通过后才被调用，未安装 ME 时不会触发类加载，模组正常运行。
	 */
package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;
