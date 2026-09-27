package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegularDodecahedronTest {
	@Test void twelveRegularPentagonsHaveExactlyTwentyVerticesAndThirtySharedEdges() {
		double phi = (1 + Math.sqrt(5)) / 2;
		double edgeLength = 2 / (phi * Math.sqrt(3));
		var vertices = new HashSet<Vec3>();
		var edges = new HashMap<Set<Vec3>, Integer>();
		assertEquals(12, RegularDodecahedron.FACES.size());
		for (var face : RegularDodecahedron.FACES) {
			assertEquals(5, face.corners().size());
			vertices.addAll(face.corners());
			for (int i = 0; i < 5; i++) {
				var a = face.corners().get(i);
				var b = face.corners().get((i + 1) % 5);
				var previous = face.corners().get((i + 4) % 5);
				assertEquals(1, a.length(), 1e-12);
				assertEquals(edgeLength, a.distanceTo(b), 1e-12);
				assertEquals(Math.cos(Math.toRadians(108)), b.subtract(a).normalize().dot(previous.subtract(a).normalize()), 1e-12);
				assertEquals(phi * edgeLength, a.distanceTo(face.corners().get((i + 2) % 5)), 1e-12);
				edges.merge(Set.of(a, b), 1, Integer::sum);
			}
		}
		assertEquals(20, vertices.size()); assertEquals(30, edges.size());
		assertTrue(edges.values().stream().allMatch(n -> n == 2));
		assertEquals(2, vertices.size() - edges.size() + RegularDodecahedron.FACES.size());
	}
	@Test void allFacesArePlanarCongruentOutwardAndBoundAConvexSolid() {
		var faces = RegularDodecahedron.FACES;
		double inradius = faces.getFirst().center().length();
		for (var face : faces) {
			assertEquals(inradius, face.center().length(), 1e-12);
			assertEquals(1, face.normal().length(), 1e-12);
			var a = face.corners().get(0); var b = face.corners().get(1); var c = face.corners().get(2);
			assertTrue(b.subtract(a).cross(c.subtract(b)).dot(face.normal()) > 0);
			for (var corner : face.corners()) assertEquals(inradius, corner.dot(face.normal()), 1e-12);
			for (var other : faces) for (var corner : other.corners()) assertTrue(corner.dot(face.normal()) <= inradius + 1e-12);
		}
		assertThrows(UnsupportedOperationException.class, () -> faces.clear());
		assertThrows(UnsupportedOperationException.class, () -> faces.getFirst().corners().clear());
	}
}
