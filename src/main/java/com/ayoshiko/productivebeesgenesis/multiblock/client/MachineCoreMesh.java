package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/** 共享原生几何；所有坐标归一化到单位球内，不为装饰创建世界实体。 */
final class MachineCoreMesh {
	private static final float[][] FACES = {
			{-1,-1,-1, -1,-1,1, -1,1,1, -1,1,-1}, {1,-1,1, 1,-1,-1, 1,1,-1, 1,1,1},
			{-1,-1,1, -1,-1,-1, 1,-1,-1, 1,-1,1}, {-1,1,-1, -1,1,1, 1,1,1, 1,1,-1},
			{1,-1,-1, -1,-1,-1, -1,1,-1, 1,1,-1}, {-1,-1,1, 1,-1,1, 1,1,1, -1,1,1}};
	private static final int SEGMENTS = 64;
	private static final float[] COS = new float[SEGMENTS + 1], SIN = new float[SEGMENTS + 1];
	static { for (int i = 0; i <= SEGMENTS; i++) { COS[i] = (float) Math.cos(i * Math.PI * 2 / SEGMENTS); SIN[i] = (float) Math.sin(i * Math.PI * 2 / SEGMENTS); } }
	static void cube(PoseStack stack, VertexConsumer out, float half, boolean plated) {
		for (int face = 0; face < FACES.length; face++) {
			int normalAxis = face / 2; float normalSign = (face & 1) == 0 ? -1 : 1;
			var normal = stack.last().normal();
			float nx = (normalAxis == 0 ? normal.m00() : normalAxis == 1 ? normal.m10() : normal.m20()) * normalSign;
			float ny = (normalAxis == 0 ? normal.m01() : normalAxis == 1 ? normal.m11() : normal.m21()) * normalSign;
			float nz = (normalAxis == 0 ? normal.m02() : normalAxis == 1 ? normal.m12() : normal.m22()) * normalSign;
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			float shade = 0.63F + 0.27F * Math.max(0, ny / length) + 0.1F * Math.max(0, (nx - nz) / length);
			for (int layer = 0; layer < (plated ? 2 : 1); layer++) for (int i = 0; i < 4; i++) {
				float x = FACES[face][i * 3], y = FACES[face][i * 3 + 1], z = FACES[face][i * 3 + 2];
				if (layer == 1) { x *= normalAxis == 0 ? 1.002F : 0.8F; y *= normalAxis == 1 ? 1.002F : 0.8F; z *= normalAxis == 2 ? 1.002F : 0.8F; }
				float brightness = shade * (layer == 1 || !plated ? 1 : 0.40F);
				out.addVertex(stack.last().pose(), x * half, y * half, z * half).setColor((int) (231 * brightness), (int) (167 * brightness), (int) (58 * brightness), 255);
			}
		}
	}
	static void ring(PoseStack stack, VertexConsumer out, float radius, float alpha, boolean accent) {
		for (int i = 0; i < SEGMENTS; i++) {
			point(stack, out, COS[i] * (radius - 0.014F), 0, SIN[i] * (radius - 0.014F), alpha, accent);
			point(stack, out, COS[i] * (radius + 0.014F), 0, SIN[i] * (radius + 0.014F), alpha, accent);
			point(stack, out, COS[i+1] * (radius + 0.014F), 0, SIN[i+1] * (radius + 0.014F), alpha, accent);
			point(stack, out, COS[i+1] * (radius - 0.014F), 0, SIN[i+1] * (radius - 0.014F), alpha, accent);
		}
	}
	static void point(PoseStack stack, VertexConsumer out, float x, float y, float z, float alpha, boolean accent) {
		out.addVertex(stack.last().pose(), x, y, z).setColor(accent ? 119 : 255, accent ? 237 : 221, accent ? 231 : 143, (int) (255 * alpha));
	}
	private MachineCoreMesh() { }
}
