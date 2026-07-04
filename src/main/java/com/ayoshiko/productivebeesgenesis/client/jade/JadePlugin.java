package com.ayoshiko.productivebeesgenesis.client.jade;

import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifugeFactory;

/**
 * Jade 插件注册入口
 * <br/>
 * 使用 {@link WailaPlugin} 注解，Jade 启动时自动扫描并加载此类。
 * 注册 AE2 网络状态显示组件到所有离心机方块实体类型。
 * <p>
 * <b>注册项</b>：
 * <ul>
 *   <li>服务端数据提供器 — 4 种离心机方块实体类</li>
 *   <li>客户端显示组件 — {@link MekCentrifugeBlock}（覆盖所有离心机方块）</li>
 * </ul>
 * <p>
 * <b>类加载安全</b>：此类仅被 Jade 加载（Jade 未安装时不加载），
 * 不从任何非可选代码路径引用。
 */
@WailaPlugin
public final class JadePlugin implements IWailaPlugin {

	@Override
	public void register(IWailaCommonRegistration registration) {
		// 注册服务端数据提供器 — 为每种离心机方块实体同步 AE2 状态
		JadeAe2StatusProvider provider = JadeAe2StatusProvider.INSTANCE;
		registration.registerBlockDataProvider(provider, TileEntityMekCentrifuge.class);
		registration.registerBlockDataProvider(provider, TileEntityMekCentrifugeFactory.class);
		registration.registerBlockDataProvider(provider, TileEntityExtraMekCentrifugeFactory.class);
		registration.registerBlockDataProvider(provider, TileEntityEMExtraMekCentrifugeFactory.class);
	}

	@Override
	public void registerClient(IWailaClientRegistration registration) {
		// 注册客户端显示组件 — 所有继承 MekCentrifugeBlock 的方块
		registration.registerBlockComponent(JadeAe2StatusProvider.INSTANCE, MekCentrifugeBlock.class);
	}
}
