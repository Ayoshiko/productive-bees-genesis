package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** 仅客户端加载；服务端注册入口不引用任何渲染类型。 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class MachineRenderEvents {
	/** 在客户端模型注册阶段绑定控制器渲染器。 */
	@SubscribeEvent
	public static void register(EntityRenderersEvent.RegisterRenderers event) {
		event.registerBlockEntityRenderer(MachineContent.CONTROLLER_TILE.get(), CombinedApiaryRenderer::new);
	}
	/** 此事件在每次资源重载的模型应用阶段触发，运行于客户端线程。 */
	@SubscribeEvent
	public static void modelsReloaded(EntityRenderersEvent.AddLayers event) {
		MachineActivityClient.resourcesReloaded();
	}
	private MachineRenderEvents() { }
}
