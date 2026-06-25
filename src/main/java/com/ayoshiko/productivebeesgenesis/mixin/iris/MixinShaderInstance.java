package com.ayoshiko.productivebeesgenesis.mixin.iris;

import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.VertexFormat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.irisshaders.iris.compat.SkipList;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * ShaderInstance 的 Iris 光影兼容 Mixin
 * <br/>
 * 原理：注入 ShaderInstance 构造方法尾部，当着色器资源属于本模组命名空间
 * （productivebeesgenesis）时，调用 setShouldSkip(SkipList.NONE)
 * 强制 Iris 不跳过该着色器的渲染处理。确保 cosmic 着色器在光影环境下
 * 不会被 Iris 的着色器跳过列表过滤掉。
 * <br/>
 * 注意：Iris 1.8.8 的 SkipList 仅提供 NONE 和 ALWAYS 两个常量，
 * Re:Avaritia 源码使用的 NONE_FORCE 在此版本中不存在，使用 NONE 替代。
 */
@Mixin(ShaderInstance.class)
public abstract class MixinShaderInstance implements ShaderInstanceInterface {

	/**
	 * 在 ShaderInstance 构造完成后检查命名空间
	 * <br/>
	 * order = 1001 确保在其他注入之后执行，此时 ShaderInstanceInterface
	 * 的 setShouldSkip 方法已可用。
	 *
	 * @param resourceProvider	资源提供者
	 * @param shaderLocation	着色器资源位置
	 * @param vertexFormat		顶点格式
	 * @param callbackInfo		回调信息
	 */
	@Inject(
		method = { "<init>(Lnet/minecraft/server/packs/resources/ResourceProvider;Lnet/minecraft/resources/ResourceLocation;Lcom/mojang/blaze3d/vertex/VertexFormat;)V" },
		at = { @At("TAIL") },
		order = 1001
	)
	private void productivebeesgenesis$onShaderInit(ResourceProvider resourceProvider, ResourceLocation shaderLocation, VertexFormat vertexFormat, CallbackInfo callbackInfo) {
		// 防御性null检查：部分模组可能传入null shaderLocation
		if (shaderLocation != null && "productivebeesgenesis".equals(shaderLocation.getNamespace())) {
			this.setShouldSkip(SkipList.NONE);
		}
	}
}
