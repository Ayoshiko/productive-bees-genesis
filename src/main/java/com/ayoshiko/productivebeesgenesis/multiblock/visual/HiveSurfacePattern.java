package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** 每面一个封闭蜂蜡盖，以大块浮雕代替密集蜂房孔洞；网格全客户端共享。 */
public final class HiveSurfacePattern {
	public record Patch(List<Vec3> corners, int color, boolean emissive) {
		public Patch { corners = List.copyOf(corners); }
		public int vertices() { return corners.size() == 4 ? 4 : (corners.size() - 2) * 4; }
	}
	public record Surface(RegularDodecahedron.Face face, List<Patch> patches) {
		public Surface { patches = List.copyOf(patches); }
	}
	public static final List<Surface> FULL = create(true), REDUCED = create(false);
	private static List<Surface> create(boolean detailed) {
		var result = new ArrayList<Surface>(12);
		for (var face : RegularDodecahedron.FACES) {
			var patches = new ArrayList<Patch>();
			patches.add(new Patch(face.corners().stream().map(c -> face.center().lerp(c, 0.84)).toList(), 0xDFA23B, false));
			var u = face.corners().getFirst().subtract(face.center()).normalize();
			var v = face.normal().cross(u);
			var foot = hex(face, u, v, 0.35, 0.001);
			var cover = hex(face, u, v, 0.275, detailed ? 0.01 : 0.006);
			if (detailed) {
				var lower = hex(face, u, v, 0.32, 0.004);
				var upper = hex(face, u, v, 0.30, 0.004);
				bevel(patches, foot, lower, 0xC68E2C, 0xE6B34B);
				bevel(patches, lower, upper, 0xD5A240, 0xE4B65A);
				bevel(patches, upper, cover, 0xE8B954, 0xF5CE76);
				var peak = face.center().add(face.normal().scale(0.014));
				for (int i = 0; i < 6; i++) patches.add(new Patch(
						List.of(peak, cover[i], cover[(i + 1) % 6]), i < 3 ? 0xEAB94F : 0xDBA238, false));
				// 少量实体蜜液流痕，保持暖色，不用黑孔或重复的小点阵表现蜂房。
				patches.add(new Patch(List.of(point(face, u, v, 0.205, -0.12), point(face, u, v, 0.231, -0.12),
						point(face, u, v, 0.231, -0.25), point(face, u, v, 0.205, -0.25)), 0xE9A52C, true));
				patches.add(new Patch(List.of(point(face, u, v, 0.218, -0.235), point(face, u, v, 0.26, -0.276),
						point(face, u, v, 0.218, -0.327), point(face, u, v, 0.182, -0.276)), 0xF2B842, true));
			} else {
				bevel(patches, foot, cover, 0xD5A240, 0xEABD62);
				patches.add(new Patch(List.of(cover[0], cover[1], cover[2], cover[3]), 0xE8B54D, false));
				patches.add(new Patch(List.of(cover[0], cover[3], cover[4], cover[5]), 0xE8B54D, false));
			}
			result.add(new Surface(face, patches));
		}
		return List.copyOf(result);
	}
	private static Vec3[] hex(RegularDodecahedron.Face face, Vec3 u, Vec3 v, double radius, double height) {
		var points = new Vec3[6];
		for (int i = 0; i < 6; i++) {
			double angle = Math.PI / 6 + i * Math.PI / 3;
			points[i] = face.center().add(u.scale(radius * Math.cos(angle))).add(v.scale(radius * Math.sin(angle))).add(face.normal().scale(height));
		}
		return points;
	}
	private static void bevel(List<Patch> patches, Vec3[] lower, Vec3[] upper, int dark, int light) {
		for (int i = 0; i < 6; i++) {
			int next = (i + 1) % 6;
			patches.add(new Patch(List.of(lower[i], lower[next], upper[next], upper[i]), i < 3 ? light : dark, false));
		}
	}
	private static Vec3 point(RegularDodecahedron.Face face, Vec3 u, Vec3 v, double x, double y) {
		return face.center().add(u.scale(x)).add(v.scale(y)).add(face.normal().scale(0.012));
	}
	public static int vertices(boolean detailed, boolean glow) {
		return (detailed ? FULL : REDUCED).stream().flatMap(s -> s.patches().stream())
				.filter(p -> !glow || p.emissive()).mapToInt(Patch::vertices).sum();
	}
	private HiveSurfacePattern() { }
}
