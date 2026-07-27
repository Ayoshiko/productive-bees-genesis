package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.function.Function;

import javax.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * 包装普通 BakedModel 的抽象基类
 * <br/>
 * 实现 PerspectiveModel，从被包装模型提取 ItemTransforms 构造 PerspectiveModelState；
 * 提供 renderWrapped 委托原模型渲染。
 * <p>
 * 物品模型烘焙工具方法已统一到 {@link RenderUtils#bakeItem}，本类不再持有副本。
 */
public abstract class WrappedItemModel implements PerspectiveModel {

	protected final BakedModel wrapped;
	protected PerspectiveModelState parentState;
	protected boolean cosmic = false;

	// 渲染上下文：由 ItemOverrides.resolve 回调写入，renderBakedModel 读取。
	// 这是 Minecraft 物品渲染管线的标准模式：渲染线程在 renderItem 前同步调用 resolve，
	// 单线程顺序执行，不存在并发访问。若未来引入批量/并行渲染则需重构为参数传递。
	@Nullable
	protected LivingEntity entity;
	@Nullable
	protected ClientLevel world;

	protected final ItemOverrides overrideList = new ItemOverrides() {
		@Override
		public BakedModel resolve(@NotNull BakedModel originalModel, @NotNull ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity entity, int seed) {
			WrappedItemModel.this.entity = entity;
			// 使用 instanceof 检查避免 ClassCastException：entity.level() 在非客户端环境可能非 ClientLevel
			// 转换失败时 world 为 null，下游 resolve 接受 @Nullable ClientLevel，可安全降级
			WrappedItemModel.this.world = level != null ? level : (entity != null && entity.level() instanceof ClientLevel cl ? cl : null);
			if (WrappedItemModel.this.cosmic) {
				return WrappedItemModel.this.wrapped.getOverrides().resolve(originalModel, stack, level, entity, seed);
			}
			return originalModel;
		}
	};

	@SuppressWarnings("deprecation") // getTransforms() 是获取 ItemTransforms 的唯一途径，applyTransform 无法替代
	public WrappedItemModel(BakedModel wrapped) {
		this.wrapped = wrapped;
		this.parentState = TransformUtils.stateFromItemTransforms(wrapped.getTransforms());
	}

	@Override
	public PerspectiveModelState getModelState() {
		return this.parentState;
	}

	@SuppressWarnings("deprecation") // BakedModel 接口要求实现 deprecated 的无参版本
	@Override
	@NotNull
	public TextureAtlasSprite getParticleIcon() {
		return this.wrapped.getParticleIcon();
	}

	@Override
	@NotNull
	public TextureAtlasSprite getParticleIcon(@NotNull ModelData data) {
		return this.wrapped.getParticleIcon(data);
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
		renderBakedModel(this.wrapped, stack, poseStack, buffers, packedLight, packedOverlay, fabulous, consumerOverride);
	}

	/**
	 * 渲染任意 BakedModel 的 quads — 公共渲染核心，消除子类重复逻辑
	 * <br/>
	 * renderWrapped 委托此方法渲染 this.wrapped，子类（如 BakedModelMyriadCombBlock）
	 * 可传入不同的 BakedModel 切换渲染目标。
	 *
	 * @param model            要渲染的 BakedModel（会先经 overrides 解析）
	 * @param stack            物品栈
	 * @param poseStack        PoseStack
	 * @param buffers          缓冲源
	 * @param packedLight      光照值
	 * @param packedOverlay    overlay 值
	 * @param fabulous         是否启用 fabulous 图形模式
	 * @param consumerOverride VertexConsumer 转换函数
	 */
	protected void renderBakedModel(BakedModel model, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay, boolean fabulous, Function<VertexConsumer, VertexConsumer> consumerOverride) {
		BakedModel resolved = model.getOverrides().resolve(model, stack, this.world, this.entity, 0);
		ItemRenderer itemRenderer = Minecraft.getInstance().getItemRenderer();
		for (BakedModel bakedModel : resolved.getRenderPasses(stack, fabulous)) {
			for (RenderType renderType : bakedModel.getRenderTypes(stack, fabulous)) {
				itemRenderer.renderModelLists(bakedModel, stack, packedLight, packedOverlay, poseStack, consumerOverride.apply(buffers.getBuffer(renderType)));
			}
		}
	}

	/** renderBakedModel 的便捷重载，使用 identity 作为 consumerOverride */
	protected void renderBakedModel(BakedModel model, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay, boolean fabulous) {
		renderBakedModel(model, stack, poseStack, buffers, packedLight, packedOverlay, fabulous, Function.identity());
	}
}
