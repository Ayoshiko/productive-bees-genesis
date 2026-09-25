package com.ayoshiko.productivebeesgenesis.multiblock.world;

import com.ayoshiko.productivebeesgenesis.multiblock.client.CombinedApiaryRenderer;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.CombinedApiaryScene;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 在真实资源与 BE 上捕获渲染顶点，验证实际坐标和失效隐藏；不进入发布产物。 */
final class MachineSceneClientChecks {
	private static long nextLightDiagnostic;
	static boolean lightingReady(Minecraft client) {
		for (var pos : MachineVisualFixture.POSITIONS) {
			var core = (MachineControllerEntity) client.level.getBlockEntity(pos);
			var frame = core.visualSnapshot().orElseThrow();
			var transform = frame.template().orElseThrow().geometry().at(pos, frame.facing());
			for (var prop : CombinedApiaryScene.props(frame.variant())) {
				var lightPos = net.minecraft.core.BlockPos.containing(transform.toWorldPoint(prop.center()));
				if (client.level.getBrightness(net.minecraft.world.level.LightLayer.SKY, lightPos) == 0) {
					if (System.nanoTime() >= nextLightDiagnostic) {
						nextLightDiagnostic = System.nanoTime() + 5_000_000_000L;
						com.mojang.logging.LogUtils.getLogger().info("SCENE_CLIENT_DARK {} {}", lightPos, prop.kind());
					}
					return false;
				}
			}
		}
		return true;
	}
	static Object renderer(Minecraft client) {
		return client.getBlockEntityRenderDispatcher().getRenderer(
				(MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.getFirst()));
	}
	static void ready(Minecraft client) {
		for (var pos : MachineVisualFixture.POSITIONS) {
			var core = (MachineControllerEntity) client.level.getBlockEntity(pos);
			var frame = core.visualSnapshot().orElseThrow();
			var renderer = client.getBlockEntityRenderDispatcher().getRenderer(core);
			check(renderer instanceof CombinedApiaryRenderer, "Missing scene renderer");
			var geometry = frame.template().orElseThrow().geometry();
			var bounds = renderer.getRenderBoundingBox(core);
			check(bounds.equals(geometry.renderBoundsAt(pos, frame.facing())), "Scene bounds diverged from structure");
			check(!renderer.shouldRenderOffScreen(core) && !renderer.shouldRender(core, pos.getCenter().add(100, 0, 0)), "Scene bypassed culling");
			var transform = geometry.at(pos, frame.facing());
			var props = CombinedApiaryScene.props(frame.variant()).stream().map(p -> transform.toWorldBounds(p.bounds()).inflate(0.0001)).toList();
			int[] seen = new int[props.size()];
			var sink = new Sink() {
				@Override public VertexConsumer addVertex(float x, float y, float z) {
					super.addVertex(x, y, z);
					var point = new Vec3(x + pos.getX(), y + pos.getY(), z + pos.getZ());
					check(bounds.inflate(0.0001).contains(point), "Scene vertex escaped finite bounds");
					boolean found = false;
					for (int i = 0; i < props.size(); i++) if (props.get(i).contains(point)) { seen[i]++; found = true; }
					check(found, "Scene vertex does not match an air-space anchor");
					return this;
				}
			};
			var pose = new PoseStack();
			renderer.render(core, 0, pose, type -> sink, 0, OverlayTexture.NO_OVERLAY);
			check(pose.clear(), "Scene leaked a pose");
			check(sink.lit, "Scene inherited dark controller lighting at " + pos);
			for (int count : seen) check(count >= 24, "A scene prop emitted no complete model");
		}
		var missing = MissingTextureAtlasSprite.getLocation();
		for (var block : new net.minecraft.world.level.block.Block[]{Blocks.BEEHIVE, Blocks.HONEYCOMB_BLOCK}) {
			var model = client.getBlockRenderer().getBlockModel(block.defaultBlockState());
			check(model != client.getModelManager().getMissingModel() && !model.getParticleIcon(net.neoforged.neoforge.client.model.data.ModelData.EMPTY).contents().name().equals(missing), "Missing scene block model");
		}
		check(!Sheets.CHEST_LOCATION.sprite().contents().name().equals(missing), "Missing scene chest texture");
	}
	static void hidden(Minecraft client, int index) {
		var core = (MachineControllerEntity) client.level.getBlockEntity(MachineVisualFixture.POSITIONS.get(index));
		var renderer = client.getBlockEntityRenderDispatcher().getRenderer(core);
		check(renderer instanceof CombinedApiaryRenderer, "Missing hidden scene renderer");
		var sink = new Sink();
		renderer.render(core, 0, new PoseStack(), type -> sink, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
		check(sink.count == 0 && renderer.getRenderBoundingBox(core).equals(new AABB(core.getBlockPos())), "Inactive scene retained geometry");
	}
	private static class Sink implements VertexConsumer {
		int count;
		boolean lit;
		@Override public VertexConsumer addVertex(float x, float y, float z) { count++; return this; }
		@Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { lit |= u > 0 || v > 0; return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}
	private static void check(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
	private MachineSceneClientChecks() { }
}
