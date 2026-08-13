/**
	 * EvolvedMekanismExtras (EME) 兼容注册隔离包
	 * <br/>
	 * 将 EME（EvolvedMekanismExtras）相关的方块/物品/方块实体注册逻辑从主注册类
	 * ({@link com.ayoshiko.productivebeesgenesis.init.ModBlocks}/{@link com.ayoshiko.productivebeesgenesis.init.ModItems}/
	 * {@link com.ayoshiko.productivebeesgenesis.init.ModBlockEntities})中隔离出来，
	 * 使主注册类不再直接 import EME 的类（EMExtraFactoryTier、EMExtraMachine 等）。
	 * <p>
	 * 核心类：
	 * <ul>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMECompatLoader} — 统一入口，供主类反射调用</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEBlockRegistration} — EME 工厂方块注册</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEItemRegistration} — EME 工厂 BlockItem 注册</li>
	 *   <li>{@link com.ayoshiko.productivebeesgenesis.compat.emextras.EMEBlockEntityRegistration} — EME 工厂
	 * BlockEntityType 注册</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本包下的类直接 import EME 的类，但仅在 EvolvedMekanismExtras 已加载时
	 * 由 {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isEvolvedMekanismExtrasLoaded()}
	 * 检查通过后才被调用，未安装 EME 时不会触发类加载，模组正常运行。
	 */
package com.ayoshiko.productivebeesgenesis.compat.emextras;
