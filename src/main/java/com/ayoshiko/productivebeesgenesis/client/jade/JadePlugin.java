package com.ayoshiko.productivebeesgenesis.client.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

/**
 * Jade 插件注册入口
 * <br/>
 * 使用 {@link WailaPlugin} 注解，Jade 启动时自动扫描并加载此类。
 * <p>
 * <b>注册项</b>：
 * <ul>
 *   <li>AE2 网络状态显示组件 — 基础离心机 + 原版工厂离心机 + {@link MekCentrifugeBlock}</li>
 *   <li>蜂箱运行状态显示组件 — {@link TileEntityMekApiary} + {@link MekApiaryBlock}
 *       （显示能量/蜜蜂/状态/进度）</li>
 * </ul>
 * <p>
 * <b>类加载安全</b>：此类仅被 Jade 加载（Jade 未安装时不加载），
 * 不引用任何 ME/EME 可选依赖类。ME/EME 工厂版的注册委托给隔离类
 * （{@link JadePluginMEDelegate}/{@link JadePluginEMEDelegate}），
 * 仅在对应模组加载时通过 {@link MekCompatHooks} 守卫调用，
 * 利用 JVM 延迟类加载保证未安装 ME/EME 时不会触发 {@code NoClassDefFoundError}。
 */
@WailaPlugin
public final class JadePlugin implements IWailaPlugin {

	@Override
	public void register(IWailaCommonRegistration registration) {
		JadeAe2StatusProvider ae2Provider = JadeAe2StatusProvider.INSTANCE;
		// 基础离心机 + 原版工厂离心机 — AE2 状态同步
		registration.registerBlockDataProvider(ae2Provider, TileEntityMekCentrifuge.class);
		registration.registerBlockDataProvider(ae2Provider, TileEntityMekCentrifugeFactory.class);

		// 基础蜂箱 + 原版工厂蜂箱 — AE2 状态 + 运行状态同步
		// Jade 不支持子类自动识别，需显式注册工厂版蜂箱子类
		registration.registerBlockDataProvider(ae2Provider, TileEntityMekApiary.class);
		registration.registerBlockDataProvider(ae2Provider, TileEntityMekApiaryFactory.class);
		registration.registerBlockDataProvider(JadeApiaryComponentProvider.INSTANCE, TileEntityMekApiary.class);
		registration.registerBlockDataProvider(JadeApiaryComponentProvider.INSTANCE, TileEntityMekApiaryFactory.class);

		// ME 工厂版 — 仅在 mekanism_extras 加载时注册（隔离类避免 NoClassDefFoundError）
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			JadePluginMEDelegate.registerCommon(registration);
		}
		// EME 工厂版 — 仅在 emextras 加载时注册（隔离类避免 NoClassDefFoundError）
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			JadePluginEMEDelegate.registerCommon(registration);
		}
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		// 离心机 AE2 网络状态显示 — 所有继承 MekCentrifugeBlock 的方块
		registration.registerBlockComponent(JadeAe2StatusProvider.INSTANCE, MekCentrifugeBlock.class);

		// 蜂箱 AE2 网络状态显示 + 运行状态显示 — 所有继承 MekApiaryBlock 的方块（含工厂版蜂箱）
		registration.registerBlockComponent(JadeAe2StatusProvider.INSTANCE, MekApiaryBlock.class);
		registration.registerBlockComponent(JadeApiaryComponentProvider.INSTANCE, MekApiaryBlock.class);
	}
}
