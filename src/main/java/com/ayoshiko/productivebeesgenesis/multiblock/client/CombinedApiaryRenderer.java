package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.visual.CoreFrameBudget;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineCoreScene;
import com.ayoshiko.productivebeesgenesis.multiblock.world.MachineControllerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.phys.AABB;

/** 大型分层悬浮核心；READY 仅驱动环境动效，不伪造生产事件。 */
public final class CombinedApiaryRenderer implements BlockEntityRenderer<MachineControllerEntity> {
	private static final CoreFrameBudget<MachineControllerEntity> DETAILS = new CoreFrameBudget<>();
	public CombinedApiaryRenderer(BlockEntityRendererProvider.Context context) { }
	public static void beginFrame() { DETAILS.clear(); }
	public static int detailedCount() { return DETAILS.retained(); }

	@Override public void render(MachineControllerEntity controller, float partialTick, PoseStack pose,
			MultiBufferSource buffers, int packedLight, int packedOverlay) {
		var snapshot = controller.visualSnapshot().orElse(null);
		if (snapshot == null || snapshot.template().isEmpty()) return;
		var client = Minecraft.getInstance();
		if (controller.getLevel() != client.level) return;
		boolean detailed = DETAILS.allow(controller);
		var space = MachineCoreScene.space(snapshot.variant());
		var geometry = snapshot.template().orElseThrow().geometry();
		var center = geometry.at(controller.getBlockPos(), snapshot.facing()).toWorldPoint(space.center())
				.subtract(controller.getBlockPos().getX(), controller.getBlockPos().getY(), controller.getBlockPos().getZ());
		var motion = MachineCoreScene.sample(detailed ? client.level.getGameTime() : 0,
				detailed ? client.getTimer().getGameTimeDeltaPartialTick(true) : 0);
		pose.pushPose();
		pose.translate(center.x, center.y, center.z);
		pose.mulPose(Axis.YP.rotationDegrees(180 - snapshot.facing().toYRot()));
		pose.scale((float) space.radius().x, (float) space.radius().y, (float) space.radius().z);
		var core = buffers.getBuffer(MachineSceneRenderTypes.CORE);
		pose.pushPose(); pose.mulPose(Axis.YP.rotationDegrees(motion.yaw())); pose.mulPose(Axis.XP.rotationDegrees(motion.pitch()));
		if (detailed) {
			// 26 个同形子块的层转终点仍为相同几何集合，不积累旋转误差或保存姿态。
			pose.scale(0.76F, 0.76F, 0.76F);
			for (int x=-1;x<=1;x++) for (int y=-1;y<=1;y++) for (int z=-1;z<=1;z++) {
				if (x == 0 && y == 0 && z == 0) continue;
				pose.pushPose();
				int layer = motion.axis() == 0 ? x : motion.axis() == 1 ? y : z;
				if (layer == motion.layer()) pose.mulPose((motion.axis() == 0 ? Axis.XP : motion.axis() == 1 ? Axis.YP : Axis.ZP).rotationDegrees(motion.turn()));
				pose.translate(x * 0.4, y * 0.4, z * 0.4);
				MachineCoreMesh.cube(pose, core, 0.17F, true); pose.popPose();
			}
		} else MachineCoreMesh.cube(pose, core, 0.45F, true);
		pose.popPose();
		if (detailed) {
			var glow = buffers.getBuffer(MachineSceneRenderTypes.GLOW);
			for (int orbit=0;orbit<2;orbit++) {
				pose.pushPose();
				pose.mulPose(Axis.YP.rotationDegrees(motion.orbit() * (orbit == 0 ? 1 : -0.7F)));
				pose.mulPose(Axis.XP.rotationDegrees(orbit == 0 ? 63 : 112));
				MachineCoreMesh.ring(pose, glow, orbit == 0 ? 0.94F : 0.87F, motion.pulse() * 0.7F, orbit == 1);
				for (int node=0;node<3;node++) {
					pose.pushPose(); pose.mulPose(Axis.YP.rotationDegrees(node * 120 + motion.orbit() * 1.4F));
					pose.translate(orbit == 0 ? 0.94 : 0.87, 0, 0); pose.mulPose(Axis.ZP.rotationDegrees(45));
					MachineCoreMesh.cube(pose, glow, 0.025F, false); pose.popPose();
				}
				pose.popPose();
			}
		}
		pose.popPose();
	}
	@Override public AABB getRenderBoundingBox(MachineControllerEntity controller) {
		return controller.visualSnapshot().flatMap(frame -> frame.template().map(template ->
				template.geometry().renderBoundsAt(controller.getBlockPos(), frame.facing())))
				.orElseGet(() -> new AABB(controller.getBlockPos()));
	}
}
