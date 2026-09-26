package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 中央大型装置的单位球包络与纯时间相位；环境旋转不表示已发生生产。 */
public final class MachineCoreScene {
	public record Space(Vec3 center, Vec3 radius) {
		public AABB bounds() { return new AABB(center.subtract(radius), center.add(radius)); }
	}
	public record Motion(float yaw, float pitch, int axis, int layer, float turn, float orbit, float pulse) { }
	private static final java.util.List<Space> SPACES = CombinedApiaryDefinition.DEFINITION.candidates().stream()
			.map(template -> spaceFor(template.geometry())).toList();
	public static Space space(int variant) { return SPACES.get(variant); }
	private static Space spaceFor(com.ayoshiko.productivebeesgenesis.multiblock.geometry.StructureGeometry geometry) {
		var box = geometry.visualBounds(); var center = geometry.coreCenter();
		return new Space(center, new Vec3(Math.min(center.x - box.minX, box.maxX - center.x) * 0.92,
				Math.min(center.y - box.minY, box.maxY - center.y) * 0.92,
				Math.min(center.z - box.minZ, box.maxZ - center.z) * 0.92));
	}
	public static Motion sample(long tick, float partialTick) {
		if (tick < 0 || !Float.isFinite(partialTick) || partialTick < 0 || partialTick > 1) return sample(0, 0);
		double cycle = Math.floorMod(tick, 1440) + (double) partialTick;
		int step = (int) (cycle / 80) % 18;
		double progress = Math.clamp(((cycle % 80) / 80 - 0.15) / 0.7, 0, 1);
		float turn = (float) (90 * progress * progress * (3 - 2 * progress)) * ((step & 1) == 0 ? 1 : -1);
		return new Motion(360 * phase(tick, partialTick, 1200), 22 + 16 * (float) Math.sin(phase(tick, partialTick, 800) * Math.PI * 2),
				step % 3, (step / 3) % 3 - 1, turn, 360 * phase(tick, partialTick, 420),
				0.85F + 0.15F * (float) Math.sin(phase(tick, partialTick, 100) * Math.PI * 2));
	}
	private static float phase(long tick, float partialTick, int period) { return (float) ((Math.floorMod(tick, period) + (double) partialTick) / period); }
	private MachineCoreScene() { }
}
