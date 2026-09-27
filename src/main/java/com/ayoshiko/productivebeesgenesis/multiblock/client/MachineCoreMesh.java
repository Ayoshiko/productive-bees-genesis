package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.ayoshiko.productivebeesgenesis.multiblock.visual.HiveSurfacePattern;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.MachineCoreScene;
import com.ayoshiko.productivebeesgenesis.multiblock.visual.RegularDodecahedron;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** 全客户端共享合金网格和五轨道采样；渲染期间只变换顶点和插值，不持有世界历史。 */
final class MachineCoreMesh {
	private record Rim(Vec3 normal, Vec3 innerA, Vec3 outerA, Vec3 outerB, Vec3 innerB) { }
	private static final List<Rim> RIMS = rims();
	private static List<Rim> rims() {
		var rims = new ArrayList<Rim>(60);
		for (var face : RegularDodecahedron.FACES) for (int i = 0; i < 5; i++) {
			var a = face.corners().get(i); var b = face.corners().get((i + 1) % 5); var c = face.center();
			rims.add(new Rim(face.normal(), c.lerp(a, 0.85), c.lerp(a, 0.89), c.lerp(b, 0.89), c.lerp(b, 0.85)));
		}
		return List.copyOf(rims);
	}
	private static final Vec3[][][] STRIPS = new Vec3[MachineCoreScene.BEES][MachineCoreScene.CYCLE_TICKS + 1][2];
	static {
		for (int bee = 0; bee < STRIPS.length; bee++) for (int i = 0; i < STRIPS[bee].length; i++) {
			var motion = MachineCoreScene.atTime(i, bee); var center = motion.edge(1);
			STRIPS[bee][i][0] = center.add(motion.across().scale(-0.28));
			STRIPS[bee][i][1] = center.add(motion.across().scale(0.28));
		}
	}
	static void hive(PoseStack stack, VertexConsumer out, boolean detailed) {
		// 保留准确外轮廓；蜂房阵列位于分面内部，不修改正十二面体顶点。
		for (var face : RegularDodecahedron.FACES) {
			float shade = shade(stack, face.normal());
			for (int i = 0; i < 5; i++) {
				point(stack, out, face.center(), face.normal(), 0, 0x57380E, shade, 255);
				point(stack, out, face.corners().get(i), face.normal(), 0, 0x57380E, shade, 255);
				point(stack, out, face.corners().get((i + 1) % 5), face.normal(), 0, 0x57380E, shade, 255);
				point(stack, out, face.corners().get((i + 1) % 5), face.normal(), 0, 0x57380E, shade, 255);
			}
		}
		surface(stack, out, detailed, false);
		for (var rim : RIMS) {
			float shade = shade(stack, rim.normal());
			point(stack, out, rim.innerA(), rim.normal(), 0.002F, 0xFFCB63, shade, 255);
			point(stack, out, rim.outerA(), rim.normal(), 0.002F, 0xA06A1F, shade, 255);
			point(stack, out, rim.outerB(), rim.normal(), 0.002F, 0xA06A1F, shade, 255);
			point(stack, out, rim.innerB(), rim.normal(), 0.002F, 0xFFCB63, shade, 255);
		}
	}
	static void glow(PoseStack stack, VertexConsumer out) {
		surface(stack, out, true, true);
		for (var rim : RIMS) {
			point(stack, out, rim.innerA(), rim.normal(), 0.004F, 0xFFD066, 1, 60);
			point(stack, out, rim.outerA(), rim.normal(), 0.004F, 0xFFD066, 1, 60);
			point(stack, out, rim.outerB(), rim.normal(), 0.004F, 0xFFD066, 1, 60);
			point(stack, out, rim.innerB(), rim.normal(), 0.004F, 0xFFD066, 1, 60);
		}
	}
	private record Light(float shade, float specular, double sweep) { }
	private static Light metalLight(PoseStack stack, Vec3 n) {
		var matrix = stack.last().normal();
		double nx = matrix.m00() * n.x + matrix.m10() * n.y + matrix.m20() * n.z;
		double ny = matrix.m01() * n.x + matrix.m11() * n.y + matrix.m21() * n.z;
		double nz = matrix.m02() * n.x + matrix.m12() * n.y + matrix.m22() * n.z;
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		double facing = Math.max(0, (nx * -0.3 + ny * 0.5 + nz * 0.81) / length);
		return new Light(shade(stack, n), (float) Math.pow(facing, 10), nx / length * 0.8 + nz / length * 0.3);
	}
	private static int metalColor(int color, Light light, Vec3 point, boolean emissive) {
		// 窄反光带随本体法线移动；颜色受限于合金材质，暗入口不会被高光洗白。
		double band = Math.max(0, 1 - Math.abs(point.x * 0.6 + point.y * 0.8 + light.sweep()) / 0.24);
		double shine = emissive || (color >> 16 & 255) < 90 ? 0 : Math.min(0.68, light.specular() * 0.48 + band * band * 0.26);
		int r = (int) ((color >> 16 & 255) * light.shade()), g = (int) ((color >> 8 & 255) * light.shade()), b = (int) ((color & 255) * light.shade());
		return (int) (r + (255 - r) * shine) << 16 | (int) (g + (234 - g) * shine) << 8 | (int) (b + (172 - b) * shine);
	}
	private static void surface(PoseStack stack, VertexConsumer out, boolean detailed, boolean glow) {
		for (var surface : detailed ? HiveSurfacePattern.FULL : HiveSurfacePattern.REDUCED) {
			var normal = surface.face().normal(); var light = metalLight(stack, normal);
			for (var patch : surface.patches()) {
				if (glow && !patch.emissive()) continue;
				var corners = patch.corners(); int size = corners.size(), color = glow ? patch.color() : metalColor(patch.color(), light, corners.getFirst(), patch.emissive());
				float lift = glow ? 0.003F : 0.001F; int alpha = glow ? 40 : 255;
				if (size == 4) for (var corner : corners) point(stack, out, corner, normal, lift, color, 1, alpha);
				else for (int i = 1; i < size - 1; i++) {
					point(stack, out, corners.getFirst(), normal, lift, color, 1, alpha);
					point(stack, out, corners.get(i), normal, lift, color, 1, alpha);
					point(stack, out, corners.get(i + 1), normal, lift, color, 1, alpha);
					point(stack, out, corners.get(i + 1), normal, lift, color, 1, alpha);
				}
			}
		}
	}
	private static float shade(PoseStack stack, Vec3 n) {
		var normal = stack.last().normal();
		double nx = normal.m00() * n.x + normal.m10() * n.y + normal.m20() * n.z;
		double ny = normal.m01() * n.x + normal.m11() * n.y + normal.m21() * n.z;
		double nz = normal.m02() * n.x + normal.m12() * n.y + normal.m22() * n.z;
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return (float) (0.65 + 0.25 * Math.max(0, ny / length) + 0.07 * Math.max(0, (nx - nz) / length));
	}
	private static void point(PoseStack stack, VertexConsumer out, Vec3 p, Vec3 normal, float lift, int color, float shade, int alpha) {
		out.addVertex(stack.last().pose(), (float) (p.x + normal.x * lift), (float) (p.y + normal.y * lift), (float) (p.z + normal.z * lift))
				.setColor((int) ((color >> 16 & 255) * shade), (int) ((color >> 8 & 255) * shade), (int) ((color & 255) * shade), alpha);
	}
	static void trail(PoseStack stack, VertexConsumer out, MachineCoreScene.Space space, long tick, float partialTick, boolean glow) {
		for (int bee = 0; bee < MachineCoreScene.BEES; bee++) {
			double time = MachineCoreScene.beeTime(tick, partialTick, bee, 0);
			float width = glow ? 1 : 0.36F, strength = glow ? 0.48F : 0.85F;
			for (int i = 0; i < MachineCoreScene.TRAIL_SEGMENTS; i++) {
				double age = (double) i * MachineCoreScene.TRAIL_TICKS / MachineCoreScene.TRAIL_SEGMENTS;
				double nextAge = (double) (i + 1) * MachineCoreScene.TRAIL_TICKS / MachineCoreScene.TRAIL_SEGMENTS;
				trailPoint(stack, out, bee, time - age, 0, space.radius(), width, strength * MachineCoreScene.trailAlpha(age));
				trailPoint(stack, out, bee, time - age, 1, space.radius(), width, strength * MachineCoreScene.trailAlpha(age));
				trailPoint(stack, out, bee, time - nextAge, 1, space.radius(), width, strength * MachineCoreScene.trailAlpha(nextAge));
				trailPoint(stack, out, bee, time - nextAge, 0, space.radius(), width, strength * MachineCoreScene.trailAlpha(nextAge));
			}
		}
	}
	private static void trailPoint(PoseStack stack, VertexConsumer out, int bee, double time, int side, Vec3 radius, float width, float alpha) {
		double wrapped = (time % MachineCoreScene.CYCLE_TICKS + MachineCoreScene.CYCLE_TICKS) % MachineCoreScene.CYCLE_TICKS;
		int index = (int) wrapped; double fraction = wrapped - index;
		var a = STRIPS[bee][index][side]; var b = STRIPS[bee][index + 1][side];
		var oppositeA = STRIPS[bee][index][1 - side]; var oppositeB = STRIPS[bee][index + 1][1 - side];
		double ratio = (1 + width) / 2;
		double ax = oppositeA.x + (a.x - oppositeA.x) * ratio, ay = oppositeA.y + (a.y - oppositeA.y) * ratio, az = oppositeA.z + (a.z - oppositeA.z) * ratio;
		double bx = oppositeB.x + (b.x - oppositeB.x) * ratio, by = oppositeB.y + (b.y - oppositeB.y) * ratio, bz = oppositeB.z + (b.z - oppositeB.z) * ratio;
		out.addVertex(stack.last().pose(), (float) ((ax + (bx - ax) * fraction) * radius.x),
				(float) ((ay + (by - ay) * fraction) * radius.y),
				(float) ((az + (bz - az) * fraction) * radius.z)).setColor(255, 176, 32, (int) (255 * alpha));
	}
	private MachineCoreMesh() { }
}
