package com.ayoshiko.productivebeesgenesis.multiblock.visual;

import com.ayoshiko.productivebeesgenesis.multiblock.definition.CombinedApiaryDefinition;
import java.util.List;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 固定布局的静态场景；锚点使用结构局部格角坐标，不保存世界或渲染资源。 */
public final class CombinedApiaryScene {
	/** 仅代表展示模型，不代表库存或生产进度。 */
	public enum Kind { HIVE, COMB, CHEST }
	/** 模型使用单位立方体，以 center 为中心缩放。 */
	public record Prop(Kind kind, Vec3 center, float scale) {
		/** 包含原版模型的保守局部范围。 */
		public AABB bounds() {
			double half = scale / 2.0;
			return new AABB(center.subtract(half, half, half), center.add(half, half, half));
		}
	}
	private static final List<List<Prop>> LAYOUTS = CombinedApiaryDefinition.DEFINITION.candidates().stream()
			.map(template -> {
				double z = template.geometry().coreCenter().z;
				return List.of(new Prop(Kind.HIVE, new Vec3(2.5, 2.5, z), 0.8F),
						new Prop(Kind.COMB, new Vec3(3.5, 3.5, z), 0.65F),
						new Prop(Kind.CHEST, new Vec3(4.5, 2.5, z), 0.8F));
			}).toList();
	/** 固定三种布局共享不可变锚点；非法索引返回空场景。 */
	public static List<Prop> props(int variant) {
		return variant < 0 || variant >= LAYOUTS.size() ? List.of() : LAYOUTS.get(variant);
	}
	private CombinedApiaryScene() { }
}
