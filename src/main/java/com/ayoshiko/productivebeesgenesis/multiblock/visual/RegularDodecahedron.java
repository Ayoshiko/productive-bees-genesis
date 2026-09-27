package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** 外接球半径为 1 的正十二面体：20 顶点、30 等长棱、12 个全等正五边形。 */
public final class RegularDodecahedron {
	public record Face(List<Vec3> corners, Vec3 center, Vec3 normal) {
		public Face { corners = List.copyOf(corners); }
	}
	private static final List<Vec3> VERTICES = vertices();
	private static final int[][] INDICES = {
			{17,16,0,12,1}, {10,8,0,16,2}, {14,12,0,8,4}, {3,17,1,9,11},
			{5,9,1,12,14}, {3,13,2,16,17}, {6,10,2,13,15}, {15,13,3,11,7},
			{5,14,4,18,19}, {6,18,4,8,10}, {11,9,5,19,7}, {19,18,6,15,7}};
	public static final List<Face> FACES = faces();
	private static List<Vec3> vertices() {
		double phi = (1 + Math.sqrt(5)) / 2, scale = 1 / Math.sqrt(3);
		var vertices = new ArrayList<Vec3>(20);
		for (int x : new int[]{-1, 1}) for (int y : new int[]{-1, 1}) for (int z : new int[]{-1, 1}) vertices.add(new Vec3(x, y, z).scale(scale));
		for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) vertices.add(new Vec3(0, a / phi, b * phi).scale(scale));
		for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) vertices.add(new Vec3(a / phi, b * phi, 0).scale(scale));
		for (int a : new int[]{-1, 1}) for (int b : new int[]{-1, 1}) vertices.add(new Vec3(a * phi, 0, b / phi).scale(scale));
		return List.copyOf(vertices);
	}
	private static List<Face> faces() {
		var faces = new ArrayList<Face>(12);
		for (var indices : INDICES) {
			var corners = java.util.Arrays.stream(indices).mapToObj(VERTICES::get).toList();
			var center = corners.stream().reduce(Vec3.ZERO, Vec3::add).scale(0.2);
			faces.add(new Face(corners, center, center.normalize()));
		}
		return List.copyOf(faces);
	}
	private RegularDodecahedron() { }
}
