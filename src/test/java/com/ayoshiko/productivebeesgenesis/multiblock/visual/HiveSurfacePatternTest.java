package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.List;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HiveSurfacePatternTest {
	@Test void sealedReliefStaysInsidePentagonFootprintsAndDoesNotChangeSilhouette() {
		for (var meshes : List.of(HiveSurfacePattern.FULL, HiveSurfacePattern.REDUCED)) for (var surface : meshes) {
			var face = surface.face();
			for (var patch : surface.patches()) {
				assertTrue(area(patch.corners()) > 0);
				for (var point : patch.corners()) {
					double relief = point.subtract(face.center()).dot(face.normal());
					assertTrue(relief >= -1e-12 && relief <= 0.014 + 1e-12);
					assertTrue(point.length() < 1, "Detail changed the regular dodecahedron silhouette");
					var projected = point.subtract(face.normal().scale(relief));
					for (var other : RegularDodecahedron.FACES)
						assertTrue(projected.subtract(other.center()).dot(other.normal()) < 1e-12);
				}
			}
		}
	}
	@Test void emissiveHoneyStaysSparseAndBothMeshesHaveFiniteBudgets() {
		assertEquals(12, HiveSurfacePattern.FULL.size());
		assertTrue(HiveSurfacePattern.vertices(true, false) < 1600);
		assertTrue(HiveSurfacePattern.vertices(false, false) < 1000);
		double honey = HiveSurfacePattern.FULL.stream().flatMap(s -> s.patches().stream()).filter(HiveSurfacePattern.Patch::emissive)
				.mapToDouble(p -> area(p.corners())).sum();
		double total = RegularDodecahedron.FACES.stream().mapToDouble(f -> area(f.corners())).sum();
		assertTrue(honey / total > 0.001 && honey / total < 0.09);
		for (var surfaces : List.of(HiveSurfacePattern.FULL, HiveSurfacePattern.REDUCED)) for (var surface : surfaces) {
			var face = surface.face();
			var u = face.corners().getFirst().subtract(face.center()).normalize(); var v = face.normal().cross(u);
			for (double radius : new double[]{0, 0.1, 0.2, 0.23}) for (int i = 0; i < 12; i++) {
				var sample = face.center().add(u.scale(radius * Math.cos(i * Math.PI / 6))).add(v.scale(radius * Math.sin(i * Math.PI / 6)));
				assertTrue(surface.patches().stream().filter(p -> !p.emissive())
						.filter(p -> p.corners().stream().allMatch(c -> c.subtract(face.center()).dot(face.normal()) > 0.005))
						.anyMatch(p -> covers(p.corners().stream().map(c -> c.subtract(face.normal().scale(c.subtract(face.center()).dot(face.normal())))).toList(), sample)),
						"Raised wax cover has an opening");
			}
		}
		System.out.println("hive vertices full=" + HiveSurfacePattern.vertices(true, false)
				+ " reduced=" + HiveSurfacePattern.vertices(false, false) + " glow=" + HiveSurfacePattern.vertices(true, true));
	}
	private static boolean covers(List<Vec3> polygon, Vec3 sample) {
		double sum = 0;
		for (int i = 0; i < polygon.size(); i++)
			sum += polygon.get(i).subtract(sample).cross(polygon.get((i + 1) % polygon.size()).subtract(sample)).length() / 2;
		return Math.abs(sum - area(polygon)) < 1e-10;
	}
	private static double area(List<Vec3> points) {
		double area = 0;
		for (int i = 1; i < points.size() - 1; i++)
			area += points.get(i).subtract(points.getFirst()).cross(points.get(i + 1).subtract(points.getFirst())).length() / 2;
		return area;
	}
}
