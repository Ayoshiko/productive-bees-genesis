package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.client.CombinedApiaryRenderer;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineCoreScene;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 捕获真实 BER 顶点验证有限包络与共享预算；仅开发源集，无资产操作。 */
final class MachineSceneClientChecks {
	private static final int DETAILED_VERTICES = 1904, SIMPLE_VERTICES = 48;
	private static long pausedHash;
	static Object renderer(Minecraft client) {
		return client.getBlockEntityRenderDispatcher().getRenderer(
				(MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst()));
	}
	static void ready(Minecraft client) {
		long originalTime = client.level.getGameTime();
		try {
			for (var pos : MachineVisualFixture.POSITIONS) {
				var core = (MachineControllerEntity) client.level.getBlockEntity(pos);
				var frame = core.visualSnapshot().orElseThrow();
				var renderer = client.getBlockEntityRenderDispatcher().getRenderer(core);
				check(renderer instanceof CombinedApiaryRenderer, "Missing core renderer");
				var geometry = frame.template().orElseThrow().geometry();
				check(renderer.getRenderBoundingBox(core).equals(geometry.renderBoundsAt(pos, frame.facing())), "Bounds diverged from definition");
				check(!renderer.shouldRenderOffScreen(core) && !renderer.shouldRender(core, pos.getCenter().add(100, 0, 0)), "Core bypassed culling");
				long previous = 0;
				// 覆盖完整 18 次层转及其中的插值，而非只取层转的静止端点。
				for (int tick = 0; tick < 1440; tick += 20) {
					client.level.setGameTime(tick);
					CombinedApiaryRenderer.beginFrame();
					var sink = capture(client, core);
					check(sink.count == DETAILED_VERTICES, "Detailed mesh changed its vertex budget");
					if (tick > 0) check(sink.hash != previous, "Core animation did not change geometry");
					check(sink.hash == capture(client, core).hash, "Repeated render changed pose");
					previous = sink.hash;
				}
			}
			CombinedApiaryRenderer.beginFrame();
			for (int i = 0; i < MachineVisualFixture.POSITIONS.size(); i++) {
				var core = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.get(i));
				int expected = i < 16 ? DETAILED_VERTICES : SIMPLE_VERTICES;
				check(capture(client, core).count == expected, "Per-frame detail budget was not shared");
			}
			check(CombinedApiaryRenderer.detailedCount() == 16, "Detail table exceeded global bound");
			CombinedApiaryRenderer.beginFrame();
			var first = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst());
			check(capture(client, first).count == DETAILED_VERTICES, "Next frame did not release budget");
		} finally {
			client.level.setGameTime(originalTime);
			CombinedApiaryRenderer.beginFrame();
		}
	}
	static void pauseBaseline(Minecraft client) { pausedHash = firstHash(client); }
	static void paused(Minecraft client) { check(firstHash(client) == pausedHash, "Core kept moving while paused"); }
	static boolean resumed(Minecraft client) { return firstHash(client) != pausedHash; }
	private static long firstHash(Minecraft client) {
		CombinedApiaryRenderer.beginFrame();
		return capture(client, (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst())).hash;
	}
	static Sink capture(Minecraft client, MachineControllerEntity core) {
		var renderer = client.getBlockEntityRenderDispatcher().getRenderer(core);
		var frame = core.visualSnapshot().orElseThrow();
		var geometry = frame.template().orElseThrow().geometry();
		var transform = geometry.at(core.getBlockPos(), frame.facing());
		var space = MachineCoreScene.space(frame.variant());
		var bounds = renderer.getRenderBoundingBox(core);
		var sink = new Sink() {
			@Override public VertexConsumer addVertex(float x, float y, float z) {
				super.addVertex(x, y, z);
				var point = new Vec3(x + core.getBlockPos().getX(), y + core.getBlockPos().getY(), z + core.getBlockPos().getZ());
				check(bounds.inflate(0.0001).contains(point), "Core vertex escaped finite bounds");
				var local = transform.toLocalPoint(point).subtract(space.center());
				double radiusSquared = Math.pow(local.x / space.radius().x, 2) + Math.pow(local.y / space.radius().y, 2) + Math.pow(local.z / space.radius().z, 2);
				check(radiusSquared <= 1.0001, "Core vertex escaped normalized sphere");
				return this;
			}
		};
		var pose = new PoseStack();
		renderer.render(core, 0, pose, type -> sink, 0, OverlayTexture.NO_OVERLAY);
		check(pose.clear(), "Core leaked a pose");
		return sink;
	}
	static void hidden(Minecraft client, int index) {
		var core = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.get(index));
		var renderer = client.getBlockEntityRenderDispatcher().getRenderer(core);
		check(renderer instanceof CombinedApiaryRenderer, "Missing hidden core renderer");
		var sink = new Sink();
		renderer.render(core, 0, new PoseStack(), type -> sink, 0, OverlayTexture.NO_OVERLAY);
		check(sink.count == 0 && renderer.getRenderBoundingBox(core).equals(new AABB(core.getBlockPos())), "Inactive core retained geometry");
	}
	static class Sink implements VertexConsumer {
		int count;
		long hash = 1;
		@Override public VertexConsumer addVertex(float x, float y, float z) {
			check(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z), "Non-finite vertex");
			count++; hash = 31 * hash + Float.floatToIntBits(x); hash = 31 * hash + Float.floatToIntBits(y); hash = 31 * hash + Float.floatToIntBits(z);
			return this;
		}
		@Override public VertexConsumer setColor(int r, int g, int b, int a) {
			check(r >= 0 && r <= 255 && g >= 0 && g <= 255 && b >= 0 && b <= 255 && a >= 0 && a <= 255, "Invalid vertex color"); return this;
		}
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}
	private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineSceneClientChecks() { }
}
