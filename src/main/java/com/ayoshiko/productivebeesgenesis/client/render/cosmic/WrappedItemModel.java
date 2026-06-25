package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.block.model.FaceBakery;
import net.minecraft.client.renderer.block.model.ItemModelGenerator;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 包装普通 BakedModel 的抽象基类
 * <br/>
 * 实现 PerspectiveModel，从被包装模型提取 ItemTransforms 构造 PerspectiveModelState；
 * 提供 bakeItem 工具方法与 renderWrapped 委托原模型渲染。
 */
public abstract class WrappedItemModel implements PerspectiveModel {

	public static final ItemModelGenerator ITEM_MODEL_GENERATOR = new ItemModelGenerator();
	public static final FaceBakery FACE_BAKERY = new FaceBakery();

	protected final BakedModel wrapped;
	protected PerspectiveModelState parentState;
	protected boolean cosmic = false;

	@Nullable
	protected LivingEntity entity;
	@Nullable
	protected ClientLevel world;

	protected final ItemOverrides overrideList = new ItemOverrides() {
		@Override
		public BakedModel resolve(@NotNull BakedModel originalModel, @NotNull ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
			WrappedItemModel.this.entity = entity;
			WrappedItemModel.this.world = level != null ? level : (entity != null ? (ClientLevel) entity.level() : null);
			if (WrappedItemModel.this.cosmic) {
				return WrappedItemModel.this.wrapped.getOverrides().resolve(originalModel, stack, level, entity, seed);
			}
			return originalModel;
		}
	};

	public WrappedItemModel(BakedModel wrapped) {
		this.wrapped = wrapped;
		this.parentState = TransformUtils.stateFromItemTransforms(wrapped.getTransforms());
	}

	public static List<BakedQuad> bakeItem(List<TextureAtlasSprite> sprites) {
		List<BakedQuad> quads = new LinkedList<>();
		for (int i = 0; i < sprites.size(); i++) {
			TextureAtlasSprite sprite = sprites.get(i);
			List<BlockElement> unbaked = ITEM_MODEL_GENERATOR.processFrames(i, "layer" + i, sprite.contents());
			for (BlockElement element : unbaked) {
				for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
					quads.add(FACE_BAKERY.bakeQuad(element.from, element.to, entry.getValue(), sprite, entry.getKey(), new PerspectiveModelState(ImmutableMap.of()), element.rotation, element.shade));
				}
			}
		}
		return quads;
	}

	@Override
	public PerspectiveModelState getModelState() {
		return this.parentState;
	}

	@Override
	@NotNull
	public TextureAtlasSprite getParticleIcon() {
		return this.wrapped.getParticleIcon();
	}

	@Override
	@NotNull
	public ItemOverrides getOverrides() {
		return this.overrideList;
	}

	@Override
	public boolean useAmbientOcclusion() {
		return this.wrapped.useAmbientOcclusion();
	}

	@Override
	public boolean isGui3d() {
		return this.wrapped.isGui3d();
	}

	@Override
	public boolean usesBlockLight() {
		return this.wrapped.usesBlockLight();
	}

	protected void renderWrapped(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay, boolean fabulous) {
		renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, fabulous, Function.identity());
	}

	protected void renderWrapped(ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay, boolean fabulous, Function<VertexConsumer, VertexConsumer> consumerOverride) {
		BakedModel model = this.wrapped.getOverrides().resolve(this.wrapped, stack, this.world, this.entity, 0);
		ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
		for (BakedModel bakedModel : model.getRenderPasses(stack, fabulous)) {
			for (RenderType renderType : bakedModel.getRenderTypes(stack, fabulous)) {
				itemRenderer.renderModelLists(bakedModel, stack, packedLight, packedOverlay, poseStack, consumerOverride.apply(buffers.getBuffer(renderType)));
			}
		}
	}
}
