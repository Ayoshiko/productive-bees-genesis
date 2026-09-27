package com.ayoshiko.productivebeesgenesis.multiblock.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MachineBeeMeshTest {
	@Test void fullSizePixelModelAndEveryWingPoseFitSatelliteEnvelope() {
		var sink = new Sink(); var pose = new PoseStack();
		MachineBeeMesh.body(pose, sink);
		int body = sink.count;
		assertTrue(body > 400 && body < 2000);
		assertTrue(sink.dark > 100 && sink.gold > 100, "Face and stripe colors were lost");
		for (int tick = 0; tick < 64; tick++) {
			var wings = new Sink(); MachineBeeMesh.wings(pose, wings, tick * 0.125);
			assertEquals(MachineBeeMesh.wingVertices(), wings.count);
			assertTrue(wings.count >= 80 && wings.count < 300);
		}
		assertEquals(MachineBeeMesh.bodyVertices(), body);
		System.out.println("bee vertices body=" + body + " wings=" + MachineBeeMesh.wingVertices());
	}
	private static final class Sink implements VertexConsumer {
		int count, dark, gold;
		@Override public VertexConsumer addVertex(float x, float y, float z) {
			assertTrue(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z));
			assertTrue(x * x + y * y + z * z <= 0.45 * 0.45, "Detailed bee escaped its conservative radius");
			count++; return this;
		}
		@Override public VertexConsumer setColor(int r, int g, int b, int a) { if (r < 120) dark++; if (r > 150 && g > 100 && b < 110) gold++; return this; }
		@Override public VertexConsumer setUv(float u, float v) { return this; }
		@Override public VertexConsumer setUv1(int u, int v) { return this; }
		@Override public VertexConsumer setUv2(int u, int v) { return this; }
		@Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
	}
}
