package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.visual.CombinedApiaryScene;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.model.data.ModelData;

/** 客户端静态内部场景；没有实体、库存访问、世界修改或逐机动画缓存。 */
public final class CombinedApiaryRenderer implements BlockEntityRenderer<MachineControllerEntity> {
	private final BlockRenderDispatcher blocks;
	private final ModelPart chest;

	/** 原版资源重载重建 BER，同时重新烘焙箱子模型。 */
	public CombinedApiaryRenderer(BlockEntityRendererProvider.Context context) {
		blocks = context.getBlockRenderDispatcher();
		chest = context.bakeLayer(ModelLayers.CHEST);
	}

	@Override
	public void render(MachineControllerEntity controller, float partialTick, PoseStack pose,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		var frame = controller.visualSnapshot().orElse(null);
		if (frame == null || frame.template().isEmpty()) return;
		var transform = frame.template().orElseThrow().geometry().at(controller.getBlockPos(), frame.facing());
		for (var prop : CombinedApiaryScene.props(frame.variant())) {
			var worldCenter = transform.toWorldPoint(prop.center());
			var lightPos = BlockPos.containing(worldCenter);
			// 直接读光照缓存，不通过控制器的不透明外壳取样，也不加载邻区块。
			int light = LightTexture.pack(controller.getLevel().getBrightness(LightLayer.BLOCK, lightPos),
					controller.getLevel().getBrightness(LightLayer.SKY, lightPos));
			var center = worldCenter.subtract(
					controller.getBlockPos().getX(), controller.getBlockPos().getY(), controller.getBlockPos().getZ());
			pose.pushPose();
			pose.translate(center.x, center.y, center.z);
			pose.mulPose(Axis.YP.rotationDegrees(180 - frame.facing().toYRot()));
			pose.scale(prop.scale(), prop.scale(), prop.scale());
			// 原版箱子模型正面朝南；蜂箱默认朝北。
			if (prop.kind() == CombinedApiaryScene.Kind.CHEST) pose.mulPose(Axis.YP.rotationDegrees(180));
			pose.translate(-0.5, -0.5, -0.5);
			switch (prop.kind()) {
				case HIVE -> blocks.renderSingleBlock(Blocks.BEEHIVE.defaultBlockState(), pose, buffers, light, packedOverlay, ModelData.EMPTY, null);
				case COMB -> blocks.renderSingleBlock(Blocks.HONEYCOMB_BLOCK.defaultBlockState(), pose, buffers, light, packedOverlay, ModelData.EMPTY, null);
				case CHEST -> chest.render(pose, Sheets.CHEST_LOCATION.buffer(buffers, RenderType::entityCutout), light, packedOverlay);
			}
			pose.popPose();
		}
	}

	@Override
	public AABB getRenderBoundingBox(MachineControllerEntity controller) {
		return controller.visualSnapshot().flatMap(frame -> frame.template().map(template ->
				template.geometry().renderBoundsAt(controller.getBlockPos(), frame.facing())))
				.orElseGet(() -> new AABB(controller.getBlockPos()));
	}
}
