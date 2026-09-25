package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 无世界访问、墙钟或累积积分；相同描述和相对游戏时间始终得到相同姿态。 */
public final class CombinedApiaryTimeline {
	public enum Stage { REST, FLY_IN, HIVE_FADE, PRESS, BURST, HOVER, COLLECT }
	/** center 为结构局部坐标，scale 为单位立方体的全尺寸。 */
	public record Pose(Vec3 center, Vec3 scale, double alpha) {
		public AABB bounds() { return new AABB(center.subtract(scale.scale(0.5)), center.add(scale.scale(0.5))); }
	}
	public record Icon(int appearance, Pose pose, double rotation) { }
	public record Frame(Stage stage, Pose bee, Vec3 beeDirection, double wingAngle,
	                    Pose hive, Pose comb, Pose chest, Pose liquid, Pose ring,
	                    double chestOpen, List<Icon> icons) { }

	public static Frame sample(MachineActivityEvent event, double elapsedTicks) {
		var props = CombinedApiaryScene.props(event.structure().variant());
		var hive = props.get(0); var comb = props.get(1); var chest = props.get(2);
		boolean active = Double.isFinite(elapsedTicks) && elapsedTicks >= 0 && elapsedTicks < event.activity().duration();
		double t = active ? elapsedTicks + event.activity().start() : 0;
		Stage stage = !active ? Stage.REST : t < 30 ? Stage.FLY_IN : t < 55 ? Stage.HIVE_FADE
				: t < 85 ? Stage.PRESS : t < 95 ? Stage.BURST : t < 125 ? Stage.HOVER : Stage.COLLECT;
		double fade = smooth((t - 30) / 25), press = smooth((t - 55) / 30);
		boolean showHiveWork = active && event.activity() != MachineActivityEvent.Activity.CENTRIFUGE;
		Pose hivePose = uniform(hive.center(), hive.scale() * (showHiveWork ? 1 + 0.2 * fade : 1), showHiveWork ? 1 - fade : 1);
		Pose combPose = new Pose(comb.center(), new Vec3(comb.scale() * (1 + 0.35 * press),
				comb.scale() * (1 - 0.7 * press), comb.scale() * (1 + 0.35 * press)), showHiveWork ? fade : 1);
		Vec3 start = hive.center().add(-0.15, 0.7, -0.25), control = hive.center().add(0.25, 0.85, -0.4);
		Vec3 end = hive.center().add(0, 0.15, -0.4);
		double fly = smooth(t / 30);
		Pose bee = uniform(curve(start, control, end, fly), 0.24, showHiveWork ? 1 - smooth((t - 27) / 6) : 0);
		Vec3 direction = control.subtract(start).scale(2 * (1 - fly)).add(end.subtract(control).scale(2 * fly)).normalize();
		Pose liquid = new Pose(comb.center().add(0, -0.38, 0), new Vec3(0.08, 0.22, 0.08),
				stage == Stage.PRESS ? Math.sin(Math.PI * press) : 0);
		Pose ring = new Pose(comb.center(), new Vec3(0.9, 0.04, 0.9), stage == Stage.BURST ? 1 - (t - 85) / 10 : 0);
		var icons = new ArrayList<Icon>();
		if (active && t >= 85 && event.activity() != MachineActivityEvent.Activity.APIARY) {
			int count = event.resources().size() * 3;
			for (int i = 0; i < count; i++) {
				// 种子只选视觉角度，图标数与实际产量无关；轨迹凸包在预留空气格内。
				double angle = i * Math.PI * 2 / count + (event.seed() & 65535L) * Math.PI * 2 / 65536;
				Vec3 target = comb.center().add(Math.cos(angle) * 0.30,
						Math.sin(angle * 3) * 0.10 + Math.sin((t - 95) * 0.12) * 0.025, Math.sin(angle) * 0.30);
				Vec3 center = comb.center().lerp(target, smooth((t - 85) / 10));
				double collect = smooth((t - 125 - i * 0.25) / (35 - i * 0.25));
				if (t >= 125) center = curve(target, chest.center().add(-0.1, 1.1, 0), chest.center().add(0, 0.1, 0), collect);
				icons.add(new Icon(i % event.resources().size(), uniform(center, 0.18, 1 - collect), angle + t * 0.04));
			}
		}
		double open = stage == Stage.COLLECT ? Math.sin(Math.PI * (t - 125) / 35) : 0;
		return new Frame(stage, bee, direction, active ? Math.sin(t * 2) * 0.6 : 0,
				hivePose, combPose, uniform(chest.center(), chest.scale(), 1), liquid, ring, open, List.copyOf(icons));
	}
	private static Pose uniform(Vec3 center, double scale, double alpha) { return new Pose(center, new Vec3(scale, scale, scale), alpha); }
	private static double smooth(double value) { double v = Math.clamp(value, 0, 1); return v * v * (3 - 2 * v); }
	private static Vec3 curve(Vec3 start, Vec3 control, Vec3 end, double t) {
		return start.scale((1 - t) * (1 - t)).add(control.scale(2 * t * (1 - t))).add(end.scale(t * t));
	}
	private CombinedApiaryTimeline() { }
}
