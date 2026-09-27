package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** 按原版 7×7×10 像素比例建模后整体缩放；顶点色实现条纹、脸部和阶梯翅缘，不生成实体或贴图。 */
final class MachineBeeMesh {
	private static final float UNIT = 1F / 32;
	private record Vertex(Vec3 point, int color) { }
	private record Wing(int x, int end, int row, int tone) { }
	private static final int[] SIDES = {-1, 1};
	private static final List<Vertex> BODY = bodyMesh();
	private static final List<Wing> WINGS = wingMesh();
	private static List<Vertex> bodyMesh() {
		var mesh = new ArrayList<Vertex>();
		// 原版为一体式长方蜂身；脸在前端，没有另接球形头部。
		pixels(mesh, new Vec3(-3.5, -3.5, 5), new Vec3(1, 0, 0), new Vec3(0, 1, 0), 7, 7, 0, 1);
		pixels(mesh, new Vec3(3.5, -3.5, -5), new Vec3(-1, 0, 0), new Vec3(0, 1, 0), 7, 7, 1, 0.7F);
		pixels(mesh, new Vec3(-3.5, 3.5, -5), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 7, 10, 2, 1);
		pixels(mesh, new Vec3(-3.5, -3.5, 5), new Vec3(1, 0, 0), new Vec3(0, 0, -1), 7, 10, 2, 0.58F);
		pixels(mesh, new Vec3(-3.5, -3.5, -5), new Vec3(0, 0, 1), new Vec3(0, 1, 0), 10, 7, 3, 0.84F);
		pixels(mesh, new Vec3(3.5, -3.5, 5), new Vec3(0, 0, -1), new Vec3(0, 1, 0), 10, 7, 4, 0.84F);
		for (int side : SIDES) {
			box(mesh, side * 2, 3.7, 5.8, 0.5, 0.45, 1.5, 0x473820);
			box(mesh, side * 2, 4.4, 6.8, 0.5, 0.5, 0.5, 0x625036);
			for (int leg = 0; leg < 3; leg++) {
				double z = -2 + leg * 2;
				box(mesh, side * 2.6, -4.1, z, 0.35, 0.8, 0.35, 0x624523);
				box(mesh, side * 2.6, -4.8, z - 0.5, 0.35, 0.3, 0.75, 0x3E3020);
			}
		}
		box(mesh, 0, -0.5, -6, 0.25, 0.25, 1, 0x38291C);
		return List.copyOf(mesh);
	}
	private static int color(int x, int y, int face) {
		if (face == 0) {
			if ((x == 1 || x == 2 || x == 4 || x == 5) && y >= 2 && y <= 4)
				return y == 4 && (x == 1 || x == 4) ? 0x526561 : 0x252B26;
			if (y == 1 && (x == 2 || x == 4)) return 0xBA8129;
			return (x + 2 * y) % 7 < 2 ? 0xF8D45F : 0xE9B83C;
		}
		int z = face == 2 ? y : face == 3 ? x : face == 4 ? 9 - x : 0;
		boolean stripe = z == 2 || z == 3 || z == 6 || z == 7 || face == 1;
		boolean fleck = (x * 3 + y * 5) % 11 < 3;
		return stripe ? (fleck ? 0x6C4B25 : 0x51361F) : (fleck ? 0xF3CD53 : 0xDFAB32);
	}
	private static void pixels(List<Vertex> mesh, Vec3 origin, Vec3 u, Vec3 v, int width, int height, int face, float shade) {
		for (int y = 0; y < height; y++) for (int x = 0; x < width;) {
			int color = color(x, y, face), end = x + 1;
			while (end < width && color(end, y, face) == color) end++;
			quad(mesh, origin.add(u.scale(x)).add(v.scale(y)), origin.add(u.scale(end)).add(v.scale(y)),
					origin.add(u.scale(end)).add(v.scale(y + 1)), origin.add(u.scale(x)).add(v.scale(y + 1)), shade(color, shade));
			x = end;
		}
	}
	private static void box(List<Vertex> mesh, double x, double y, double z, double hx, double hy, double hz, int color) {
		Vec3 a = new Vec3(x - hx, y - hy, z - hz), b = new Vec3(x + hx, y - hy, z - hz);
		Vec3 c = new Vec3(x + hx, y + hy, z - hz), d = new Vec3(x - hx, y + hy, z - hz);
		Vec3 e = new Vec3(x - hx, y - hy, z + hz), f = new Vec3(x + hx, y - hy, z + hz);
		Vec3 g = new Vec3(x + hx, y + hy, z + hz), h = new Vec3(x - hx, y + hy, z + hz);
		quad(mesh, a, b, c, d, shade(color, 0.7F)); quad(mesh, f, e, h, g, color);
		quad(mesh, e, a, d, h, shade(color, 0.82F)); quad(mesh, b, f, g, c, shade(color, 0.82F));
		quad(mesh, d, c, g, h, color); quad(mesh, e, f, b, a, shade(color, 0.58F));
	}
	private static void quad(List<Vertex> mesh, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
		mesh.add(new Vertex(a.scale(UNIT), color)); mesh.add(new Vertex(b.scale(UNIT), color));
		mesh.add(new Vertex(c.scale(UNIT), color)); mesh.add(new Vertex(d.scale(UNIT), color));
	}
	private static int shade(int color, float shade) {
		return (int) ((color >> 16 & 255) * shade) << 16 | (int) ((color >> 8 & 255) * shade) << 8 | (int) ((color & 255) * shade);
	}
	static void body(PoseStack pose, VertexConsumer out) {
		for (var vertex : BODY) {
			var p = vertex.point(); int color = vertex.color();
			out.addVertex(pose.last().pose(), (float) p.x, (float) p.y, (float) p.z)
					.setColor(color >> 16 & 255, color >> 8 & 255, color & 255, 255);
		}
	}
	private static List<Wing> wingMesh() {
		String[] pixels = {"  11111  ", " 1222221 ", "122323221", "122222221", " 1222221 ", "  11111  "};
		var mesh = new ArrayList<Wing>();
		for (int row = 0; row < pixels.length; row++) for (int x = 0; x < 9;) {
			int tone = pixels[row].charAt(x); int end = x + 1;
			while (end < 9 && pixels[row].charAt(end) == tone) end++;
			if (tone != ' ') mesh.add(new Wing(x, end, row, tone - '0'));
			x = end;
		}
		return List.copyOf(mesh);
	}
	static void wings(PoseStack pose, VertexConsumer out, double time) {
		double angle = Math.toRadians(18 + 28 * Math.sin(time * Math.PI / 4));
		float cos = (float) Math.cos(angle), sin = (float) Math.sin(angle);
		for (int side : SIDES) for (var wing : WINGS) {
			wing(pose, out, side, wing.x(), wing.row(), cos, sin, wing.tone());
			wing(pose, out, side, wing.end(), wing.row(), cos, sin, wing.tone());
			wing(pose, out, side, wing.end(), wing.row() + 1, cos, sin, wing.tone());
			wing(pose, out, side, wing.x(), wing.row() + 1, cos, sin, wing.tone());
		}
	}
	private static void wing(PoseStack pose, VertexConsumer out, int side, int x, int z, float cos, float sin, int tone) {
		out.addVertex(pose.last().pose(), side * (1.5F + x * cos) * UNIT, (3.5F + x * sin) * UNIT, (z - 3.5F - x * 0.15F) * UNIT)
				.setColor(tone == 3 ? 182 : 237, tone == 3 ? 191 : 240, tone == 3 ? 172 : 211, tone == 2 ? 95 : 220);
	}
	static int bodyVertices() { return BODY.size(); }
	static int wingVertices() { return WINGS.size() * 8; }
	private MachineBeeMesh() { }
}
