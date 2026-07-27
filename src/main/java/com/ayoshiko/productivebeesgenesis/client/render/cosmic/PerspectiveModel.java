package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.mojang.math.Transformation;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 自定义物品 BakedModel 顶层接口
 * <br/>
 * 继承 BakedModel，提供 renderItem 与 applyTransform 默认实现；
 * getQuads 返回空列表，isCustomRenderer=true，用于接管 ItemRenderer 默认流程。
 */
public interface PerspectiveModel extends BakedModel {

	@Nullable
	PerspectiveModelState getModelState();

	void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay);

	@SuppressWarnings("deprecation") // BakedModel 接口要求实现 deprecated 的 3 参数版本
	@NotNull
	@Override
	default List<BakedQuad> getQuads(@Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand) {
		return Collections.emptyList();
	}

	@Override
	default boolean isCustomRenderer() {
		return true;
	}

	@NotNull
	@Override
	default ItemOverrides getOverrides() {
		return ItemOverrides.EMPTY;
	}

	@NotNull
	@Override
	default BakedModel applyTransform(@NotNull ItemDisplayContext context, @NotNull PoseStack poseStack, boolean leftFlip) {
		PerspectiveModelState modelState = getModelState();
		if (modelState != null) {
			Transformation transform = modelState.getTransform(context);
			// 性能说明：applyTransform 为接口默认方法，无法持有实例字段预分配 Vector3f。
			// Transformation.getTranslation()/getScale() 由 Mojang API 定义为每次返回新对象，外部无法复用。
			// 调用频率较低（仅物品显示变换），GC 影响可忽略，保持原实现。
			Vector3f trans = transform.getTranslation();
			Vector3f scale = transform.getScale();
			poseStack.translate(trans.x(), trans.y(), trans.z());
			poseStack.mulPose(transform.getLeftRotation());
			poseStack.scale(scale.x(), scale.y(), scale.z());
			poseStack.mulPose(transform.getRightRotation());
			if (leftFlip) {
				poseStack.mulPose(Axis.YN.rotationDegrees(180.0F));
			}
			return this;
		}
		return BakedModel.super.applyTransform(context, poseStack, leftFlip);
	}
}
