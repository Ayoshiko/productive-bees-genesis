package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.config.ClientConfig.CoreEffects;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.CoreFrameBudget;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineCoreScene;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;
import org.joml.Quaternionf;

/** 中央蜂巢自转与反向卫星蜜蜂；全部为有预算的渲染几何，不创建世界实体。 */
public final class CombinedApiaryRenderer implements BlockEntityRenderer<MachineControllerEntity> {
	private static final CoreFrameBudget<MachineControllerEntity> DETAILS = new CoreFrameBudget<>();
	public CombinedApiaryRenderer(BlockEntityRendererProvider.Context context) { }
	public static void beginFrame() { DETAILS.clear(); }
	public static int detailedCount() { return DETAILS.retained(); }
	@Override public void render(MachineControllerEntity controller, float partialTick, PoseStack pose,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		var effects = ModConfig.CLIENT.machineCoreEffects.get();
		if (effects == CoreEffects.OFF) return;
		var snapshot = controller.visualSnapshot().orElse(null);
		if (snapshot == null || snapshot.template().isEmpty()) return;
		var client = Minecraft.getInstance();
		if (controller.getLevel() != client.level) return;
		boolean detailed = effects == CoreEffects.FULL && DETAILS.allow(controller);
		var space = MachineCoreScene.space(snapshot.variant());
		var center = snapshot.template().orElseThrow().geometry().at(controller.getBlockPos(), snapshot.facing()).toWorldPoint(space.center())
				.subtract(controller.getBlockPos().getX(), controller.getBlockPos().getY(), controller.getBlockPos().getZ());
		long tick = client.level.getGameTime();
		float partial = client.getTimer().getGameTimeDeltaPartialTick(true);
		pose.pushPose(); pose.translate(center.x, center.y, center.z);
		pose.mulPose(Axis.YP.rotationDegrees(180 - snapshot.facing().toYRot()));
		var solid = buffers.getBuffer(MachineSceneRenderTypes.CORE);
		core(pose, solid, space, tick, partial, detailed, false);
		if (detailed) {
			for (int bee = 0; bee < MachineCoreScene.BEES; bee++) {
				pose.pushPose(); beePose(pose, space, tick, partial, bee);
				MachineBeeMesh.body(pose, solid); pose.popPose();
			}
			var transparent = buffers.getBuffer(MachineSceneRenderTypes.TRAIL);
			for (int bee = 0; bee < MachineCoreScene.BEES; bee++) {
				pose.pushPose(); beePose(pose, space, tick, partial, bee);
				MachineBeeMesh.wings(pose, transparent, MachineCoreScene.beeTime(tick, partial, bee, 0)); pose.popPose();
			}
			MachineCoreMesh.trail(pose, transparent, space, tick, partial, false);
			var glow = buffers.getBuffer(MachineSceneRenderTypes.GLOW);
			MachineCoreMesh.trail(pose, glow, space, tick, partial, true);
			core(pose, glow, space, tick, partial, true, true);
		}
		pose.popPose();
	}
	private static void core(PoseStack pose, VertexConsumer out, MachineCoreScene.Space space,
			long tick, float partial, boolean detailed, boolean glow) {
		pose.pushPose();
		if (detailed) pose.mulPose(Axis.ZP.rotationDegrees(MachineCoreScene.bodyAngle(tick, partial)));
		pose.mulPose(Axis.XP.rotationDegrees(23)); pose.mulPose(Axis.YP.rotationDegrees(31));
		float radius = space.bodyRadius(); pose.scale(radius, radius, radius);
		if (glow) MachineCoreMesh.glow(pose, out);
		else MachineCoreMesh.hive(pose, out, detailed);
		pose.popPose();
	}
	private static void beePose(PoseStack pose, MachineCoreScene.Space space, long tick, float partial, int bee) {
		var motion = MachineCoreScene.atTime(MachineCoreScene.beeTime(tick, partial, bee, 0), bee);
		var position = motion.edge(1).multiply(space.radius());
		var tangent = motion.tangent().multiply(space.radius()).normalize();
		pose.translate(position.x, position.y, position.z);
		pose.mulPose(new Quaternionf().rotationTo(0, 0, 1, (float) tangent.x, (float) tangent.y, (float) tangent.z));
		float scale = space.beeScale(); pose.scale(scale, scale, scale);
	}
	@Override public AABB getRenderBoundingBox(MachineControllerEntity controller) {
		return controller.visualSnapshot().flatMap(frame -> frame.template().map(template ->
				template.geometry().renderBoundsAt(controller.getBlockPos(), frame.facing())))
				.orElseGet(() -> new AABB(controller.getBlockPos()));
	}
}
