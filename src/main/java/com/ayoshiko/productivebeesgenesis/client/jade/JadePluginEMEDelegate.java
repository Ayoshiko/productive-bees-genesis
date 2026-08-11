package com.ayoshiko.productivebeesgenesis.client.jade;

import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory;
import snownee.jade.api.IWailaCommonRegistration;

/**
	 * Jade 插件 EME 隔离注册器
	 * <br/>
	 * 集中引用 EME（emextras）可选依赖的 TileEntity 类，
	 * 仅在 {@link com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks#isEvolvedMekanismExtrasLoaded()}
	 * 守卫通过后由 {@link JadePlugin} 调用，利用 JVM 延迟类加载保证未安装 EME 时不会触发
	 * {@code NoClassDefFoundError}。
	 * <p>
	 * 设计原则：单一职责（仅注册 EME 工厂版方块实体的 Jade 数据提供者）。
	 *
	 * @since 2.0.0
	 */
final class JadePluginEMEDelegate {

	private JadePluginEMEDelegate() {
	}

	/**
	 * 注册 EME 工厂版蜂箱和离心机的 Jade BlockDataProvider
	 *
	 * @param registration Jade 公共注册器
	 */
	static void registerCommon(IWailaCommonRegistration registration) {
		JadeAe2StatusProvider ae2Provider = JadeAe2StatusProvider.INSTANCE;
		// EME 工厂版离心机 — AE2 状态同步
		registration.registerBlockDataProvider(ae2Provider, TileEntityEMExtraMekCentrifugeFactory.class);
		// EME 工厂版蜂箱 — AE2 状态 + 运行状态同步
		registration.registerBlockDataProvider(ae2Provider, TileEntityEMExtraMekApiaryFactory.class);
		registration.registerBlockDataProvider(JadeApiaryComponentProvider.INSTANCE, TileEntityEMExtraMekApiaryFactory.class);
	}
}
