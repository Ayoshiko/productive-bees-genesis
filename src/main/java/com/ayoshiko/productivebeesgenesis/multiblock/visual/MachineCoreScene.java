package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureGeometry;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 中央正十二面蜂巢自转，五只纯几何蜜蜂沿五个倾斜莫比乌斯带边界反向绕行。 */
public final class MachineCoreScene {
	public static final int LAP_TICKS = 360;
	public static final int TRAIL_TICKS = 360;
	public static final int TRAIL_SEGMENTS = 48;
	public static final int CYCLE_TICKS = LAP_TICKS * 2, BEES = 5;
	public record Space(Vec3 center, Vec3 radius) {
		public AABB bounds() { return new AABB(center.subtract(radius), center.add(radius)); }
		/** 本体必须等比缩放，不能随长方体空腔拉伸成不规则多面体。 */
		public float bodyRadius() { return (float) (Math.min(radius.x, Math.min(radius.y, radius.z)) * 0.48); }
		public float beeScale() { return (float) (Math.min(radius.x, Math.min(radius.y, radius.z)) * 0.30); }
		public Vec3 point(Vec3 normalized) { return center.add(normalized.multiply(radius)); }
	}
	private record Orbit(Vec3 right, Vec3 up, Vec3 normal, double phase) {
		Vec3 transform(Vec3 point) { return right.scale(point.x).add(up.scale(point.y)).add(normal.scale(point.z)); }
	}
	private static final List<Orbit> ORBITS = List.of(
			orbit(18, 0, 77), orbit(37, 72, 716), orbit(58, 144, 135),
			orbit(42, 216, 5), orbit(67, 288, 481));
	private static Orbit orbit(double tilt, double yaw, double phase) {
		double t = Math.toRadians(tilt), y = Math.toRadians(yaw);
		var right = new Vec3(Math.cos(y), Math.sin(y), 0);
		var up = new Vec3(-Math.sin(y) * Math.cos(t), Math.cos(y) * Math.cos(t), Math.sin(t));
		return new Orbit(right, up, right.cross(up), phase);
	}
	public static Vec3 orbitNormal(int bee) { return ORBITS.get(bee).normal(); }
	public record Motion(Vec3 center, Vec3 across, Vec3 tangent) {
		public Vec3 edge(int side) { return center.add(across.scale(side)); }
	}
	private static final List<Space> SPACES = CombinedApiaryDefinition.DEFINITION.candidates().stream()
			.map(template -> spaceFor(template.geometry())).toList();
	public static Space space(int variant) { return SPACES.get(variant); }
	private static Space spaceFor(StructureGeometry geometry) {
		var box = geometry.visualBounds(); var center = geometry.coreCenter();
		return new Space(center, new Vec3(Math.min(center.x - box.minX, box.maxX - center.x) * 0.92,
				Math.min(center.y - box.minY, box.maxY - center.y) * 0.92,
				Math.min(center.z - box.minZ, box.maxZ - center.z) * 0.92));
	}
	public static double time(long tick, float partialTick) {
		return tick < 0 || !Float.isFinite(partialTick) || partialTick < 0 || partialTick > 1
				? 0 : Math.floorMod(tick, CYCLE_TICKS) + (double) partialTick;
	}
	public static float bodyAngle(long tick, float partialTick) { return (float) (time(tick, partialTick) * 360 / CYCLE_TICKS); }
	public static double beeTime(long tick, float partialTick, int bee, double age) {
		return time(tick, partialTick) + ORBITS.get(bee).phase() - age;
	}
	public static Motion sample(long tick, float partialTick) { return atTime(time(tick, partialTick)); }
	public static Motion atTime(double ticks) { return atTime(ticks, 0); }
	public static Motion atTime(double ticks, int bee) {
		double u = -(ticks % CYCLE_TICKS) * Math.PI * 2 / LAP_TICKS;
		double cos = Math.cos(u), sin = Math.sin(u), halfCos = Math.cos(u / 2), halfSin = Math.sin(u / 2);
		double radius = 0.755 + 0.105 * halfCos;
		var tangent = new Vec3(radius * sin + 0.0525 * halfSin * cos,
				-radius * cos + 0.0525 * halfSin * sin, -0.0525 * halfCos).normalize();
		var orbit = ORBITS.get(bee);
		return new Motion(orbit.transform(new Vec3(0.755 * cos, 0.755 * sin, 0)),
				orbit.transform(new Vec3(0.105 * halfCos * cos, 0.105 * halfCos * sin, 0.105 * halfSin)), orbit.transform(tangent));
	}
	public static float trailAlpha(double ageTicks) {
		double remaining = Math.clamp(1 - ageTicks / TRAIL_TICKS, 0, 1);
		return (float) remaining;
	}
	private MachineCoreScene() { }
}
