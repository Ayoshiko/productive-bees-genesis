package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/**
 * Cosmic 烘焙模型抽象基类
 * <br/>
 * 提取 {@link BakedModelCosmic} 与 {@link BakedModelHell} 的公共渲染逻辑：
 * 基础物品渲染、Iris 延迟入队、mask 光晕层渲染框架。
 * <br/>
 * 子类只需通过三个抽象方法提供差异化的着色器 uniform、RenderType 与批次收尾逻辑，
 * 遵循 DRY（消除重复）与 OCP（对扩展开放）原则。
 */
public abstract class AbstractBakedModelCosmic extends WrappedItemModel implements CosmicRenderable {

	/** mask 纹理资源位置列表，子类共享 */
	protected final List<ResourceLocation> maskSprites;

	/**
	 * 全局缓存代数 — 图集重建时自增，使所有实例的 atlasSprites 缓存失效。
	 * <br/>
	 * 使用代数计数器而非遍历实例列表，避免维护实例注册表的复杂度与内存泄漏风险。
	 */
	private static volatile long globalCacheGeneration = 0L;

	/** 本实例缓存的图集精灵列表 — maskSprites 固定不变，仅图集重建时需刷新 */
	private volatile List<TextureAtlasSprite> cachedAtlasSprites;
	/**
	 * 本实例缓存的烘焙四边形列表 — 由 atlasSprites 经 {@link RenderUtils#bakeItem} 烘焙而成。
	 * <br/>
	 * bakeItem 涉及 FaceBakery.bakeQuad，开销较大，缓存后避免每帧重新烘焙。
	 */
	private volatile List<BakedQuad> cachedBakedQuads;
	/** 本实例缓存对应的代数 — 与 globalCacheGeneration 不一致时重建 */
	private volatile long cachedGeneration = -1L;

	protected AbstractBakedModelCosmic(BakedModel wrapped, List<ResourceLocation> maskSprites) {
		super(wrapped);
		this.maskSprites = maskSprites;
		// 标记为 cosmic 模型，影响 ItemOverrides.resolve 的行为
		this.cosmic = true;
	}

	/**
	 * 失效所有实例的 atlasSprites 缓存
	 * <br/>
	 * 由 {@link com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient} 在
	 * {@link net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent} 中调用。
	 * <br/>
	 * 原因：图集重新拼接后，maskSprites 对应的 TextureAtlasSprite 的 UV 坐标可能已变化，
	 * 继续使用旧缓存会导致 cosmic 渲染采样到错误纹理，因此必须通过自增代数使所有实例缓存失效，
	 * 下一帧渲染时按需重建。
	 */
	public static void invalidateCache() {
		globalCacheGeneration++;
	}

	/**
	 * 设置本类型专属的着色器 uniform
	 *
	 * @param time	游戏时间（已取模）
	 * @param yaw	玩家偏航角（弧度）
	 * @param pitch	玩家俯仰角（弧度）
	 * @param scale	外部缩放系数（GUI 时为 100，世界为 1）
	 */
	protected abstract void setupShaderUniforms(float time, float yaw, float pitch, float scale);

	/**
	 * 获取本类型对应的 RenderType
	 */
	protected abstract RenderType getRenderType();

	/**
	 * 结束本类型 RenderType 的批次，确保 cosmic 层立即刷新到 GPU
	 */
	protected abstract void endBatch(MultiBufferSource.BufferSource bufferSource);

	@Override
	public void renderItem(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		// 先渲染被包装的基础物品模型
		renderWrapped(stack, poseStack, buffers, packedLight, packedOverlay, true);
		// 立即刷新基础层，避免 cosmic 层深度测试异常
		if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
			bufferSource.endBatch();
		}
		// Iris 光影启用时延迟到世界渲染后执行，避免光影破坏 cosmic 着色器状态
		if (IrisCompat.shouldDefer(context)) {
			CosmicRenderQueue.enqueue(CosmicRenderCall.obtain(this, stack, context, poseStack, packedLight, packedOverlay, RenderSystem.getProjectionMatrix(), RenderSystem.getModelViewMatrix()));
			return;
		}
		renderCosmicLayer(stack, context, poseStack, buffers, packedLight, packedOverlay);
	}

	@Override
	public void renderCosmicLayer(ItemStack stack, ItemDisplayContext context, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
		Minecraft mc = Minecraft.getInstance();
		// 防御性检查：主菜单或世界未加载时 mc.level/mc.player 可能为 null
		// 必须在调用 mc.level.getGameTime() 之前完成，否则会 NPE
		if (mc.level == null || mc.player == null) {
			return;
		}
		float yaw = 0.0F;
		float pitch = 0.0F;
		float scale = 1.0F;
		// GUI 或库存渲染时使用固定缩放，否则基于玩家视角计算旋转
		if (CosmicShaders.cosmicInventoryRender || context == ItemDisplayContext.GUI) {
			scale = 100.0F;
		} else {
			yaw = (float) ((mc.player.getYRot() * 2.0F) * Math.PI / 360.0D);
			pitch = -((float) ((mc.player.getXRot() * 2.0F) * Math.PI / 360.0D));
		}
		// 委托子类设置专属 uniform
		setupShaderUniforms((float) (mc.level.getGameTime() % Integer.MAX_VALUE), yaw, pitch, scale);
		// 获取本类型 RenderType 对应的 VertexConsumer
		VertexConsumer consumer = buffers.getBuffer(getRenderType());
		// 获取缓存的烘焙四边形 — maskSprites 固定不变，仅图集重建时需重新烘焙
		// 缓存命中时跳过 atlasSprites 查找和 bakeItem 烘焙（FaceBakery.bakeQuad 开销较大）
		List<BakedQuad> bakedQuads = cachedBakedQuads;
		if (bakedQuads == null || cachedGeneration != globalCacheGeneration) {
			// 缓存未命中或图集已重建 — 重建 atlasSprites 并烘焙四边形
			List<TextureAtlasSprite> atlasSprites = new ArrayList<>(this.maskSprites.size());
			for (ResourceLocation mask : this.maskSprites) {
				atlasSprites.add(mc.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(mask));
			}
			cachedAtlasSprites = atlasSprites;
			bakedQuads = RenderUtils.bakeItem(atlasSprites);
			cachedBakedQuads = bakedQuads;
			cachedGeneration = globalCacheGeneration;
		}
		mc.getItemRenderer().renderQuadList(poseStack, consumer, bakedQuads, stack, packedLight, packedOverlay);
		// 立即刷新本类型批次，确保 cosmic 层在后续渲染前提交
		if (buffers instanceof MultiBufferSource.BufferSource bufferSource) {
			endBatch(bufferSource);
		}
	}
}
