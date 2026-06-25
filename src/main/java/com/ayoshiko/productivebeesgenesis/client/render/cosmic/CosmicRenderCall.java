package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix3f;
import org.joml.Matrix3fc;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Iris 延迟渲染调用快照
 * <br/>
 * 保存一次 cosmic 光晕渲染所需的模型、物品栈、展示上下文、光照/覆盖层以及 pose/normal/projection/modelView 矩阵。
 */
public class CosmicRenderCall {

	public final CosmicRenderable model;
	public final ItemStack stack;
	public final ItemDisplayContext context;
	public final int light;
	public final int overlay;
	public final Matrix4f pose;
	public final Matrix3f normal;
	public final Matrix4f projection;
	public final Matrix4f modelView;

	public CosmicRenderCall(CosmicRenderable model, ItemStack stack, ItemDisplayContext context, PoseStack poseStack, int light, int overlay, Matrix4f projection, Matrix4f modelView) {
		this.model = model;
		this.stack = stack;
		this.context = context;
		this.light = light;
		this.overlay = overlay;
		PoseStack.Pose pose = poseStack.last();
		this.pose = new Matrix4f((Matrix4fc) pose.pose());
		this.normal = new Matrix3f((Matrix3fc) pose.normal());
		this.projection = new Matrix4f((Matrix4fc) projection);
		this.modelView = new Matrix4f((Matrix4fc) modelView);
	}
}
