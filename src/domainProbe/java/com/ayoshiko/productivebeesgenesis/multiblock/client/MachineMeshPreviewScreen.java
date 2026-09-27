package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.math.Axis;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** 仅开发源集：用实际生产网格放大检查细节，不进入发布 JAR。 */
public final class MachineMeshPreviewScreen extends Screen {
	public MachineMeshPreviewScreen() { super(Component.literal("程序化蜂巢与蜜蜂模型")); }
	@Override public boolean isPauseScreen() { return false; }
	@Override public void renderBackground(GuiGraphics graphics, int x, int y, float partial) { }
	@Override public void render(GuiGraphics graphics, int x, int y, float partial) {
		graphics.fill(0, 0, width, height, 0xFF25282E); graphics.flush();
		var pose = graphics.pose(); var buffers = graphics.bufferSource();
		pose.pushPose(); pose.translate(width * 0.28, height * 0.5, 200);
		float hiveScale = height * 0.29F; pose.scale(hiveScale, -hiveScale, hiveScale);
		pose.mulPose(Axis.XP.rotationDegrees(22)); pose.mulPose(Axis.YP.rotationDegrees(-28));
		MachineCoreMesh.hive(pose, buffers.getBuffer(MachineSceneRenderTypes.CORE), true);
		MachineCoreMesh.glow(pose, buffers.getBuffer(MachineSceneRenderTypes.GLOW)); pose.popPose();
		pose.pushPose(); pose.translate(width * 0.76, height * 0.51, 200);
		float beeScale = height * 0.95F; pose.scale(beeScale, -beeScale, beeScale);
		pose.mulPose(Axis.XP.rotationDegrees(24)); pose.mulPose(Axis.YP.rotationDegrees(-38));
		MachineBeeMesh.body(pose, buffers.getBuffer(MachineSceneRenderTypes.CORE));
		MachineBeeMesh.wings(pose, buffers.getBuffer(MachineSceneRenderTypes.TRAIL), 0.75); pose.popPose();
		graphics.flush();
		graphics.drawCenteredString(font, "正十二面体蜂巢 · 蜂蜜金分面", (int) (width * 0.28), (int) (height * 0.88), 0xE9D7AD);
		graphics.drawCenteredString(font, "原版比例蜜蜂 · 放大细节", (int) (width * 0.76), (int) (height * 0.88), 0xE9D7AD);
	}
}
