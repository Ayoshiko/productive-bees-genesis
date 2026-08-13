/**
	 * 宇宙渲染系统包
	 * <br/>
	 * 实现自定义 cosmic 着色器渲染管线：
	 * <ol>
	 *   <li>烘焙模型（{@code AbstractBakedModelCosmic}、{@code BakedModelCosmic}、{@code BakedModelHell}、{@code
	 * BakedModelHalo}）</li>
	 *   <li>渲染队列与 Iris 兼容（{@code CosmicRenderQueue}、{@code CosmicRenderCall}、{@code IrisCompat}）</li>
	 *   <li>着色器与渲染类型（{@code CosmicShaders}、{@code CosmicRenderTypes}）</li>
	 *   <li>几何加载器（{@code AbstractMaskGeometryLoader}、{@code GeometryLoaderCosmic}、{@code
	 * GeometryLoaderHell}、{@code GeometryLoaderHalo}）</li>
	 *   <li>视角模型接口与工具（{@code PerspectiveModel}、{@code PerspectiveModelState}、{@code RenderUtils}、{@code
	 * TransformUtils}）</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
