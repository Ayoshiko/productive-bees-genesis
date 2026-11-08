package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

/**
	 * 自定义物品 BakedModel 顶层接口
	 * <br/>
	 * 继承 BakedModel，提供 renderItem；显示变换沿用 NeoForge 的原生实现。
	 * getQuads 返回空列表，isCustomRenderer=true，用于接管 ItemRenderer 默认流程。
	 */
public interface PerspectiveModel extends BakedModel {

	void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers,
		int packedLight, int packedOverlay);

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
}
